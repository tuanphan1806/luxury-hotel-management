# Tong hop API Backend Hotel Management

> Luu y 2026-07-16: phan reservation/payment/refund ben duoi co mot so flow
> compatibility cu. Nguon day du va hien tai cho SePay, RoomHold, allocation,
> walk-in v2, review va migration V16-V28 la
> `docs/payment-platform/consolidated-implementation-report.md`. Cu the,
> reservation `PAYMENT_PENDING` chua tao RoomHold; RoomHold chi tao khi phat
> hanh QR deposit, va frontend dashboard da dung `/api/reservations/walk-in/v2`.

Tai lieu nay duoc tong hop tu cac controller trong `code/backend/src/main/java/com/hotel/backend/controller`.

Base URL local thuong dung: `http://localhost:8080`

Quy uoc:
- Cac endpoint can dang nhap gui header `Authorization: Bearer <accessToken>`.
- Refresh token gui qua header `Authorization: Bearer <refreshToken>` khi goi `/auth/refresh-token`.
- Logout gui `Authorization: Bearer <accessToken>` va `Refresh-Token: Bearer <refreshToken>`.
- Mot so endpoint co `@PreAuthorize` yeu cau role `ADMIN` hoac `STAFF`.
- Date/time dung dinh dang ISO, vi du: `2026-07-04T14:30:00`.
- Cac command payment/refund/review/walk-in/cancel/confirm/check-in/check-out/no-show
  duoc ghi ro trong bang ben duoi bat buoc header `Idempotency-Key`.

## Auth, session va phan quyen hien tai

### STAFF/ADMIN single-session

- Login lan 1: backend tao `accessToken`, `refreshToken`, luu JTI cua access/refresh vao bang `user_tokens`.
- Login lan 2 o may khac: backend tim session cu trong `user_tokens`, blacklist access token cu, blacklist refresh token cu, xoa session cu, sau do luu session moi.
- May cu se bi da ra khi goi API tiep theo vi access token cu da bi blacklist hoac khong khop JTI dang luu.

### STAFF/ADMIN refresh token

- Goi `POST /auth/refresh-token`.
- Backend kiem tra refresh token co dung JTI dang luu trong `user_tokens` khong.
- Neu khong khop thi reject.
- Neu khop: blacklist access token cu neu con hieu luc, blacklist refresh token cu, tao access/refresh moi, cap nhat `user_tokens` voi access JTI moi va refresh JTI moi.
- Refresh token cu khong dung lai duoc.

### CUSTOMER multi-session

- Customer co the login nhieu may.
- Backend khong xoa session cu va khong luu customer session vao `user_tokens`.
- Moi refresh token van duoc rotate rieng: refresh token cu bi blacklist sau khi doi token moi.
- Logout o mot may chi blacklist token cua may do, cac may khac van dung binh thuong.

### Frontend token handling

- Frontend luu access token trong cookie `token`.
- Frontend luu refresh token trong cookie/localStorage `refreshToken`.
- Khi API tra `401` voi message `Access token expired`, frontend tu goi `/auth/refresh-token`, luu cap token moi, sau do retry request cu.
- Neu refresh that bai, frontend xoa session local va chuyen ve `/login`.

### Public khong can dang nhap

- `POST /auth/login`
- `POST /auth/register`
- `POST /auth/refresh-token`
- Cac endpoint khac duoi `/auth/**`, tru `/auth/logout`
- `POST /api/chat`
- `GET /api/room-types/**`
- `GET /api/facilities/**`
- `GET /api/galleries/**`
- `GET /api/reviews/**`
- `GET /api/rooms/available`
- `GET /api/reservations/availability`
- `POST /api/payments/create` (public o lop security, nhung service van kiem tra user so huu reservation hoac `X-Guest-Token` hop le va bat buoc `Idempotency-Key`)
- `POST /api/payments/sepay/webhook` (khong dung JWT; bat buoc `Authorization: Apikey ...` hop le)
- `GET /api/payments/result/{transactionId}`
- `GET /api/payments/vnpay/return` (legacy/history)
- `GET /api/payments/vnpay/ipn` (legacy/history)

### Can dang nhap

- `POST /auth/logout`
- `GET /api/payments/{transactionId}`
- `GET /api/payments/booking/{reservationId}`
- Confirm reservation
- Check-in/check-out
- Final payment
- Tao/sua/xoa review
- Hau het API con lai

