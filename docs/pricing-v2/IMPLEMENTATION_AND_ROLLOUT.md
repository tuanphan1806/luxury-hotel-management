# Pricing Engine V2 — implementation, verification and rollout

## 1. Trạng thái và phạm vi

Pricing V2 đã hoàn thiện cho luồng đặt phòng online, walk-in dùng giá hệ
thống, check-in theo giờ đến thực tế, gia hạn, final-payment, checkout
reconciliation và invoice snapshot. Sau migration V36, cột giá legacy đã bị
loại bỏ nên runtime mới mặc định dùng bảng giá versioned cho mọi hạng phòng:

```dotenv
PRICING_ENGINE_V2_ENABLED=true
PRICING_ENGINE_V2_ROOM_TYPE_CODES=*
PRICING_ENGINE_V2_REQUIRE_QUOTE=false
PRICING_QUOTE_TTL_MINUTES=15
```

Blueprint Render hiện đã ở trạng thái full rollout đã được duyệt:

```dotenv
PRICING_ENGINE_V2_ENABLED=true
PRICING_ENGINE_V2_ROOM_TYPE_CODES=*
PRICING_ENGINE_V2_REQUIRE_QUOTE=true
```

Ký tự `*` bảo đảm loại phòng do ADMIN tạo sau này cũng đi qua Pricing V2;
không phải sửa danh sách mã bằng tay. Mỗi loại phòng mới bắt buộc có bảng giá
đang hiệu lực trước khi API tạo thành công. Nếu dựng môi trường khác từ source
default, vẫn phải chạy migration/seed, kiểm tra rate profile và UAT trước khi
bật ba biến trên.

Các luồng được giữ nguyên để tương thích:

- reservation đã tồn tại mang `LEGACY_V1`, không tính lại;
- walk-in dùng giá hệ thống đi V2 khi mã hạng phòng được Pricing V2 hỗ trợ;
  trường hợp ADMIN duyệt nhập giá tay vẫn giữ `LEGACY_V1` và audit riêng;
- production bắt buộc quote; môi trường compatibility chủ động đặt
  `PRICING_ENGINE_V2_REQUIRE_QUOTE=false` chỉ cho phép server tự báo giá từ
  rate profile hiện hành, không khôi phục công thức hay cột giá legacy;
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

Nếu giá/policy đổi, trả `409 PRICE_CHANGED`; client lấy quote mới, so sánh
`totalAmount` với quote cũ để nói rõ “số tiền đã đổi” hay “chỉ làm mới
quote/policy nhưng số tiền không đổi”, rồi bắt khách xác nhận lại. Retry cùng
request dùng cùng `Idempotency-Key`.

Quote và reservation chưa giữ phòng. RoomHold chỉ được tạo ở bước phát QR.

### Compatibility không có giá fallback

Frontend không tự tính hoặc fallback sang giá local. `5080
PRICING_ENGINE_DISABLED`, lỗi mạng, profile thiếu, quote sai hoặc server error
đều chặn đặt phòng và hiển thị lỗi rõ ràng.

Trong giai đoạn compatibility, client cũ có thể chưa gửi quote nếu gate cho
phép. Backend vẫn tính bằng policy/rate version đang hiệu lực và ghi snapshot;
web và chatbot dùng quote server-authoritative. Sau khi mọi client online khác
(nếu có) đã nâng cấp và canary hoàn tất, bật:

```dotenv
PRICING_ENGINE_V2_REQUIRE_QUOTE=true
```

Gate này chặn request bỏ quote. Không bật gate trong canary nếu còn client chưa
hỗ trợ quote; cấu hình sai sẽ làm các client đó fail loudly thay vì âm thầm
tính sai giá.

## 3. Nguồn dữ liệu và snapshot

- `stay_policy_versions`: chính sách grace, threshold, overnight, turnover.
- `room_rate_profiles`: giá theo `room_types.code`, version và hiệu lực UTC.
- `pricing_quotes` + `pricing_quote_lines`: quote có hạn dùng và hash.
- `pricing_quote_commitments`: bảo đảm một quote chỉ tạo một reservation.
- `reservation_rate_snapshots`: evidence append-only theo từng dòng phòng.
- `reservation_invoice`: lưu pricing version và breakdown normalized.

