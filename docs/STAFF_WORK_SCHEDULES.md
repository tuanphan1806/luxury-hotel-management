# Lịch làm việc, điểm danh và ca thu ngân

## Mục tiêu

Module tách rõ ba lớp dữ liệu để lịch dự kiến không bị trộn với thời gian làm việc thực tế:

1. `WorkShiftTemplate`: mẫu ca (Sáng/Chiều/Tối), giờ bắt đầu/kết thúc và ngưỡng điểm danh.
2. `WorkScheduleAssignment`: lịch ADMIN phân cho một STAFF ở một ngày cụ thể.
3. `WorkShiftSession`: phiên làm việc thực tế, chỉ sinh ra khi STAFF check-in.

`CashierShift` là sổ tiền trong phiên và được liên kết một-một với
`WorkShiftSession`. Reservation, payment, refund và checkout không đổi state machine;
chỉ điều kiện STAFF được ghi nhận tiền mặt sử dụng ca thu ngân được mở từ phiên làm việc.
ADMIN vẫn xử lý nghiệp vụ theo quyền quản trị và không phải mở ca.

## Quyền và luồng chuẩn

### ADMIN

- Tạo/cập nhật/ngừng dùng mẫu ca.
- Phân công, sửa hoặc hủy lịch chưa check-in.
- Xem lịch của toàn bộ STAFF.
- Không check-in/out thay STAFF và không cần ca thu ngân để xử lý reservation.

### STAFF

- Chỉ xem lịch của chính mình.
- Check-in trong cửa sổ `scheduledStart - checkInEarlyMinutes` đến trước `scheduledEnd`.
- Check-in tạo `WorkShiftSession ACTIVE` và mở `CashierShift OPEN` trong cùng transaction.
- Check-out đóng `CashierShift`, đóng `WorkShiftSession` và hoàn thành lịch trong cùng transaction.
- Check-out trước giờ kết thúc bắt buộc nhập lý do.
- Không được mở một ca thu ngân độc lập qua mutation cũ; check-in lịch làm việc là lối vào chuẩn duy nhất.

Mọi mutation yêu cầu `Idempotency-Key`. Retry cùng key trả lại kết quả cũ, không tạo
thêm phiên làm việc hay ca thu ngân.

## Trạng thái và xử lý ngoại lệ

- Đi muộn: vẫn cho check-in; `late=true` khi vượt ngưỡng snapshot của lịch.
- Vắng mặt: lịch `SCHEDULED` đã hết giờ mà chưa có session được scheduler chuyển `ABSENT`.
- Quên check-out: sau thời gian grace, scheduler đóng ca thu ngân, chuyển session
  `AUTO_CLOSED`, dùng giờ kết thúc lịch làm thời điểm hiệu lực và lưu thời điểm xử lý trong audit.
- Scheduler lấy tối đa 100 ứng viên mỗi lượt, sau đó khóa và kiểm tra lại theo thứ tự
  `assignment -> work session -> cashier shift`. Vì vậy check-in sát ranh giới không thể bị
  một lượt quét cũ ghi đè thành `ABSENT`; lỗi ở nhánh đánh dấu vắng cũng không chặn nhánh
  tự đóng ca bị quên.
- Ca qua đêm: nếu giờ kết thúc không sau giờ bắt đầu thì kết thúc ở ngày kế tiếp.
- Không cho hai lịch không-hủy chồng nhau của cùng STAFF.
- Một STAFF chỉ có tối đa một session `ACTIVE` và một cashier shift đang mở.
- Audit điểm danh dùng category `WORKFORCE` trong phạm vi Vận hành; cấu hình mẫu ca
  và phân lịch nằm trong phạm vi Quản lý, nên bộ lọc nhật ký không trộn với Đặt phòng.
- Mẫu ca sửa sau này không đổi lịch sử vì assignment giữ snapshot tên, màu, giờ và ngưỡng.
- Ca thu ngân cũ mở thủ công trước rollout được gắn vào session khi check-in để không tạo hai ca.
- Endpoint mở ca cũ chỉ còn dành cho ADMIN để tương thích rollout/chẩn đoán khẩn cấp;
  ADMIN vẫn không bắt buộc mở ca khi xử lý reservation, payment hoặc refund.

## Bảo vệ dữ liệu PostgreSQL

Migration `V30__staff_work_schedules.sql` tạo:

- exclusion constraint chống lịch chồng nhau;
- unique partial index chống hai session `ACTIVE` của cùng nhân viên;
- unique assignment/session và session/cashier shift;
- check constraint cho state và thời gian;
- index phục vụ danh sách lịch và scheduler.

## Cấu hình

```properties
app.work-schedule.auto-checkout-grace-minutes=120
```

Giá trị được giới hạn an toàn từ 15 phút đến 12 giờ. Scheduler chạy mỗi 15 phút theo
`Asia/Ho_Chi_Minh`.

## UAT tối thiểu

1. ADMIN tạo ca qua ngày và phân cho STAFF; thử tạo lịch chồng để xác nhận bị chặn.
2. STAFF thử check-in quá sớm; đến cửa sổ hợp lệ thì check-in thành công và có đúng một cashier shift.
3. Retry check-in cùng key; xác nhận không sinh session/shift thứ hai.
4. STAFF check-out sớm không lý do bị chặn; nhập lý do thì session và cashier shift cùng đóng.
5. Tạo một ca quá hạn không check-in; chạy scheduler và xác nhận `ABSENT` + audit SYSTEM.
6. Tạo session quên check-out; chạy scheduler sau grace và xác nhận `AUTO_CLOSED` + cashier shift đóng.
7. CUSTOMER gọi API bị 403; STAFF không tạo/sửa lịch; ADMIN không gọi API điểm danh.
8. Sau check-in, chạy một thao tác thu tiền mặt reservation; sau check-out xác nhận STAFF không thể ghi nhận thêm tiền mặt.

## Rollback vận hành

Không xóa dữ liệu lịch/phiên đã phát sinh. Nếu cần tạm ngừng UI, ẩn liên kết điều hướng
nhưng giữ migration và dữ liệu. Không hạ migration V30 trên database đã có session hoặc
cashier shift liên kết; khôi phục ứng dụng bằng bản tương thích V30.
