# Chính sách vòng đời và xóa dữ liệu

Tài liệu này là ranh giới thống nhất giữa **ngừng hoạt động**, **hủy nghiệp vụ**
và **xóa vĩnh viễn**. Mục tiêu là không làm mất lịch sử đặt phòng, tiền, kiểm
toán hoặc vận hành chỉ để làm danh sách quản trị gọn hơn.

## Nguyên tắc bắt buộc

1. Dữ liệu tài chính, reservation, invoice, audit và journal là lịch sử; không
   cung cấp API xóa cứng.
2. Đối tượng catalog đã được nghiệp vụ tham chiếu phải ngừng hoạt động hoặc hủy,
   không xóa.
3. Xóa cứng chỉ dành cho bản ghi cấu hình/nội dung chưa từng được sử dụng hoặc dữ
   liệu tạm đã hết hạn.
4. Service kiểm tra phụ thuộc và khóa bản ghi trước khi xóa; foreign key của
   PostgreSQL là hàng rào cuối cùng chống race condition.
5. Xóa database phải `flush` thành công trước khi giải phóng ảnh/media bên ngoài.
6. UI phải nói rõ thao tác là vô hiệu hóa, hủy hay xóa vĩnh viễn; không dùng từ
   “xóa” cho một soft-delete.

## Ma trận hiện hành

| Đối tượng | Hành vi được phép | Hàng rào an toàn |
|---|---|---|
| Reservation, payment, refund, invoice, financial journal, audit log | Không xóa cứng | Chuyển trạng thái hoặc ghi bút toán/hành động bù theo workflow |
| Loại phòng | Ngừng hoạt động; chỉ xóa vĩnh viễn loại đang inactive và chưa từng dùng | Chặn khi có phòng, dòng đặt phòng, đánh giá hoặc báo giá; bảng giá thuộc aggregate chỉ cascade trong lần xóa hợp lệ |
| Phòng vật lý | Chỉ xóa phòng chưa từng dùng | Chặn khi có khách/assignment, lịch sử đặt phòng hoặc bảo trì; phòng đã dùng được ngừng bán/bảo trì |
| Tiện nghi | Xóa vĩnh viễn khi không còn hạng phòng nào sử dụng | Không tự gỡ liên kết; admin phải chủ động cập nhật hạng phòng trước |
| Dịch vụ thêm | Active/inactive, không có API xóa cứng | Snapshot dịch vụ đã đặt và lịch sử thu tiền được giữ nguyên |
| User | Vô hiệu hóa mềm | Giữ user và lịch sử; thu hồi session/token; có thể kích hoạt lại theo quyền ADMIN |
| Ca làm việc theo ngày | Xóa ca tương lai hoàn toàn trống; nếu đã phát sinh thì hủy | Chặn khi đã bắt đầu, có phân công hoặc yêu cầu đăng ký; ca đã chạy được hoàn tất theo workflow |
| Gallery | Xóa vĩnh viễn nội dung độc lập | Xóa + flush database trước khi release media; ADMIN-only |
| Review | Chủ sở hữu hoặc ADMIN được xóa nội dung | Kiểm tra ownership; không làm thay đổi reservation đã hoàn thành |
| Yêu cầu liên hệ | ADMIN được xóa vĩnh viễn | UI cảnh báo rõ không thể hoàn tác; không liên kết ledger/reservation |
| Quote, token, login ticket, media orphan | Cleanup dữ liệu tạm hết hạn/không còn tham chiếu | Chỉ cleanup theo điều kiện hết hạn hoặc orphan; không đụng dữ liệu đã commit |

## Quy tắc khi bổ sung đối tượng mới

Trước khi thêm `DELETE`, phải trả lời được: đối tượng đã có history hay chưa,
foreign key nào tham chiếu tới nó, có phương án inactive/cancel hay không, media
được giải phóng lúc nào, quyền nào được thao tác và audit nào được ghi. Nếu chưa
trả lời đủ, mặc định **không mở hard-delete**.