Runtime không hardcode giá. Seeder chỉ tạo policy/rate version còn thiếu và
không ghi đè lịch sử đã phát sinh.

### Quản lý bảng giá theo loại phòng

Từ migration V36, `room_types.price` đã bị xóa. Nguồn giá duy nhất của một loại
phòng là chuỗi version bất biến trong `room_rate_profiles`. Khi ADMIN tạo hoặc
sửa loại phòng, request phải gửi trọn bộ:

- số khách đã bao gồm trong giá;
- giá 2 giờ đầu;
- giá mỗi giờ thêm;
- giá qua đêm;
- giá ngày đêm/24 giờ;
- phụ thu mỗi khách thêm theo chu kỳ gói.

Các khoảng 2 giờ đầu và 1 giờ thêm do policy hệ thống cố định; ADMIN chỉ quản
lý số tiền, không tự tạo một công thức khác cho từng loại phòng. Backend kiểm
tra VND nguyên, `includedGuests <= maxGuests` và thứ tự
`firstBlockPrice <= overnightPrice <= dailyPrice`.

Tạo loại phòng và rate profile đầu tiên chạy trong cùng transaction. Khi sửa
giá, backend khóa `RoomType`, đóng version hiện hành rồi thêm version kế tiếp;
hai request cập nhật đồng thời không được tạo hai version chồng thời gian.
Reservation/quote đã cam kết tiếp tục trỏ tới version và snapshot cũ. Migration
tự ngừng hoạt động loại phòng chưa có đúng một rate profile hiệu lực; ADMIN phải
mở màn hình sửa, xác nhận đủ bảng giá rồi kích hoạt lại trước khi bán. Loại
phòng đã có lịch sử chỉ được ngừng hoạt động, còn xóa vĩnh viễn chỉ áp dụng cho
loại phòng chưa có phòng vật lý, reservation, review hoặc quote liên quan.

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
7. reprice mọi dịch vụ `REQUESTED`/`CONFIRMED` dùng
   `PER_PACKAGE_CYCLE` (cả booking-time và in-stay) bằng unit-price snapshot;
   chỉ delta của dịch vụ `CONFIRMED` được cộng vào công nợ; snapshot lịch sử
   `PER_NIGHT` được xử lý như alias tương thích.

`inventoryProtectedUntil`:

```text
HOURLY/DAILY:
  max(plannedCheckout, packageIncludedCheckout) + turnoverBuffer

OVERNIGHT:
  max(plannedCheckout, operationalNightHardCheckout) + turnoverBuffer
```

Turnover chỉ bảo vệ tồn phòng, không cộng vào doanh thu.

Request tạo đơn/báo giá/availability/walk-in được giới hạn bởi
`PRICING_MAX_STAY_DAYS` (mặc định 365 ngày, hard safety cap 1095 ngày). Mục
đích là chặn payload breakdown theo chu kỳ tăng không giới hạn; checkout thực
tế vẫn luôn được phép tính lại nên không thể bị kẹt chỉ vì khách trả muộn.

Với policy hiện tại, `operationalNightHardCheckout` là 12:00. Vì vậy khách
đến muộn vẫn có thể dùng đủ tối đa 12 giờ nhưng không quá 12:00 mà không bị
đơn kế tiếp bán chồng quyền đã hứa. Khi khách checkout sớm:

- reservation chuyển `CHECKED_OUT`;
- assignment chuyển `CHECKED_OUT`;
- phòng vật lý chuyển `AVAILABLE` và `DIRTY`;
- truy vấn availability loại ngay reservation `CHECKED_OUT`.

Do đó phần tồn phòng còn được bảo vệ trong tương lai được giải phóng ngay,
nhưng phòng chỉ sẵn sàng gán lại sau khi hoàn tất dọn phòng.

MVP checkout theo toàn bộ reservation: mọi assignment của đơn được chốt cùng
một transaction. Chưa hỗ trợ checkout/giảm giá riêng từng phòng trong cùng
đơn; nếu cần phải là một workflow và snapshot tài chính mới, không sửa ngầm
assignment hiện tại.

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
- HOURLY/DAILY checkout sớm tính lại theo thời gian thực tế;
- OVERNIGHT trước 23:00 của đêm vận hành có thể tính lại và hoàn phần thừa;
  từ 23:00 giữ giá qua đêm làm sàn, nhưng không giữ phụ thu gia hạn tương lai
  chưa sử dụng;