### Chi STAFF/ADMIN

- `POST /api/payments/cash`
- `POST /api/payments/refund`
- Cac API co `@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")` duoc enforce boi `@EnableMethodSecurity`.

### Flow Online Booking

1. Khach dang nhap, hoac dat phong guest va giu `guestToken` backend tra ve.
2. `POST /api/reservations` tao reservation `PAYMENT_PENDING`, cac slot `ReservationRoom`, nhung chua tao RoomHold.
3. `POST /api/payments/create` voi `provider: "SEPAY"` (neu bo trong provider thi backend mac dinh `SEPAY`). Guest gui them `X-Guest-Token`; moi client gui `Idempotency-Key`. Backend chi tao RoomHold khi phat hanh QR `DEPOSIT`.
4. Backend tu tinh tien coc/final payment, tao payment `PENDING`, ma chuyen khoan rieng va thoi gian het han. Response tra `transactionId`, `paymentUrl` cua trang theo doi, `qrCodeUrl`, `transferContent`, thong tin tai khoan nhan, `expectedAmount`, `expiresAt`.
5. Frontend hien VietQR va poll `GET /api/payments/result/{transactionId}`. Endpoint polling public, `Cache-Control: no-store`, va cung tra lai thong tin QR de reload trang khong mat phien thanh toan.
6. Khi tien vao, SePay goi `POST /api/payments/sepay/webhook`. Backend xac minh API key, chong trung event, doi chieu tai khoan + ma chuyen khoan + so tien roi moi cap nhat `PENDING -> SUCCESS` va RoomHold `CONVERTED`.
7. Thanh toan coc thanh cong chuyen reservation sang `DRAFT`; `PATCH /api/reservations/confirm/{id}` chi confirm neu tong payment hop le >= requiredDeposit (hien la 50% total), sau do reservation `CONFIRMED`.
8. Staff `PATCH /api/reservations/check-in/{id}` gui danh sach phong + guest va `Idempotency-Key`. Mot reservation co the gom nhieu RoomType va quantity; backend bat buoc gan du tung slot dung RoomType. Mot Room vat ly chi duoc thuoc mot active stay trong khoang thoi gian chong lap.
9. `GET /api/reservations/{id}/final-payment` tra `totalAmount`, `paidAmount`, `remainingAmount = total - paid`, `fullyPaid`.
10. Neu con thieu sau check-in: tao SePay VietQR bang `/api/payments/create`, hoac staff thu tien mat bang `/api/payments/cash`.
11. `PATCH /api/reservations/check-out/{id}` chi cho checkout khi da thanh toan du va khong con khoan hoan bat buoc dang cho. Sau do reservation `CHECKED_OUT`, room ve `AVAILABLE`.

### Flow Walk-In

1. Staff/Admin tao `POST /api/reservations/walk-in/v2` kem `Idempotency-Key`, danh sach Room + guest va `paymentOption=CASH|SEPAY|UNPAID`.
2. Backend lock RoomType roi Room theo thu tu, tao reservation va gan phong, sau do chuyen `CHECKED_IN` dung mot lan; khong tao RoomHold.
3. `CASH` ghi receipt trong cung transaction; `UNPAID` khong tao payment; `SEPAY` commit stay truoc roi tao QR, QR loi khong rollback check-in.
4. `GET /api/reservations/{id}/final-payment` tra settlement hien tai; `/api/payments/create` hoac `/cash` thu phan con lai.
5. `PATCH /api/reservations/check-out/{id}` kem key chi thanh cong khi khong con balance/refund blocking.

## Authentication va Email

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| POST | `/auth/login` | Body `SignInRequest` | Dang nhap, lay access token va refresh token |
| POST | `/auth/refresh-token` | Header `Authorization` = refresh token | Lay token moi |
| POST | `/auth/logout` | Header `Authorization`, `Refresh-Token` | Dang xuat va invalidate token |
| POST | `/auth/register` | Body `UserCreationRequest` | Dang ky user moi |
| GET | `/auth/confirm-email` | Query `secretCode` | Xac nhan email, sau do redirect |
| GET | `/auth/send-email` | Query `to`, `subject`, `content` | Gui email bat ky |
| GET | `/auth/verify-email` | Query `to`, `name` | Gui email xac thuc |

## User

