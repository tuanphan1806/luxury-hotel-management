# Refactor Findings

Ngày cập nhật: 2026-07-23
Repository: `C:\Users\admin\Downloads\hotelmanagement-new`
Commit được audit: `476f9c656c86af234706acbfff116530e0d5b7e3`

## Mục đích

File này chỉ ghi nhận các điểm logic, workflow hoặc rủi ro kỹ thuật được phát hiện trong quá trình refactor. Những nội dung chưa được chủ dự án cho phép thay đổi không được sửa trong code.

## 1. Phát hiện đã được phép sửa

### CHAT-01 — Nhận diện từ khóa tiếng Việt sai biên từ

- Hiện tượng: bộ nhận diện có thể coi từ `giải` là từ khóa `giá`, dẫn tới chatbot chọn nhầm intent.
- Phạm vi: chỉ thuộc chatbot, không tham gia Reservation, RoomHold, payment, refund, ledger, check-in hoặc checkout.
- Xử lý: thay kiểm tra chuỗi con bằng policy kiểm tra biên từ và thêm characterization test.
- Trạng thái: **RESOLVED** theo quyền mở rộng riêng mà chủ dự án đã cho phép đối với chatbot và lỗi nhỏ không ảnh hưởng workflow cốt lõi.

## 2. Phát hiện không tự ý sửa

### FINDING-01 — Các orchestrator tài chính vẫn lớn

- `ReservationServiceImpl`, `PaymentRefundService` và `SePayService` vẫn chứa các state transition và transaction boundary nhạy cảm.
- Có thể tiếp tục tách sâu hơn bằng coordinator/strategy, nhưng việc đó làm tăng rủi ro thay đổi thứ tự lock, save, flush, audit, event hoặc ledger.
- Quyết định hiện tại: chỉ extract mapper, calculator, authenticator, identity helper, access policy và các port hẹp. Không di chuyển điểm chốt trạng thái tài chính trong đợt này.
- Trạng thái: **DEFERRED BY SAFETY**, không phải bug đã xác nhận.

### FINDING-02 — Đã xử lý: mã tương thích VNPay

- Ngày 24/07/2026, runtime, API, cấu hình và test VNPay đã được gỡ theo quyết định chỉ dùng SePay/CASH.
- Migration PostgreSQL V11 dùng preflight và từ chối tự đổi nhãn dữ liệu tài chính VNPay thành SePay.
- Quyết định hiện tại: không xóa hoặc đổi hành vi khi chưa có một task migration/deprecation riêng.
- Trạng thái: **REQUIRES BUSINESS DECISION**.

### FINDING-03 — Cảnh báo tương thích công cụ build tương lai

- Mockito đang tự attach Java agent; cơ chế này có thể bị chặn trong một bản JDK tương lai.
- `S3UploadStorage` dùng API đã deprecated.
- Đây là cảnh báo bảo trì, không làm fail build hiện tại và không phải lỗi workflow.
- Trạng thái: **TECHNICAL DEBT**, nên xử lý trong task nâng cấp dependency riêng.

## 3. Điểm phát sinh khi viết test nhưng không phải lỗi sản phẩm

- Một fixture test ban đầu dùng tên enum không tồn tại `RESERVATION_CANCELLATION`; fixture đã được sửa về enum hiện hữu `ACCEPTED_ALLOCATION` trước khi tiếp tục.
- Không có code production hoặc workflow nào được thay đổi vì lỗi fixture này.

## 4. Kết luận về logic/workflow

- Ngoài lỗi nhận diện chatbot đã được phép sửa, chưa xác nhận thêm bug nghiệp vụ nào trong phạm vi refactor.
- Không đổi endpoint, DTO, database schema, quyền truy cập, state transition, transaction boundary hoặc thứ tự side effect.
- Các nghi vấn ngoài phạm vi phải được audit và duyệt riêng trước khi sửa.
