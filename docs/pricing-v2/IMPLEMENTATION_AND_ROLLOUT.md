# Pricing Engine V2 — implementation, verification and rollout

## 1. Trạng thái và phạm vi

Pricing V2 đã hoàn thiện ở local cho luồng đặt phòng online, walk-in dùng
giá hệ thống, check-in theo giờ đến thực tế, gia hạn, final-payment, checkout
reconciliation và invoice snapshot. Runtime mặc định vẫn tắt:

```dotenv
PRICING_ENGINE_V2_ENABLED=false
PRICING_ENGINE_V2_ROOM_TYPE_CODES=
PRICING_ENGINE_V2_REQUIRE_QUOTE=false
PRICING_QUOTE_TTL_MINUTES=15
```

Không được bật toàn bộ production ngay sau migration. Bật theo canary từng
`room_types.code`, quan sát rồi mới mở rộng.

Môi trường local hiện bật đủ sáu mã hạng phòng và bắt buộc quote để kiểm thử
end-to-end. File cấu hình mẫu và Render vẫn giữ mặc định an toàn nêu trên.

Các luồng được giữ nguyên để tương thích:

- reservation đã tồn tại mang `LEGACY_V1`, không tính lại;
- walk-in dùng giá hệ thống đi V2 khi mã hạng phòng nằm trong canary; trường
  hợp ADMIN duyệt nhập giá tay vẫn giữ `LEGACY_V1` và audit riêng;
- client cũ không gửi quote vẫn đi legacy;
- RoomHold chỉ được tạo khi `/api/payments/create` thực sự phát QR;
- SePay, ledger, refund, idempotency, check-in và checkout state machine không
  có đường tắt mới.

## 2. Contract đặt phòng V2

### Bước báo giá

Frontend gọi:

```http
POST /api/pricing/quote
```

Request gồm:

- `checkIn`, `checkOut`;
- `guestCount`;
- từng dòng `{roomTypeId, quantity, lineGuestCount}`;
- dịch vụ booking-time đã chọn.

Invariant:

```text
guestCount = SUM(lineGuestCount)
quantity <= lineGuestCount <= roomType.maxGuests * quantity
```

Response chứa `quoteId`, `quoteHash`, expiry UTC, policy/rate version,
package, room charge, extra-guest charge, service charge, total và
`inventoryProtectedUntil`.

### Bước tạo reservation

Frontend hiển thị đúng quote server, sau đó gửi cùng `quoteId`, `quoteHash` và
`lineGuestCount` vào `POST /api/reservations`.

Trong một transaction, backend:

1. khóa quote;
2. xác nhận quote chưa hết hạn/chưa dùng;
3. khóa rate profile;
4. tính lại bằng engine;
5. so sánh request/hash/giá/dịch vụ;
6. kiểm tra tồn phòng;
7. tạo reservation `PAYMENT_PENDING`;
8. ghi commitment + rate snapshot bất biến.

Nếu giá/policy đổi, trả `409 PRICE_CHANGED`; client lấy quote mới và bắt khách
xác nhận lại. Retry cùng request dùng cùng `Idempotency-Key`.

Quote và reservation chưa giữ phòng. RoomHold chỉ được tạo ở bước phát QR.

### Fallback compatibility

Frontend chỉ fallback về cách tính legacy khi backend trả đúng application
code `5080 PRICING_ENGINE_DISABLED`. Lỗi mạng, profile thiếu, quote sai hoặc
server error đều chặn đặt phòng; không được âm thầm dùng giá local.

Trong giai đoạn compatibility, client cũ vẫn có thể không gửi quote. Web và
chatbot hiện đã dùng chung trang booking có quote server-authoritative; chatbot
không còn tạo reservation trực tiếp bằng giá legacy. Sau khi mọi client online
khác (nếu có) đã nâng cấp và canary hoàn tất, bật:

```dotenv
PRICING_ENGINE_V2_REQUIRE_QUOTE=true
```

Gate này chặn request cố ý bỏ quote để lấy giá legacy. Không bật gate trong
canary nếu còn client chưa hỗ trợ V2; cấu hình sai sẽ làm các client đó fail
loudly thay vì âm thầm tính sai giá.

## 3. Nguồn dữ liệu và snapshot

