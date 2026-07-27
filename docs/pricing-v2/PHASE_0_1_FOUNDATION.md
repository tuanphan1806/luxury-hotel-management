# Pricing Engine V2 — Foundation decisions

> Trạng thái: tài liệu quyết định nền. Runtime Phase 2–6 đã được triển khai;
> xem `IMPLEMENTATION_AND_ROLLOUT.md` để biết contract, bằng chứng test và cách
> bật canary. Các đoạn mô tả “Phase 1 chưa...” bên dưới được giữ như lịch sử
> phạm vi của migration nền, không còn là trạng thái implementation hiện tại.

Tài liệu này khóa các quyết định đã được dùng để xây nền schema. Phase 0–1
không thay thế `PricingService` hiện tại và không thay đổi kết quả của bất kỳ
reservation đang chạy nào.

## Quyết định đã khóa

### Inventory protection

V2 dùng `PACKAGE_ENTITLEMENT` (Policy A):

```text
inventoryProtectedUntil
  = max(plannedCheckout, packageEntitlementEnd)
  + turnoverBuffer
```

`turnoverBufferMinutes` mặc định là 30 phút và chỉ tham gia availability,
RoomHold, room assignment, extension và next booking. Thời gian dọn phòng
không được cộng vào tiền phòng, số giờ phụ thu hoặc doanh thu hóa đơn.

Với `OVERNIGHT`, `packageEntitlementEnd` là giờ checkout cứng của đêm vận
hành (12:00 theo policy V1), không chỉ là checkout đã nhập. Quy tắc này bảo
vệ lời hứa “đến muộn vẫn có thể dùng tối đa 12 giờ nhưng không quá 12:00”.

Khi staff xác nhận checkout sớm, phòng có thể được giải phóng sớm theo workflow
dọn phòng hiện hữu; việc checkout sớm không tự làm giảm mức tiền đã cam kết.

### DAILY và grace

- DAILY là chu kỳ 24 giờ lăn từ thời điểm check-in, không phải ngày lịch.
- Ngưỡng chuyển DAILY mặc định: 20 giờ.
- Grace mặc định: 15 phút.
- `24h15` vẫn là một DAILY.
- Từ `24h16`, phần dư bắt đầu một block 2 giờ đầy đủ.
- Grace luôn được trừ trước khi làm tròn block.

Quy tắc chính xác sẽ được triển khai và property-test trong Phase 2; Phase 1
chỉ version hóa tham số, chưa dùng chúng để tính tiền runtime.

### RoomType, capacity và rate profile V1

| Code | Sức chứa/phòng | Khách gồm trong giá/phòng | 2 giờ đầu | Giờ thêm | Qua đêm | 24 giờ |
|---|---:|---:|---:|---:|---:|---:|
| `STANDARD` | 2 | 1 | 70.000 | 20.000 | 170.000 | 300.000 |
| `DELUXE` | 3 | 2 | 100.000 | 25.000 | 220.000 | 400.000 |
| `EXECUTIVE` | 3 | 2 | 120.000 | 30.000 | 270.000 | 480.000 |
| `SUITE` | 4 | 2 | 150.000 | 35.000 | 350.000 | 600.000 |
| `FAMILY` | 6 | 4 | 130.000 | 30.000 | 330.000 | 550.000 |
| `PRESIDENTIAL` | 6 | 4 | 200.000 | 50.000 | 450.000 | 850.000 |

Phụ thu khách thêm mặc định là 50.000đ theo chu kỳ package. Đây là seed version
1, không phải hằng số trong pricing service. Seed chỉ tạo profile còn thiếu và
không ghi đè profile đã được quản trị.

Tại booking:

```text
quantity <= lineGuestCount <= roomType.maxGuests * quantity
reservation.guestCount = SUM(lineGuestCount)
extraGuestCount
  = max(lineGuestCount - includedGuests * quantity, 0)
```

Trước check-in, guest assignment của từng phòng vật lý vẫn phải không vượt
`roomType.maxGuests`.

## Ranh giới tài chính

- `Reservation.displayPackageSummary` chỉ để hiển thị.
- Không dùng package tổng hợp để tính cọc, payment, checkout hoặc invoice.
- Tất cả phép tính tiền phải cộng breakdown của từng
  `ReservationRoomType`.
- Mỗi dòng giữ `minimumCommittedRoomCharge` và `maxPackageReached`.
- Checkout không tự hạ giá dưới mức cam kết; ngoại lệ phải là một adjustment
  được duyệt và đi qua refund/ledger, không sửa snapshot cũ.
- `ReservationRateSnapshot` là append-only theo từng dòng.
- Invoice lấy snapshot cuối cùng phù hợp qua
  `ReservationInvoiceSnapshotService`; không tính ngược bằng bảng giá hiện tại.

## Compatibility và rollout

- Reservation cũ được migration gán `LEGACY_V1`; không backfill/recalculate.
- V2 chỉ dành cho reservation mới sau khi feature flag được bật.
- `room_types.code` là khóa nghiệp vụ `NOT NULL`, `UNIQUE` và không đổi sau
  khi loại phòng đã tham gia reservation hoặc rate profile.
- RoomType ngoài sáu mã chuẩn được backfill thành `ROOM_TYPE_<id>`.
- Rate/policy version không được sửa giá trị cũ; chỉ được đóng
  `effectiveToUtc/active` rồi tạo version mới.

Feature flags mặc định:

```dotenv
PRICING_ENGINE_V2_ENABLED=false
PRICING_ENGINE_V2_ROOM_TYPE_CODES=
```

Ở thời điểm migration nền được tạo, Phase 1 chưa thêm quote API và chưa nối
V2 vào `ReservationServiceImpl`. Các phase sau hiện đã hoàn tất ở local:

1. Phase 2: pure `PricingEngine` + boundary/property tests — hoàn tất.
2. Phase 3: quote có `quoteId`, expiry, version và hash — hoàn tất.
3. Phase 4: reservation mới sau feature flag, reprice trong transaction và
   trả `409 PRICE_CHANGED` khi quote không còn hợp lệ — hoàn tất.
4. Phase 5: extension, checkout, invoice — hoàn tất cho reservation V2.
   Walk-in dùng giá hệ thống đi V2 khi nằm trong canary; price override được
   ADMIN duyệt vẫn là ranh giới `LEGACY_V1`.
5. Phase 6: payment/refund/ledger/reporting regression — đã được chạy trong
   full backend suite; rollout production vẫn phải theo runbook canary.
