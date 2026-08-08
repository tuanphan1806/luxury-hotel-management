# Lịch làm việc, điểm danh và ca thu ngân

## Mục tiêu

Module tách rõ ba lớp dữ liệu để lịch dự kiến không bị trộn với thời gian làm việc thực tế:

1. `WorkShiftTemplate`: mẫu cấu hình tái sử dụng; không tự sinh ba ca cho mọi ngày.
2. `WorkShiftRequirement` (`DailyShift`): ca thực tế ADMIN mở cho một ngày, giữ snapshot
   tên, giờ, màu, định biên và chính sách nhận ca.
3. `WorkScheduleAssignment`: lịch ADMIN phân hoặc hệ thống tự phân cho một STAFF.
4. `WorkShiftSession`: phiên làm việc thực tế, chỉ sinh ra khi STAFF check-in.

`CashierShift` là sổ tiền trong phiên và được liên kết một-một với
`WorkShiftSession`. Reservation, payment, refund và checkout không đổi state machine;
chỉ điều kiện STAFF được ghi nhận tiền mặt sử dụng ca thu ngân được mở từ phiên làm việc.
ADMIN vẫn xử lý nghiệp vụ theo quyền quản trị và không phải mở ca.

## Quyền và luồng chuẩn

### ADMIN

- Tạo/cập nhật/ngừng dùng mẫu ca.
- Mở từng ca theo ngày hoặc tạo nhanh nhiều ngày/tuần/tháng sau khi xem preview.
- Chọn chính sách nhận ca: ADMIN phân công, STAFF đăng ký chờ duyệt hoặc tự nhận khi còn chỗ.
- Hủy ca trước khi bắt đầu với lý do; ca đã hủy vẫn được giữ để audit và báo cáo.
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

- DailyShift chỉ có `OPEN`, `COMPLETED`, `CANCELLED`. Không có bản ghi nghĩa là ngày đó
  không mở ca; `CANCELLED` nghĩa là từng mở nhưng đã hủy và vẫn giữ lịch sử.
- `OPEN` sau giờ kết thúc chỉ có nghĩa đang chờ kết ca. API không nhận đăng ký hoặc
  phân công mới và STAFF không thể check-in sau ranh giới kết thúc.
- Đi muộn: vẫn cho check-in; `late=true` khi vượt ngưỡng snapshot của lịch.
- Vắng mặt: lịch `SCHEDULED` đã hết giờ mà chưa có session được scheduler chuyển `ABSENT`.
- Quên check-out: sau thời gian grace, scheduler đóng ca thu ngân, chuyển session
  `AUTO_CLOSED`, dùng giờ kết thúc lịch làm thời điểm hiệu lực, để `checkOutBy` trống và
  lưu thời điểm xử lý trong audit. Trạng thái này được hiển thị khác checkout chủ động.
- DailyShift chỉ chuyển `COMPLETED` sau giờ kết thúc khi không còn assignment
  `SCHEDULED`, session `ACTIVE` hay cashier shift `OPEN/CLOSING`. Checkout cuối cùng có
  thể kích hoạt hoàn tất ngay; scheduler là cơ chế dự phòng, không đóng mù theo đồng hồ.
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

Migration `V30__staff_work_schedules.sql` tạo nền tảng phiên làm việc; migration
`V34__dynamic_daily_work_shifts.sql` nâng cấp DailyShift động và tạo:

- exclusion constraint chống lịch chồng nhau;
- unique partial index chống hai session `ACTIVE` của cùng nhân viên;
- unique assignment/session và session/cashier shift;
- check constraint cho state và thời gian;
- index phục vụ danh sách lịch và scheduler.
- unique `(shift_template_id, work_date)` để thao tác tạo nhanh/retry không sinh ca trùng;
- constraint cho trạng thái, policy, snapshot thời gian và metadata hủy/hoàn tất;
- backfill chỉ những ngày đã có phân công/yêu cầu cũ, không tự tạo ba ca cho mọi ngày.

## Cấu hình

```properties
app.work-schedule.auto-checkout-grace-minutes=120
```

Giá trị được giới hạn an toàn từ 15 phút đến 12 giờ. Mặc định 120 phút; do scheduler
chạy mỗi 15 phút theo `Asia/Ho_Chi_Minh`, thời điểm xử lý thực tế nằm trong khoảng
120–135 phút sau giờ kết thúc. `actualCheckOutUtc` vẫn là giờ kết thúc dự kiến để không
ghi nhận giả thời gian làm việc kéo dài do quên thao tác.

## UAT tối thiểu

1. ADMIN tạo đúng một ca trong ngày và tạo nhanh theo tuần; xác nhận preview, skip ca
   trùng và retry cùng `Idempotency-Key` không sinh dữ liệu đôi.
2. Chọn đủ ba policy; xác nhận `ADMIN_ONLY` không hiện đăng ký, `MANUAL_APPROVAL` tạo
   yêu cầu chờ duyệt và `AUTO_ASSIGN` phân ngay khi còn chỗ.
3. ADMIN tạo ca qua ngày và phân cho STAFF; thử tạo lịch chồng để xác nhận bị chặn.
4. STAFF thử check-in quá sớm; đến cửa sổ hợp lệ thì check-in thành công và có đúng một cashier shift.
5. Retry check-in cùng key; xác nhận không sinh session/shift thứ hai.
6. STAFF check-out sớm không lý do bị chặn; nhập lý do thì session và cashier shift cùng đóng.
7. Tạo một ca quá hạn không check-in; chạy scheduler và xác nhận `ABSENT` + audit SYSTEM.
8. Tạo session quên check-out; chạy scheduler sau grace và xác nhận `AUTO_CLOSED` + cashier shift đóng.
9. Trong khoảng OPEN chờ kết ca, thử đăng ký/phân công/check-in mới và xác nhận đều bị chặn.
10. CUSTOMER gọi API bị 403; STAFF không tạo/sửa DailyShift; ADMIN không gọi API điểm danh.
11. Sau check-in, chạy một thao tác thu tiền mặt reservation; sau check-out xác nhận STAFF không thể ghi nhận thêm tiền mặt.

## Rollback vận hành

Không xóa dữ liệu lịch/phiên đã phát sinh. Nếu cần tạm ngừng UI, ẩn liên kết điều hướng
nhưng giữ migration và dữ liệu. Không hạ migration V30 trên database đã có session hoặc
cashier shift liên kết; khôi phục ứng dụng bằng bản tương thích V30.