- `stay_policy_versions`: chính sách grace, threshold, overnight, turnover.
- `room_rate_profiles`: giá theo `room_types.code`, version và hiệu lực UTC.
- `pricing_quotes` + `pricing_quote_lines`: quote có hạn dùng và hash.
- `pricing_quote_commitments`: bảo đảm một quote chỉ tạo một reservation.
- `reservation_rate_snapshots`: evidence append-only theo từng dòng phòng.
- `reservation_invoice`: lưu pricing version và breakdown normalized.

Runtime không hardcode giá. Seeder chỉ tạo policy/rate version còn thiếu và
không ghi đè lịch sử đã phát sinh.

## 4. Gia hạn và tồn phòng

Gia hạn chỉ áp dụng tự động cho reservation V2 ở `CONFIRMED` hoặc
`CHECKED_IN`.

Trước khi thay đổi:

1. khóa reservation;
2. project giá và entitlement mới từ snapshot đã cam kết;
3. kiểm tra lại availability, loại trừ chính reservation hiện tại;
4. nếu chồng tồn phòng đã bán/hold thì dừng, không đổi checkout;
5. append snapshot `EXTENSION`;
6. chỉ tăng nghĩa vụ tiền, không tự giảm mức đã cam kết;
7. reprice dịch vụ booking-time `PER_NIGHT` bằng unit-price snapshot.

`inventoryProtectedUntil`:

```text
HOURLY/DAILY:
  max(plannedCheckout, packageIncludedCheckout) + turnoverBuffer

OVERNIGHT:
  max(plannedCheckout, operationalNightHardCheckout) + turnoverBuffer
```

Turnover chỉ bảo vệ tồn phòng, không cộng vào doanh thu.

Với policy hiện tại, `operationalNightHardCheckout` là 12:00. Vì vậy khách
đến muộn vẫn có thể dùng đủ tối đa 12 giờ nhưng không quá 12:00 mà không bị
đơn kế tiếp bán chồng quyền đã hứa. Khi khách checkout sớm:

- reservation chuyển `CHECKED_OUT`;
- assignment chuyển `CHECKED_OUT`;
- phòng vật lý chuyển `AVAILABLE` và `DIRTY`;
- truy vấn availability loại ngay reservation `CHECKED_OUT`.

Do đó phần tồn phòng còn được bảo vệ trong tương lai được giải phóng ngay,
nhưng phòng chỉ sẵn sàng gán lại sau khi hoàn tất dọn phòng.

## 5. Final payment, checkout và invoice

- preview hiển thị planned room, actual/projected room, extra guest,
  booking-time/in-stay services và post-commitment increase riêng biệt;
- `planned*` đọc từ snapshot `COMMITMENT`;
- `actual*` đọc từ snapshot đã ghi gần nhất;
- khi đang `CHECKED_IN`, API reservation trả thêm `projected*` tính read-only
  theo thời điểm hiện tại và từng dòng hạng phòng; projection không sửa tiền,
  payment, ledger hoặc trạng thái;
- checkout tính lại trong transaction, không tin preview cũ;
- còn thiếu tiền thì không checkout;
- checkout sớm không tự hạ package đã cam kết;
- invoice đọc snapshot, không đọc bảng giá hiện hành;
- snapshot invoice đã tạo là bất biến/idempotent.

Phương trình chi tiết ở thời điểm chốt:

```text
Tổng thực tế
  = SUM(tiền phòng thực tế từng dòng
        + phụ thu khách thêm từng dòng)
  + dịch vụ đã CONFIRMED/FULFILLED
  + phụ phí vận hành đã xác nhận
  - giảm giá
  + thuế
```

Đối với một dòng có nhiều phòng cùng hạng, response/invoice giữ đồng thời
`quantity`, giá một phòng cho kỳ lưu trú và subtotal của cả dòng. Một đơn có
nhiều hạng phòng được cộng từ các dòng độc lập; không chia ngược tổng đơn để
suy ra giá từng phòng.

## 6. Migration

Thứ tự:

1. `V14__pricing_engine_v2_foundation.sql`
2. `V15__pricing_quotes_and_rate_guards.sql`
3. `V16__invoice_pricing_v2_breakdown.sql`
4. `V17__seed_pricing_v2_rate_profiles.sql`
5. `V18__version_overnight_policy_to_08.sql`
6. `V19__enforce_pricing_input_guards.sql`
7. `V20__prevent_overlapping_pricing_versions.sql`

Đã kiểm chứng trên PostgreSQL 16 Testcontainers:

