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

Với `OVERNIGHT`, `packageEntitlementEnd` không vượt quá giờ checkout cứng của
đêm vận hành. Policy hiện tại dùng 10:00; policy V1 lịch sử dùng 12:00. Quy
tắc này bảo vệ tồn phòng theo đúng quyền lưu trú đã báo giá mà không viết lại
snapshot của các đơn cũ.

Khi staff xác nhận checkout sớm, phòng được giải phóng theo workflow dọn phòng
hiện hữu và tiền được replay từ `actualCheckIn` đến `actualCheckout`. HOURLY và
DAILY có thể giảm theo thời gian thực tế. Với OVERNIGHT, checkout trước mốc
`overnightRefundLockTime` (mặc định 23:00 của đêm vận hành) vẫn được tính lại;
từ mốc đó trở đi giá qua đêm đã cam kết là sàn không hoàn.

### Độ chính xác thời gian

Mọi thời lượng tính tiền đều dùng phút trần:

```text
minutes = ceil(Duration(checkIn, checkOut) / 1 minute)
```

Chỉ cần còn giây hoặc nano lẻ thì tăng thêm một phút. Không dùng
`Duration.toHours()` và không bỏ phần giây trong phụ thu trước/sau gói.

### HOURLY

```text
extraMinutes = max(minutes - firstBlockMinutes - graceMinutes, 0)
extraUnits = ceil(extraMinutes / extraUnitMinutes)
rawRoomCharge = firstBlockPrice + extraUnits * extraUnitPrice
roomCharge = min(rawRoomCharge, dailyPrice)
```

Trần `dailyPrice` chỉ áp dụng cho tiền phòng. Phụ thu khách thêm và dịch vụ
không bị nuốt vào trần này.

### OVERNIGHT

- Khung vận hành mặc định: `20:00 → 08:00`.
- Quyền lưu trú cơ sở tối đa 12 giờ nhưng không bao giờ vượt quá 10:00 hôm
  sau. Ví dụ nhận 20:00 có entitlement đến 08:00, nhận 21:00 đến 09:00,
  nhận từ 22:00 trở đi bị chặn tại 10:00.
- Trả trước 23:00 của đêm vận hành: tính lại theo thời gian thực tế và có thể
  chuyển về HOURLY.
- Từ 23:00 trở đi: giữ giá OVERNIGHT làm sàn; phần gia hạn tương lai chưa dùng
  vẫn được loại khỏi nghĩa vụ.
- Khách nhận từ `00:00` đến trước `05:00` vào gói OVERNIGHT ngay, không phụ
  thuộc thời lượng đã nhập, và entitlement bị chặn tại 10:00 cùng ngày.
- Từ đúng `05:00`, cửa sổ sáng sớm không còn áp dụng; engine phân loại theo
  HOURLY/DAILY hoặc kỳ lưu trú thực sự đi qua đêm như bình thường.
- Phút đến trước 20:00 và phút trả sau entitlement được trừ grace riêng, làm
  tròn lên block giờ rồi cộng `extraUnitPrice`.
- Nếu tiền phòng qua đêm cộng block đạt giá DAILY thì tiền phòng chuyển lên
  DAILY; các khoản ngoài tiền phòng vẫn tính riêng.

Ví dụ `STANDARD`, nhận 22:00:

| Trả phòng | Tiền phòng |
|---|---:|
| 10:15 | 170.000 |
| 10:16 | 190.000 |
| 11:15 | 190.000 |
| 11:16 | 210.000 |
| 12:00 | 210.000 |

### DAILY, grace và phần dư

- DAILY là chu kỳ 24 giờ lăn từ thời điểm check-in, không phải ngày lịch.
- Ngưỡng chuyển DAILY mặc định: 20 giờ.
- Grace mặc định: 15 phút.
- `24h15` vẫn là một DAILY.
- Từ `24h16`, chu kỳ dư bắt đầu tại đúng mốc `checkIn + 24h`; không dịch điểm
  bắt đầu sang `+24h15`.