Base path: `/api/user`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| POST | `/api/user` | Body `UserCreationWithTypeRequest` | Tao user moi |
| PUT | `/api/user/{userId}` | Body `UserUpdateRequest` | Cap nhat user |
| PATCH | `/api/user/change-password` | Body `UserPasswordRequest` | Doi mat khau |
| DELETE | `/api/user/{userId}` | Path `userId` | Xoa user |
| GET | `/api/user/{userId}` | Path `userId` | Lay chi tiet user |
| GET | `/api/user/list` | Query `keyword?`, `sort?`, `page=0`, `size=20` | Lay danh sach user, yeu cau `ADMIN`/`STAFF` |

## Room Type

Base path: `/api/room-types`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| GET | `/api/room-types` | Query `minPrice?`, `maxPrice?` | Lay danh sach loai phong, co the loc theo khoang gia |
| GET | `/api/room-types/{id}` | Path `id` | Lay chi tiet loai phong |
| POST | `/api/room-types` | Body `RoomTypeRequest` | Tao loai phong |
| PUT | `/api/room-types/{id}` | Body `RoomTypeRequest` | Cap nhat loai phong |
| DELETE | `/api/room-types/{id}` | Path `id` | Xoa loai phong |

## Room

Base path: `/api/rooms`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| POST | `/api/rooms` | Body `RoomRequest` | Tao phong |
| PUT | `/api/rooms/{id}` | Body `RoomRequest` | Cap nhat phong |
| GET | `/api/rooms/{id}` | Path `id` | Lay chi tiet phong |
| GET | `/api/rooms` | Khong co | Lay tat ca phong |
| GET | `/api/rooms/list` | Query `keyword?`, `sort?`, `page=0`, `size=20` | Lay danh sach phong co phan trang |
| GET | `/api/rooms/search` | Query `keyword?`, `status?`, `cleaningStatus?` | Tim phong |
| GET | `/api/rooms/available-for-reservation` | Query `reservationId`, `roomTypeId?` | Lay phong trong de gan cho dat phong, yeu cau `ADMIN`/`STAFF` |
| DELETE | `/api/rooms/{id}` | Path `id` | Xoa phong |
| PATCH | `/api/rooms/{id}/status` | Query `status` | Cap nhat trang thai phong |
| PATCH | `/api/rooms/{id}/cleaning-status` | Query `cleaningStatus` | Cap nhat trang thai don dep |

## Facility

Base path: `/api/facilities`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| GET | `/api/facilities` | Query `type?`, `keyword?` | Lay danh sach tien nghi, loc theo type hoac keyword |
| GET | `/api/facilities/{id}` | Path `id` | Lay chi tiet tien nghi |
| POST | `/api/facilities` | Body `FacilityRequest` | Tao tien nghi |
| PUT | `/api/facilities/{id}` | Body `FacilityRequest` | Cap nhat tien nghi |
| DELETE | `/api/facilities/{id}` | Path `id` | Xoa tien nghi |

## Gallery

Base path: `/api/galleries`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| GET | `/api/galleries` | Query `type?`, `keyword?` | Lay danh sach anh, loc theo type hoac keyword |
| GET | `/api/galleries/{id}` | Path `id` | Lay chi tiet anh |
| POST | `/api/galleries` | Body `GalleryRequest` | Tao anh gallery |
| PUT | `/api/galleries/{id}` | Body `GalleryRequest` | Cap nhat anh gallery |
| DELETE | `/api/galleries/{id}` | Path `id` | Xoa anh gallery |

## Reservation