- database mới từ V1 đến V20;
- database mô phỏng đã ở V13 có reservation/invoice cũ rồi nâng đến V20;
- constraint/index/backfill;
- V17 chỉ seed rate profile cho room type canonical chưa có bất kỳ profile nào,
  không ghi đè version do operator tạo;
- V18 version hóa mốc nhận khách qua đêm tới 08:00 mà không sửa snapshot cũ;
- V19 khóa dữ liệu tiền về VND nguyên và bắt buộc ít nhất một khách mỗi phòng;
- V20 preflight và chặn mọi policy/rate version có khoảng hiệu lực chồng nhau;
- policy inactive/future/expired không bị seed rate trái ý operator;
- Hibernate schema validation.

Kết quả: 18 test migration pass.

## 7. Bằng chứng regression local

- frontend production build: pass, 43 routes;
- frontend unit test: 30 test pass, gồm contract chatbot → booking V2;
- backend full suite: 418 test pass sau thay đổi chatbot;
- PostgreSQL migration profile: 18 test pass, gồm database mới, nâng cấp dữ
  liệu cũ, Hibernate validation và idempotency concurrency;
- runtime quote smoke trên PostgreSQL local: `STANDARD`, 20:00 → 08:00 được
  phân loại `OVERNIGHT`, tổng `170.000 VND`, policy V2 và bảo vệ tồn phòng
  tới `12:30` đúng turnover buffer;
- ba mốc giờ chính sách dùng JDBC 4.2 `LocalTime` theo wall-clock và khai báo
  cột `time`; cách này ngăn Hibernate dịch giờ qua timezone nhưng không thay
  đổi storage của các `LocalDateTime` reservation hiện hữu;
- backend full suite: xem kết quả regression mới nhất ở báo cáo bàn giao; không
  dùng lại số đếm cũ sau khi bổ sung test pricing;
- Pricing V2 reservation integration bao phủ online, walk-in, nhiều phòng
  cùng hạng, nhiều hạng phòng, extra guest, dịch vụ, extension, invoice và
  inventory conflict;
- pure engine có property scan theo từng phút qua toàn bộ boundary và sáu rate
  profile;
- rate/profile lookup đã kiểm chứng với `effectiveToUtc` hữu hạn và policy
  inactive;
- test scenario/legacy bảo đảm reservation cũ và workflow tài chính không đổi.

Cảnh báo Mockito dynamic-agent/JDK là technical warning tương lai, không phải
test failure.

## 8. Runbook canary production

1. Backup/branch Neon trước migration.
2. Chạy Flyway trên staging clone và đối chiếu row counts/constraints.
3. Deploy backend với V2 disabled.
4. Kiểm tra sáu `room_types.code`, policy V1 và rate profile active.
5. Xác nhận blueprint có sẵn canary list `STANDARD`; tuyệt đối không bật boolean
   V2 khi canary list rỗng.
6. Bật một mã ít rủi ro:

   ```dotenv
   PRICING_ENGINE_V2_ENABLED=true
   PRICING_ENGINE_V2_ROOM_TYPE_CODES=STANDARD
   ```

7. Smoke test quote → reservation → QR → webhook → confirm → check-in muộn
   → extension → final payment → checkout → invoice; chạy thêm walk-in dùng
   giá hệ thống và walk-in có price override được duyệt.
8. Theo dõi `PRICE_CHANGED`, quote expired, availability conflict, ledger,
   RoomHold và reconciliation.
9. Mở thêm code theo từng đợt; không xóa canary list cho đến khi UAT hoàn tất.
10. Sau khi mọi client online đã dùng quote và canary list được xóa, mới bật
   `PRICING_ENGINE_V2_REQUIRE_QUOTE=true`.

Rollback an toàn:

- đặt `PRICING_ENGINE_V2_ENABLED=false` để ngừng tạo quote/đơn V2 mới;
- không downgrade migration;
- không đổi reservation V2 đã tạo về legacy;
- reservation V2 hiện hữu vẫn tiếp tục lifecycle từ snapshot đã cam kết.

## 9. Việc không tự động hóa trong rollout

- Không bật feature flag production chỉ dựa vào unit test.
- Không repair Flyway checksum production.
- Không cập nhật/xóa rate version đã được snapshot.
- Không tự chuyển price override đã duyệt sang V2 hoặc tính lại reservation
  `LEGACY_V1`.
- Không deploy/push chỉ vì local pass; cần explicit release authorization,
  staging/UAT và kiểm tra secrets/runtime.