- Chu kỳ dư được phân loại lại độc lập theo HOURLY/OVERNIGHT/DAILY và có
  entitlement riêng.

Ví dụ `STANDARD`: `24h16 = 300.000 + 70.000 = 370.000`.

### Phụ thu khách thêm và dịch vụ theo chu kỳ

```text
extraGuestCount
  = max(lineGuestCount - includedGuests * roomQuantity, 0)

extraGuestCharge
  = extraGuestCount * extraGuestPrice * pricingCycleCount
```

`pricingCycleCount` là số cycle thật trong breakdown, không phải số ngày lịch.
Dịch vụ cần nhân theo cùng số cycle dùng đơn vị canonical
`PER_PACKAGE_CYCLE`. `PER_NIGHT` chỉ còn là alias đọc snapshot lịch sử, không
được dùng khi tạo/cập nhật catalog mới.

Trong MVP hiện tại, dịch vụ in-stay `PER_PACKAGE_CYCLE` dùng số chu kỳ của
toàn kỳ lưu trú đã cam kết (không tự phân loại lại bằng phép chia 24 giờ).
Khi gia hạn, order còn `REQUESTED` hoặc `CONFIRMED` được cập nhật multiplier
từ cùng pricing engine; order `FULFILLED` không bị mở lại. Nếu sau này cần
tính riêng từ đúng thời điểm bắt đầu sử dụng dịch vụ, order phải bổ sung
`serviceStartsAt` và snapshot quy tắc đó trước, không suy diễn từ
`requestedAt`.

Các đơn vị theo **đêm lịch** (`PER_OCCUPIED_NIGHT`,
`PER_GUEST_PER_OCCUPIED_NIGHT`) và phạm vi đích (`ROOM`, `GUEST`,
`ROOM_TYPE_LINE`) chưa được giả lập bằng `PER_PACKAGE_CYCLE`. Chỉ bổ sung khi
order lưu được đúng đối tượng nhận dịch vụ và snapshot được quy tắc đếm đêm;
trước đó catalog chỉ được chọn các đơn vị hệ thống thực sự tính đúng.

### RoomType, số khách phù hợp và sức chứa tối đa

| Code | Sức chứa tối đa/phòng | Khách phù hợp, gồm trong giá/phòng | 2 giờ đầu | Giờ thêm | Qua đêm | 24 giờ |
|---|---:|---:|---:|---:|---:|---:|
| `STANDARD` | 3 | 2 | 70.000 | 20.000 | 170.000 | 300.000 |
| `DELUXE` | 4 | 3 | 100.000 | 25.000 | 220.000 | 400.000 |
| `EXECUTIVE` | 4 | 3 | 120.000 | 30.000 | 270.000 | 480.000 |
| `SUITE` | 5 | 4 | 150.000 | 35.000 | 350.000 | 600.000 |
| `FAMILY` | 7 | 6 | 130.000 | 30.000 | 330.000 | 550.000 |
| `PRESIDENTIAL` | 7 | 6 | 200.000 | 50.000 | 450.000 | 850.000 |

Phụ thu khách thêm mặc định là 50.000đ theo chu kỳ package. Đây là seed version
1, không phải hằng số trong pricing service. Seed chỉ tạo profile còn thiếu và
không ghi đè profile đã được quản trị.

`includedGuests` là số khách phù hợp được công bố trên card public và đã nằm
trong giá phòng. `maxGuests` là giới hạn cứng sau khi tính cả khách phụ thu.
Hai giá trị được quản trị riêng cho từng loại phòng; hệ thống chỉ bắt buộc
`1 <= includedGuests <= maxGuests`, không áp dụng công thức tăng cố định cho
loại phòng mới hay lần chỉnh sửa sau này.

Tại booking:

```text
quantity <= lineGuestCount <= roomType.maxGuests * quantity
reservation.guestCount = SUM(lineGuestCount)
extraGuestCount
  = max(lineGuestCount - includedGuests * quantity, 0)
```