Base path: `/api/reservations`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| GET | `/api/reservations/availability` | Query `checkIn`, `checkOut` | Kiem tra loai phong con trong |
| POST | `/api/reservations` | Body `CreateReservationRequest` | Khach hang tao dat phong |
| POST | `/api/reservations/walk-in` | Body `CreateWalkInReservationRequest`, header `Idempotency-Key` | Legacy: staff tao reservation confirmed, yeu cau `ADMIN`/`STAFF` |
| POST | `/api/reservations/walk-in/v2` | Body `CreateWalkInCheckedInRequest`, header `Idempotency-Key` | Atomic walk-in `CASH/SEPAY/UNPAID`, gan phong va CHECKED_IN dung mot lan |
| GET | `/api/reservations/my` | Dang nhap | Lay dat phong cua user hien tai |
| GET | `/api/reservations/{id}` | Path `id` | Lay chi tiet dat phong |
| PATCH | `/api/reservations/cancel/{id}` | Body `CancelReservationRequest?`, header `Idempotency-Key` | Huy dat phong |
| PUT | `/api/reservations/{id}/refund-recipient` | Body `RefundRecipientRequest`, header `Idempotency-Key`, co the gui `X-Guest-Token` | Khach/user cung cap tai khoan nhan hoan cho khoan hoan chuyen khoan thu cong |
| GET | `/api/reservations/{id}/refund-recipient` | Path `id`, co the gui `X-Guest-Token` | Lay thong tin nguoi nhan da che so tai khoan |
| PATCH | `/api/reservations/confirm/{id}` | Path `id`, header `Idempotency-Key` | Xac nhan dat phong, yeu cau `ADMIN`/`STAFF` |
| PATCH | `/api/reservations/check-in/{id}` | Body `List<AssignRoomRequest>`, header `Idempotency-Key` | Gan du Room dung RoomType/quantity, yeu cau `ADMIN`/`STAFF` |
| PATCH | `/api/reservations/check-out/{id}` | Path `id`, header `Idempotency-Key` | Check-out, yeu cau `ADMIN`/`STAFF` |
| GET | `/api/reservations/{id}/final-payment` | Path `id` | Tinh so tien thanh toan cuoi |
| GET | `/api/reservations` | Khong co | Lay tat ca dat phong, yeu cau `ADMIN`/`STAFF` |
| PATCH | `/api/reservations/{id}` | Body `UpdateReservationRequest` | Cap nhat dat phong |

## Payment

Base path: `/api/payments`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| POST | `/api/payments/create` | Body `PaymentRequest` voi `provider: "SEPAY"`; header `Idempotency-Key`; guest gui `X-Guest-Token` | Tao/tra lai giao dich SePay VietQR `PENDING`; backend tu tinh so tien. Tra canonical UTC fields song song alias local |
| POST | `/api/payments/sepay/webhook` | Raw JSON SePay; header `Authorization: Apikey <key>` | Webhook public ve network nhung bat buoc API key hop le; ghi nhan ca `in` va `out`, xu ly idempotent theo event SePay |
| GET | `/api/payments/sepay/events/review` | Khong co | Staff/Admin lay cac receipt SePay `REVIEW_REQUIRED` chua ghep duoc payment; response `no-store` |
| GET | `/api/payments/result/{transactionId}` | UUID giao dich noi bo | Public polling cho trang ket qua; tra status, amount/expectedAmount/receivedAmount va thong tin VietQR neu la SePay |
| POST | `/api/payments/cash` | Body `PaymentRequest`, header `Idempotency-Key` | Staff/Admin ghi nhan tien mat tai quay |
| GET | `/api/payments/vnpay/return` | Query params tu VNPay | Endpoint legacy de nhan browser return cua giao dich VNPay lich su; khong dung de tao payment moi |
| GET | `/api/payments/vnpay/ipn` | Query params tu VNPay | Endpoint legacy de nhan IPN/late callback cua giao dich VNPay lich su |
| GET | `/api/payments/{transactionId}` | Path `transactionId` | Lay chi tiet giao dich |
| GET | `/api/payments/booking/{reservationId}` | Path `reservationId` | Lay cac giao dich cua mot reservation |
| POST | `/api/payments/refund` | Body `RefundRequest`, header `Idempotency-Key` | Staff/Admin tao yeu cau hoan; response co status/source legacy va canonical |
| GET | `/api/payments/refunds/pending` | Khong co | Staff/Admin lay hang doi hoan tien dang can xu ly |
| GET | `/api/payments/refunds/payout-config` | Khong co | Staff/Admin lay metadata tai khoan nguon da che; khong tra credential chuyen tien |
| GET | `/api/payments/refund/{refundId}/manual-details` | Path `refundId` | Staff/Admin lay ba thong tin nguoi nhan, noi dung hoan duy nhat va URL VietQR de chuyen khoan |
| PATCH | `/api/payments/refund/{refundId}/manual-fallback/open` | Body `{ "reason": "..." }`, header `Idempotency-Key` | Chi Admin mo fallback truoc timeout; khong hoan tat refund |
| PATCH | `/api/payments/refund/{refundId}/manual-complete` | Body `ManualRefundCompleteRequest`, header `Idempotency-Key` | Fallback sau timeout/Admin mo; bat buoc ly do, proof tuy chon |
| PATCH | `/api/payments/refund/{refundId}/reconcile` | Header `Idempotency-Key` | QueryDR chi danh cho refund VNPay lich su dang `PROCESSING` |
| PATCH | `/api/payments/refund/{refundId}/retry` | Header `Idempotency-Key` | Retry/reactivate o trang thai cho phep |

