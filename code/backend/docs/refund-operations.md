# Quy trình hoàn tiền

## Nguyên tắc chung

- Kênh thanh toán online hiện hành là SePay/VietQR. Giao diện khách và nhân viên
  chỉ hiển thị tên phương thức là **QR** hoặc **chuyển khoản ngân hàng**.
- Reservation chỉ được chuyển sang trạng thái cuối sau khi mọi nghĩa vụ hoàn tiền
  liên quan đã có `PaymentRefund.status = SUCCEEDED`. Đóng modal hoặc tạo refund
  `PENDING` không được chốt reservation.
- Mọi phương thức hoàn tiền đi qua một điểm hoàn tất duy nhất trong
  `PaymentRefundService`; không endpoint nào được cập nhật thẳng reservation.
- Refund phải đúng toàn bộ `expected_amount`. Không chấp nhận hoàn thiếu, hoàn
  nhiều lần hoặc dùng một giao dịch ngân hàng cho hai refund.

## Hoàn bằng QR/chuyển khoản

1. Nếu chưa có tài khoản nhận, refund ở `AWAITING_CUSTOMER_INFO`; khách cung cấp
   đúng bốn trường: ngân hàng, số tài khoản, họ tên chủ tài khoản và mã ngân hàng
   được chọn từ danh mục.
2. Khi đã có người nhận, refund chuyển sang `REQUESTED`, có `refund_code` duy nhất
   dạng `RF` + 16 ký tự và `expected_amount`.
3. Staff/Admin chuyển đúng số tiền và ghi đúng `refund_code` trong nội dung chuyển
   khoản. UI hiển thị **Đang chờ xác nhận tự động từ ngân hàng**.
4. Webhook SePay `transferType = out` được xác thực bằng
   `Authorization: Apikey <key>`. Hệ thống chỉ hoàn tất khi nội dung chứa đúng một
   `refund_code` đang chờ và số tiền bằng chính xác `expected_amount`.
5. Event SePay được lưu bền vững và dedup theo provider event id; `referenceCode`
   và provider time UTC được giữ làm bằng chứng đối soát. Replay trả ACK nhưng
   không hoàn lần hai.
6. Giao dịch ra không khớp mã, khớp nhiều mã hoặc sai số tiền vào
   `REVIEW_REQUIRED`; không tự gán gần đúng.

`READY_FOR_MANUAL_TRANSFER` chỉ còn là alias tương thích cho dữ liệu cũ. V29
chuyển các hàng QR đang ở trạng thái đó sang `REQUESTED`.

## Fallback khi thiếu webhook

- Sau `SEPAY_REFUND_WEBHOOK_TIMEOUT_MINUTES` (mặc định 45 phút), staff/admin có
  thể xác nhận thủ công. Admin có thể mở fallback sớm bằng endpoint riêng nhưng
  thao tác mở không hoàn tất refund.
- Xác nhận thủ công bắt buộc chọn lý do. Ảnh minh chứng là tùy chọn; không có
  trường nhập mã giao dịch ngân hàng bằng tay.
- Fallback vẫn gọi đúng điểm hoàn tất chung: refund sang `SUCCEEDED`, ledger được
  cập nhật và reservation chỉ được finalize trong cùng transaction đó.

## Hoàn tiền mặt

- Chọn tiền mặt tạo refund `REQUESTED`; reservation giữ nguyên.
- Staff dùng nút **Xác nhận đã giao tiền mặt cho khách** sau khi đã giao thực tế.
- Tiền mặt không cần tài khoản nhận, mã giao dịch hoặc ảnh minh chứng.
- Chỉ thao tác xác nhận giao tiền mới chuyển refund sang `SUCCEEDED` và cho phép
  reservation đi tiếp.

## Bảo mật dữ liệu người nhận

Danh sách vận hành chỉ hiển thị ngân hàng và bốn số cuối. Thông tin đầy đủ chỉ
được tải qua endpoint staff/admin, không ghi vào URL, toast, log hoặc localStorage.

```dotenv
REFUND_DATA_ENCRYPTION_KEY=<base64-32-byte-key>
SEPAY_WEBHOOK_API_KEY=<same-value-configured-in-sepay-webhook>
SEPAY_REFUND_WEBHOOK_TIMEOUT_MINUTES=45
```

- `SEPAY_WEBHOOK_SECRET` vẫn được nhận làm alias API key để không phá cấu hình
  local cũ. Cấu hình mới nên dùng `SEPAY_WEBHOOK_API_KEY`.
- `SEPAY_WEBHOOK_HMAC_SECRET` chỉ dành cho webhook legacy khi không cấu hình API
  key; khi API key đã có, request sai API key không được hạ cấp sang HMAC.
- Không commit API key, khóa mã hóa hoặc số tài khoản thật vào Git.
- Không đổi `REFUND_DATA_ENCRYPTION_KEY` sau khi đã có dữ liệu nếu chưa có quy
  trình xoay khóa và mã hóa lại.

## URL webhook local

```text
https://<ngrok-host>/api/payments/sepay/webhook
```

Trong SePay, chọn xác thực API key và cấu hình nhận cả giao dịch tiền vào lẫn tiền
ra. Không bật bộ lọc chỉ gửi khi có mã thanh toán nếu muốn giữ được giao dịch
không khớp trong hàng đợi đối soát.