Với đơn có nhiều phòng hoặc nhiều hạng, hạn mức đã gồm giá được cộng theo
từng dòng `ReservationRoomType` vì phòng vật lý chưa được gán ở bước booking.
Phân bổ tự động chạy hai lượt: dùng hết toàn bộ suất đã gồm giá trước, sau đó
mới dùng suất phụ thu theo `extraGuestPrice` tăng dần. Đây chỉ là đề xuất mặc
định; khách/nhân viên vẫn được đổi `lineGuestCount` của từng hạng trong giới
hạn và backend báo giá lại đúng lựa chọn đó. Hệ thống không tự bỏ hạng phòng,
không đổi số lượng phòng và không ghi đè phân bổ đã nhập rõ ràng.

Trước check-in, guest assignment của từng phòng vật lý vẫn phải không vượt
`maxGuests` đã snapshot khi đơn được cam kết. Quản trị không được giảm sức
chứa nếu còn đơn hoạt động vượt giới hạn mới; vì vậy một chỉnh sửa catalog sau
đặt phòng không thể làm đơn hợp lệ bị từ chối tại check-in.

## Ranh giới tài chính

- `Reservation.displayPackageSummary` chỉ để hiển thị.
- Không dùng package tổng hợp để tính cọc, payment, checkout hoặc invoice.
- Tất cả phép tính tiền phải cộng breakdown của từng
  `ReservationRoomType`.
- Mỗi dòng giữ `minimumCommittedRoomCharge` và `maxPackageReached` làm bằng
  chứng cam kết; `minimumCommittedRoomCharge` chỉ là sàn khi policy thực sự yêu
  cầu, hiện tại là OVERNIGHT từ 23:00.
- Checkout sớm tự động tính lại bằng engine từ thời gian thực tế. Chênh lệch âm
  được ghi snapshot `ADJUSTMENT/CHECKOUT`, cập nhật nghĩa vụ và phải đi qua
  refund/ledger trước khi checkout; không sửa snapshot cũ.
- `ReservationRateSnapshot` là append-only theo từng dòng.
- Invoice lấy snapshot cuối cùng phù hợp qua
  `ReservationInvoiceSnapshotService`; không tính ngược bằng bảng giá hiện tại.
- Số tiền đã thu dùng `SUM(payment.acceptedAmount)`, không dùng tiền nhận thô.
  Chỉ refund thuộc accepted allocation ở trạng thái đang giữ nghĩa vụ hoặc đã
  hoàn tất mới được trừ; refund của tiền thừa/chưa chấp nhận không bị trừ hai
  lần.
- Công thức settlement:

  ```text
  netPaid = acceptedPaymentAllocation - acceptedAllocationRefunds
  outstanding = max(finalTotal - netPaid, 0)
  ```

- `discount` và `tax` chưa có policy cấu hình độc lập trong MVP nên mặc định
  bằng 0. Khi bổ sung, phải snapshot rõ eligibility/base và giữ thứ tự:
  `subtotal - discount + tax`; không suy diễn từ bảng giá hiện hành.

## Compatibility và rollout

- Reservation cũ được migration gán `LEGACY_V1`; không backfill/recalculate.
- V2 chỉ dành cho reservation mới sau khi feature flag được bật.
- `room_types.code` là khóa nghiệp vụ `NOT NULL`, `UNIQUE` và không đổi sau
  khi loại phòng đã tham gia reservation hoặc rate profile.
- RoomType ngoài sáu mã chuẩn được backfill thành `ROOM_TYPE_<id>`.
- Rate/policy version không được sửa giá trị cũ; chỉ được đóng
  `effectiveToUtc/active` rồi tạo version mới.
- Policy trước V21 giữ `earlyMorningOvernightMinimumMinutes=0` và
  `remainderCycleStartsAtBoundary=false` để tái hiện quote lịch sử. V21 dùng
  `120/true`; policy active từ V38 dùng `0/true`, cutoff sáng sớm 05:00 và
  hard checkout 10:00. Mỗi quote/reservation vẫn giữ policy version đã chốt.

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