### Xac thuc webhook SePay

- Contract hien hanh dung `Authorization: Apikey <SEPAY_WEBHOOK_API_KEY>` va so sanh constant-time.
- `SEPAY_WEBHOOK_SECRET` la alias tuong thich cho API key local cu. HMAC raw-body chi la fallback legacy qua `SEPAY_WEBHOOK_HMAC_SECRET` khi khong co API key.
- Khi API key da cau hinh, header sai tra `401` va khong duoc ha cap sang HMAC. Request hop le tra `200` voi `{"success":true}`.
- Event trung `id`/reference khong ghi nhan payment hay tao refund lan hai.

### Quy tac ghi nhan SePay

- Nhan `transferType: "in"` cho payment va `transferType: "out"` cho doi soat refund; ca hai bat buoc dung merchant account va so tien duong.
- Webhook co merchant account sai bi reject truoc khi ghi provider event; reconciliation
  account sai chi bo qua va khong di vao ledger cua merchant dang cau hinh.
- Dung so tien: payment `PENDING -> SUCCESS` neu reservation van duoc phep nhan tien.
- Receipt khong tim thay payment tuong ung vao `REVIEW_REQUIRED`; khong tu dong cong tien cho reservation va cung khong tu dong tao refund. Staff/Admin xem tai `/sepay/events/review`; reconciliation co the thu ghep lai sau.
- Thieu tien: khong ghi nhan thanh toan mot phan; payment chuyen `REFUND_PENDING` va tao hoan thu cong toan bo so tien da nhan.
- Thua tien: payment thanh cong trong pham vi con phai thu va tao hoan thu cong phan thua; checkout bi chan den khi khoan hoan bat buoc hoan tat.
- Tien den muon khi payment/reservation khong con du dieu kien: khong credit reservation, chuyen `REFUND_PENDING` va tao hoan thu cong toan bo.
- Lan chuyen thu hai cho payment da `SUCCESS`, `REFUND_PENDING` hoac `REFUNDED` duoc ghi thanh giao dich bo sung rieng va dua toan bo vao hang doi hoan; khong cong them vao reservation.
- Giao dich `out` chi hoan tat refund QR `REQUESTED/PROCESSING` khi noi dung chua dung mot `refund_code` va `transferAmount == expected_amount`; sai/khong khop vao `REVIEW_REQUIRED`.

### Reconciliation SePay

- Scheduler doc ca giao dich tien vao va tien ra trong khoang lookback cau hinh, co the gioi han dung tai khoan bang `SEPAY_API_BANK_ACCOUNT_ID`, moi trang toi da 100 ban ghi, tiep tuc theo `meta.pagination.has_more` den `sepay.reconciliation-max-pages` (backend gioi han 1-100 trang).
- Moi event xu ly idempotent theo thu tu provider event ID -> merchant + provider transaction ID -> fingerprint; scoped bank reference la alias compatibility cuoi. Event `REVIEW_REQUIRED` duoc phep thu ghep lai, con event da `PROCESSED`/`IGNORED` khong xu ly lai.

### Hoan tien theo provider goc

- `SEPAY`: he thong khong phat lenh chuyen tien; Staff/Admin quet QR trong app ngan hang. SePay tu dong xac nhan giao dich tien ra qua webhook/reconciliation.
- Khach chi nhap ba thong tin nhin thay: ngan hang, so tai khoan, ho ten chu tai khoan. `bankCode` duoc suy ra tu lua chon ngan hang; du lieu nhay cam duoc ma hoa trong DB va API khach chi doc ban da che.
- Staff/Admin lay `manual-details`, quet VietQR va ghi dung `refund_code`. UI cho SePay tu dong cap nhat `SUCCEEDED`; `manual-complete` chi la fallback sau timeout/Admin mo, bat buoc ly do va proof tuy chon.
- `VNPAY`: chi giu cho giao dich/refund lich su. Neu du du lieu giao dich goc va `VNPAY_REFUND_ENABLED=true`, refund di theo `VNPAY_ORIGINAL`; giao dich cu thieu `providerCreateDate` phai vao `MANUAL_REVIEW`. `reconcile`/`retry` chi ap dung kenh VNPay. Khong cho tao payment VNPay moi qua `/api/payments/create`.
- Reservation co ca giao dich VNPay lich su va SePay co the co `refundRoute: "MIXED"`; moi phan tien van theo dung kenh cua giao dich goc.

