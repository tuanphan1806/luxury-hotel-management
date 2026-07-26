# Dịch vụ thêm — kế hoạch triển khai tương thích

## Hai luồng nghiệp vụ

- `BOOKING_TIME`: khách hoặc nhân viên chọn dịch vụ trong lúc tạo reservation. Giá và đơn vị tính được snapshot, tổng dịch vụ được cộng vào `Reservation.totalAmount` trước khi tính cọc 50%/100%. Việc tạo `RoomHold` vẫn chỉ diễn ra khi phát hành QR đặt cọc.
- `IN_STAY`: khách đang `CHECKED_IN` gửi yêu cầu; STAFF/ADMIN có thể tạo hộ cho reservation `CONFIRMED` hoặc `CHECKED_IN`. Yêu cầu mới ở `REQUESTED`, chỉ được cộng vào công nợ khi STAFF/ADMIN chuyển sang `CONFIRMED`, và phải `FULFILLED` hoặc `CANCELLED` trước checkout.

## Quy tắc tài chính

- Không tạo payment/ledger riêng cho dịch vụ.
- Thanh toán vẫn chỉ đi qua CASH hoặc SePay và dùng FINAL_PAYMENT/đối soát checkout hiện có.
- `REQUESTED` chưa phải chi phí đã chốt.
- `CONFIRMED` là chi phí đã chốt nhưng còn việc vận hành phải hoàn thành.
- `FULFILLED` là dịch vụ đã phục vụ và được thể hiện trên hóa đơn.
- `CANCELLED` không được tính vào số phải thu.
- Checkout bị chặn nếu còn dòng `REQUESTED` hoặc `CONFIRMED`.

## Điều chỉnh so với prompt tham khảo

- Dùng tên `AddOnService` để không nhầm với `Facility`.
- Bổ sung VI/EN, mã dịch vụ ổn định, thứ tự hiển thị và hai cờ `bookingEnabled`/`inStayEnabled`.
- `AbstractEntity` hiện chỉ có `createdAt`/`updatedAt`; người thao tác được ghi qua audit trail append-only, không thêm giả định `created_by`/`updated_by`.
- Bổ sung snapshot mã, tên VI/EN, đơn vị tính, hệ số tính giá và số lượng tính tiền.
- Mutation nhạy cảm bắt buộc `Idempotency-Key`.
- Không hard-delete catalog đã được tham chiếu; chỉ deactivate/reactivate.
- Không đổi state machine Reservation, RoomHold, SePay, refund hoặc ledger.

## Ngoài phạm vi

Không quản lý tồn kho dịch vụ, SLA đặt trước, lịch giao chi tiết hoặc cổng thanh toán riêng cho dịch vụ trong đợt này.