- mọi khoản thu thừa sau reprice phải hoàn xong mới checkout;
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
8. `V21__version_pricing_boundary_policy.sql`
9. `V22__canonicalize_add_on_package_cycle_unit.sql`

Đã kiểm chứng trên PostgreSQL 16 Testcontainers. Chuỗi Flyway hiện tại chạy
từ V1 đến V37; các mục V14–V22 và V37 dưới đây là phần trực tiếp của Pricing
V2:

- database mới từ V1 đến V37;
- database mô phỏng có reservation/invoice cũ rồi nâng đến schema mới nhất;
- constraint/index/backfill;
- V17 chỉ seed rate profile cho room type canonical chưa có bất kỳ profile nào,
  không ghi đè version do operator tạo;
- V18 version hóa mốc nhận khách qua đêm tới 08:00 mà không sửa snapshot cũ;
- V19 khóa dữ liệu tiền về VND nguyên và bắt buộc ít nhất một khách mỗi phòng;
- V20 preflight và chặn mọi policy/rate version có khoảng hiệu lực chồng nhau;
- V21 tạo policy/rate version mới cho ngưỡng qua đêm sáng sớm 120 phút và
  phần dư bắt đầu đúng biên 24 giờ; policy/rate lịch sử không bị ghi đè; cả
  rate hiện tại có ngày kết thúc và chuỗi rate hữu hạn đã lên lịch tương lai
  đều được clone sang policy mới mà không mất khoảng hiệu lực;
- V22 đổi catalog `PER_NIGHT` thành `PER_PACKAGE_CYCLE` nhưng giữ alias trong
  snapshot dịch vụ đã phát sinh;
- policy inactive/future/expired không bị seed rate trái ý operator;
- Hibernate schema validation.

Kết quả regression PostgreSQL gần nhất: `FlywayPostgresMigrationIT` 20/20
test pass, gồm migration mới, nâng cấp dữ liệu cũ, schema/constraint và kiểm
tra tính bất biến của policy. Concurrency được kiểm tra trong full backend
suite.

## 7. Bằng chứng regression local

- frontend production build: pass, 47 routes; typecheck pass;
- frontend unit test: 80 test pass, gồm contract chatbot → booking V2,
  giới hạn khoảng lưu trú và
  canonical/legacy add-on pricing unit;
- backend full unit/integration suite: 570 test pass, không
  failure/error/skip;
- PostgreSQL migration profile: 20 test pass, gồm database mới, nâng cấp dữ
  liệu cũ, migration V1–V37 và Hibernate validation;
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

## 8. Runbook triển khai một môi trường mới

1. Backup/branch Neon trước migration.
2. Chạy Flyway trên staging clone và đối chiếu row counts/constraints.
3. Deploy backend với V2 disabled.
4. Kiểm tra sáu `room_types.code`, policy V1 và rate profile active.
5. Bắt đầu canary bằng một mã ít rủi ro:

   ```dotenv
   PRICING_ENGINE_V2_ENABLED=true
   PRICING_ENGINE_V2_ROOM_TYPE_CODES=STANDARD
   ```

6. Smoke test quote → reservation → QR → webhook → confirm → check-in muộn
   → extension → final payment → checkout → invoice; chạy thêm walk-in dùng
   giá hệ thống và walk-in có price override được duyệt.
7. Theo dõi `PRICE_CHANGED`, quote expired, availability conflict, ledger,
   RoomHold và reconciliation.
8. Mở thêm code theo từng đợt. Sau khi toàn bộ mã đã qua UAT, chuyển sang
   `PRICING_ENGINE_V2_ROOM_TYPE_CODES=*` để bao phủ cả loại phòng tạo mới.
9. Sau khi mọi client online đã dùng quote, bật
   `PRICING_ENGINE_V2_REQUIRE_QUOTE=true`. Đây là trạng thái hiện tại của
   blueprint production; không quay lại compatibility nếu không có kế hoạch
   rollback rõ ràng.

Emergency sales stop (không phải rollback dữ liệu):

- đặt `PRICING_ENGINE_V2_ENABLED=false` để ngừng tạo quote/đơn mới; từ V36
  không còn đường fallback sang `room_types.price`;
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