## Review

Base path: `/api/reviews`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| GET | `/api/reviews/room-type/{roomTypeId}` | Path `roomTypeId` | Lay review theo loai phong |
| GET | `/api/reviews/room-type/rating/{roomTypeId}` | Path `roomTypeId` | Lay diem trung binh va so review cua loai phong |
| POST | `/api/reviews` | Body `CreateReviewRequest` | Tao review cua user hien tai |
| GET | `/api/reviews/my` | Dang nhap | Lay review cua user hien tai |
| PATCH | `/api/reviews/{id}` | Body `UpdateReviewRequest` | Cap nhat review cua user hien tai |
| DELETE | `/api/reviews/{id}` | Path `id` | Xoa review cua user hien tai hoac admin |

## Guest

Base path: `/api/guests`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| GET | `/api/guests/reservation-room/{reservationRoomId}` | Path `reservationRoomId` | Lay danh sach khach trong mot reservation room |
| GET | `/api/guests/reservation/{reservationId}` | Path `reservationId` | Lay tat ca khach cua mot reservation |

## File

Base path: `/files`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| POST | `/files/upload` | Multipart `file`, `folder`; Staff/Admin cho `REFUND_PROOFS` | Upload anh; folder refund proof nhan them PDF da validate |

## Chat

Base path: `/api/chat`

| Method | Endpoint | Body / Params | Mo ta |
| --- | --- | --- | --- |
| POST | `/api/chat` | Body `ChatRequest` | Gui cau hoi cho chatbot khach san |

## Request body tham khao

### SignInRequest

```json
{
  "username": "string",
  "password": "string"
}
```

### UserCreationRequest

```json
{
  "fullName": "string",
  "username": "string",
  "email": "user@example.com",
  "phone": "string",
  "address": "string",
  "imageUrl": "string",
  "password": "string"
}
```

### UserCreationWithTypeRequest

```json
{
  "fullName": "string",
  "username": "string",
  "email": "user@example.com",
  "type": "CUSTOMER",
  "phone": "string",
  "address": "string",
  "imageUrl": "string",
  "password": "string"
}
```

### UserUpdateRequest

```json
{
  "fullName": "string",
  "username": "string",
  "email": "user@example.com",
  "type": "CUSTOMER",
  "phone": "string",
  "address": "string",
  "imageUrl": "string"
}
```

### UserPasswordRequest

```json
{
  "id": 1,
  "password": "string",
  "confirmPassword": "string"
}
```

### RoomTypeRequest

```json
{
  "typeName": "Deluxe",
  "description": "string",
  "price": 1200000.00,
  "imageUrl": "string",
  "facilityIds": [1, 2, 3]
}
```

### RoomRequest

```json
{
  "roomName": "101",
  "roomTypeId": 1,
  "floor": 1,
  "description": "string"
}
```

### FacilityRequest

```json
{
  "facilityName": "Wifi",
  "type": "ROOM",
  "description": "string",
  "imageUrl": "string"
}
```

### GalleryRequest

```json
{
  "title": "Lobby",
  "type": "HOTEL",
  "imageUrl": "string"
}
```

### CreateReservationRequest

```json
{
  "checkIn": "2026-07-04T14:00:00",
  "checkOut": "2026-07-05T12:00:00",
  "guestCount": 2,
  "note": "string",
  "roomTypes": [
    {
      "roomTypeId": 1,
      "quantity": 1
    }
  ]
}
```

### UpdateReservationRequest

```json
{
  "guestCount": 2,
  "note": "string"
}
```

### CancelReservationRequest

```json
{
  "cancellationReason": "string"
}
```

### AssignRoomRequest

```json
{
  "reservationRoomId": 1,
  "roomId": 101,
  "guests": [
    {
      "fullName": "Nguyen Van A",
      "phone": "string",
      "email": "user@example.com",
      "idCardNumber": "string",
      "idCardType": "CCCD",
      "dateOfBirth": "1990-01-01",
      "nationality": "Vietnam",
      "isPrimary": true
    }
  ]
}
```

### PaymentRequest

```json
{
  "bookingId": 1,
  "provider": "SEPAY",
  "purpose": "DEPOSIT",
  "orderInfo": "Thanh toan dat phong"
}
```

Ghi chu:
- Frontend khong tu quyet dinh `amount`, tai khoan nhan hoac noi dung chuyen khoan; backend tu tinh tien coc 50% khi reservation `PAYMENT_PENDING`, hoac tinh `remainingAmount` khi thanh toan cuoi.
- Thanh toan online moi chi dung `provider: "SEPAY"`; neu bo trong provider backend cung mac dinh SePay. `provider: "VNPAY"` bi tu choi khi tao payment moi.
- `bankCode` trong request cu khong duoc tin de chon tai khoan SePay; tai khoan merchant lay tu cau hinh server.
- Tien mat dung `POST /api/payments/cash` va chi hop le khi reservation dang `CHECKED_IN`.
- Response SePay co `transactionId`, `paymentUrl`, `qrCodeUrl`, `transferContent`, `bankAccountNumber`, `bankCode`, `bankName`, `accountHolder`, `expectedAmount`, `expiresAt`.
- Frontend poll `GET /api/payments/result/{transactionId}` cho den khi status khong con `PENDING`.

### RefundRequest

```json
{
  "transactionId": "string",
  "amount": 100000,
  "reason": "string"
}
```

### RefundRecipientRequest

```json
{
  "bankCode": "ICB",
  "bankName": "VietinBank",
  "accountNumber": "012345678901",
  "accountHolderName": "NGUYEN VAN A"
}
```

UI chi hien ba truong. `bankCode` la metadata noi bo cua muc ngan hang da chon.

### ManualRefundDetailsResponse

```json
{
  "refundId": "refund-uuid",
  "amount": 100000,
  "bankCode": "ICB",
  "bankName": "VietinBank",
  "accountNumber": "012345678901",
  "accountHolderName": "NGUYEN VAN A",
  "transferContent": "SEVQR HOAN RF...",
  "refundQrCodeUrl": "https://vietqr.app/img?..."
}
```

### ManualRefundCompleteRequest

```json
{
  "recipientId": "recipient-uuid",
  "recipientVersion": 0,
  "transferredAt": "2026-07-15T10:30:00",
  "fallbackReason": "Da chuyen nhung khong nhan duoc webhook SePay",
  "proofAssetId": 123
}
```

`proofAssetId` la tuy chon; form khong co truong nhap ma giao dich ngan hang.

### CreateReviewRequest

```json
{
  "reservationId": 1,
  "roomTypeId": 1,
  "rating": 5,
  "comment": "string"
}
```

### UpdateReviewRequest

```json
{
  "rating": 5,
  "comment": "string"
}
```

### ChatRequest

```json
{
  "question": "Khach san con phong khong?"
}
```

## Enum tham khao

| Enum | Gia tri |
| --- | --- |
| `UserType` | `ADMIN`, `STAFF`, `CUSTOMER` |
| `RoomStatus` | `AVAILABLE`, `BOOKED`, `CHECKED_IN`, `MAINTENANCE` |
| `CleaningStatus` | `CLEAN`, `DIRTY`, `IN_PROGRESS` |
| `ReservationStatus` | `PAYMENT_PENDING`, `DRAFT`, `CONFIRMED`, `CANCELLATION_PENDING`, `CANCELLED`, `CHECKED_IN`, `CHECKED_OUT`, `NO_SHOW` |
| `PaymentProvider` | `SEPAY`, `VNPAY`, `CASH` |
| `PaymentMethod` | `CASH`, `BANKING`, `VNPAY`, `MOMO` (enum legacy, khong dung de chon provider SePay) |
| `PaymentStatus` | `PENDING`, `SUCCESS`, `FAILED`, `CANCELLED`, `REFUNDED`, `REFUND_PENDING` |
| `RefundChannel` | `VNPAY_ORIGINAL`, `MANUAL_BANK_TRANSFER`, `CASH_AT_COUNTER` |
| `RefundRoute` | `NONE`, `VNPAY_ORIGINAL`, `MANUAL_BANK_TRANSFER`, `MIXED` |
| `RefundStatus` | `AWAITING_CUSTOMER_INFO`, `READY_FOR_MANUAL_TRANSFER`, `REQUESTED`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `MANUAL_REVIEW` |
| `IdCardType` | `CMND`, `CCCD`, `PASSPORT` |
| `UploadFolder` | `AVATAR`, `FACILITIES`, `GALLERY`, `ROOM_TYPES`, `ROOMS` |
