package com.hotel.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // ── Generic ──────────────────────────────────────────────
    UNCATEGORIZED_EXCEPTION(9999, "Lỗi hệ thống không xác định", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_REQUEST(4000, "Yêu cầu không hợp lệ", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(4004, "Không tìm thấy tài nguyên", HttpStatus.NOT_FOUND),
    DUPLICATE_RESOURCE(4009, "Tài nguyên đã tồn tại", HttpStatus.CONFLICT),
    AUTH_RATE_LIMITED(4290, "Bạn thao tác quá nhiều lần, vui lòng thử lại sau", HttpStatus.TOO_MANY_REQUESTS),

    // ── Reservation ──────────────────────────────────────────
    RESERVATION_NOT_FOUND(5001, "Không tìm thấy đặt phòng", HttpStatus.NOT_FOUND),
    RESERVATION_CODE_DUPLICATE(5002, "Mã đặt phòng đã tồn tại", HttpStatus.CONFLICT),
    RESERVATION_INVALID_DATE(5003, "Thời gian check-out phải sau thời gian check-in", HttpStatus.BAD_REQUEST),
    RESERVATION_CHECKIN_PAST(5004, "Thời gian check-in phải sau thời điểm hiện tại", HttpStatus.BAD_REQUEST),
    RESERVATION_CANNOT_CANCEL(5005, "Không thể hủy đặt phòng ở trạng thái này", HttpStatus.BAD_REQUEST),
    RESERVATION_CANNOT_CONFIRM(5006, "Không thể xác nhận đặt phòng ở trạng thái này", HttpStatus.BAD_REQUEST),
    RESERVATION_CANNOT_UPDATE(5009, "Chỉ có thể cập nhật đặt phòng ở trạng thái DRAFT", HttpStatus.BAD_REQUEST),
    RESERVATION_NOT_OWNER(5013, "Bạn không có quyền với đặt phòng này", HttpStatus.FORBIDDEN),
    // ── Room availability ─────────────────────────────────────
    ROOM_TYPE_NOT_FOUND(5010, "Không tìm thấy loại phòng", HttpStatus.NOT_FOUND),
    ROOM_NOT_AVAILABLE(5011, "Loại phòng không đủ số lượng trong khoảng ngày yêu cầu", HttpStatus.CONFLICT),
    ROOM_QUANTITY_INVALID(5012, "Số lượng phòng phải lớn hơn 0", HttpStatus.BAD_REQUEST),

    // ── RoomHold ─────────────────────────────────────────────
    ROOM_HOLD_NOT_FOUND(5020, "Không tìm thấy giữ chỗ", HttpStatus.NOT_FOUND),
    ROOM_HOLD_EXPIRED(5021, "Giữ chỗ đã hết hạn, vui lòng đặt phòng lại", HttpStatus.GONE),
    ROOM_HOLD_ALREADY_EXISTS(5022, "Loại phòng này đã có giữ chỗ đang hoạt động", HttpStatus.CONFLICT),

    // ── ReservationRoom (assign) ─────────────────────────────
    RESERVATION_ROOM_NOT_FOUND(5030, "Không tìm thấy phòng trong đặt chỗ", HttpStatus.NOT_FOUND),
    ROOM_NOT_FOUND(5031, "Không tìm thấy phòng", HttpStatus.NOT_FOUND),
    ROOM_ALREADY_ASSIGNED(5032, "Phòng đã được gán cho đặt chỗ này", HttpStatus.CONFLICT),
    ROOM_WRONG_TYPE(5033, "Phòng không thuộc loại phòng yêu cầu", HttpStatus.BAD_REQUEST),

    RESERVATION_CANNOT_CHECKIN(5007, "Chỉ có thể check-in khi đặt phòng đã CONFIRMED", HttpStatus.BAD_REQUEST),
    RESERVATION_CANNOT_CHECKOUT(5008, "Chỉ có thể check-out khi đang CHECKED_IN", HttpStatus.BAD_REQUEST),


    RESERVATION_PAYMENT_REQUIRED(5010, "Khách hàng chưa thanh toán, không thể trả phòng", HttpStatus.PAYMENT_REQUIRED),
    // ── Review ───────────────────────────────────────────────
    REVIEW_NOT_FOUND(5050, "Không tìm thấy đánh giá", HttpStatus.NOT_FOUND),
    REVIEW_NOT_OWNER(5051, "Bạn không có quyền với đánh giá này", HttpStatus.FORBIDDEN),
    REVIEW_ALREADY_EXISTS(5052, "Bạn đã đánh giá đặt phòng này rồi", HttpStatus.CONFLICT),
    REVIEW_RESERVATION_NOT_COMPLETED(5053, "Chỉ có thể đánh giá sau khi đã trả phòng", HttpStatus.BAD_REQUEST),

    // ── Guest
    GUEST_NOT_FOUND(5060, "Không tìm thấy thông tin khách", HttpStatus.NOT_FOUND),
    GUEST_PRIMARY_REQUIRED(5061, "Phòng phải có ít nhất 1 khách chính (isPrimary=true)", HttpStatus.BAD_REQUEST),
    GUEST_MULTIPLE_PRIMARY(5062, "Phòng chỉ được có 1 khách chính", HttpStatus.BAD_REQUEST),
    // ── Customer / User ──────────────────────────────────────
    CUSTOMER_NOT_FOUND(5040, "Không tìm thấy khách hàng", HttpStatus.NOT_FOUND),

    // ── Email / password recovery ────────────────────────────
    EMAIL_DELIVERY_FAILED(5070, "Không thể gửi email lúc này", HttpStatus.SERVICE_UNAVAILABLE),
    PASSWORD_RESET_TOKEN_INVALID(5071, "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn", HttpStatus.BAD_REQUEST),
    OAUTH_EXCHANGE_TICKET_INVALID(5072, "Phiên đăng nhập mạng xã hội không hợp lệ hoặc đã hết hạn", HttpStatus.BAD_REQUEST),
    OAUTH_PROFILE_TICKET_INVALID(5073, "Phiên bổ sung email không hợp lệ hoặc đã hết hạn", HttpStatus.BAD_REQUEST),
    OAUTH_EMAIL_ALREADY_IN_USE(5074, "Email đã thuộc một tài khoản khác; hãy đăng nhập bằng phương thức đã liên kết", HttpStatus.CONFLICT),

    // ── Pricing V2 ────────────────────────────────────────────
    PRICING_ENGINE_DISABLED(5080, "Bảng giá mới chưa được mở cho hạng phòng này", HttpStatus.SERVICE_UNAVAILABLE),
    PRICING_PROFILE_NOT_FOUND(5081, "Không tìm thấy phiên bản bảng giá đang hiệu lực", HttpStatus.CONFLICT),
    PRICING_QUOTE_EXPIRED(5082, "Báo giá đã hết hạn, vui lòng kiểm tra giá lại", HttpStatus.GONE),
    PRICE_CHANGED(5083, "Giá hoặc chính sách đã thay đổi, cần xác nhận báo giá mới", HttpStatus.CONFLICT),
    PRICING_QUOTE_MISMATCH(5084, "Báo giá không khớp với thông tin đặt phòng", HttpStatus.CONFLICT),

    // ── Cashier shifts / cash ledger ─────────────────────────
    CASHIER_SHIFT_REQUIRED(5090, "Cần mở ca thu ngân trước khi thao tác tiền mặt", HttpStatus.CONFLICT),
    CASHIER_SHIFT_ALREADY_OPEN(5091, "Bạn đang có một ca thu ngân chưa đóng", HttpStatus.CONFLICT),
    CASHIER_SHIFT_NOT_FOUND(5092, "Không tìm thấy ca thu ngân", HttpStatus.NOT_FOUND),
    CASHIER_SHIFT_CLOSED(5093, "Ca thu ngân đã đóng, không thể ghi nhận thêm tiền", HttpStatus.CONFLICT),
    CASHIER_SHIFT_FORBIDDEN(5094, "Bạn không có quyền thao tác ca thu ngân này", HttpStatus.FORBIDDEN),
    BUSINESS_DAY_CLOSED(5095, "Ngày nghiệp vụ đã khóa, không thể ghi nhận tài chính lùi ngày", HttpStatus.CONFLICT),
    BUSINESS_DAY_CLOSE_BLOCKED(5096, "Ngày nghiệp vụ còn ngoại lệ nên chưa thể khóa", HttpStatus.CONFLICT),
    BUSINESS_DAY_NOT_FOUND(5097, "Không tìm thấy bản khóa ngày nghiệp vụ", HttpStatus.NOT_FOUND),
    FINANCIAL_POSTING_INVALID(5098, "Nguồn tài chính không cân bằng hoặc thiếu dữ liệu canonical", HttpStatus.CONFLICT),
    BUSINESS_DAY_INVALID(5099, "Chỉ được khóa ngày nghiệp vụ đã kết thúc", HttpStatus.BAD_REQUEST),

    // ── Staff work schedules / attendance ───────────────────
    WORK_SHIFT_TEMPLATE_NOT_FOUND(5100, "Không tìm thấy mẫu ca làm việc", HttpStatus.NOT_FOUND),
    WORK_SCHEDULE_NOT_FOUND(5101, "Không tìm thấy lịch làm việc", HttpStatus.NOT_FOUND),
    WORK_SCHEDULE_OVERLAP(5102, "Nhân viên đã có ca làm việc trùng thời gian", HttpStatus.CONFLICT),
    WORK_SCHEDULE_INVALID_EMPLOYEE(5103, "Chỉ có thể phân lịch cho tài khoản STAFF đang hoạt động", HttpStatus.BAD_REQUEST),
    WORK_SCHEDULE_CANNOT_MODIFY(5104, "Không thể thay đổi ca đã bắt đầu hoặc đã kết thúc", HttpStatus.CONFLICT),
    WORK_SCHEDULE_CHECK_IN_TOO_EARLY(5105, "Chưa đến thời gian được phép check-in ca", HttpStatus.CONFLICT),
    WORK_SCHEDULE_CHECK_IN_EXPIRED(5106, "Ca làm việc đã qua thời gian check-in", HttpStatus.CONFLICT),
    WORK_SCHEDULE_ALREADY_ACTIVE(5107, "Nhân viên đang có một ca làm việc khác chưa check-out", HttpStatus.CONFLICT),
    WORK_SCHEDULE_NOT_ACTIVE(5108, "Ca làm việc chưa được check-in hoặc đã kết thúc", HttpStatus.CONFLICT),
    WORK_SCHEDULE_FORBIDDEN(5109, "Bạn không có quyền thao tác lịch làm việc này", HttpStatus.FORBIDDEN),
    WORK_SHIFT_REGISTRATION_NOT_FOUND(5110, "Không tìm thấy yêu cầu đăng ký ca", HttpStatus.NOT_FOUND),
    WORK_SHIFT_REGISTRATION_DUPLICATE(5111, "Bạn đã có lịch hoặc yêu cầu cho ca này", HttpStatus.CONFLICT),
    WORK_SHIFT_REGISTRATION_FULL(5112, "Ca này đã đủ nhân sự", HttpStatus.CONFLICT),
    WORK_SHIFT_REGISTRATION_CANNOT_MODIFY(5113, "Yêu cầu đăng ký ca đã được xử lý", HttpStatus.CONFLICT),
    WORK_SHIFT_REGISTRATION_PAST_DATE(5114, "Không thể đăng ký hoặc điều chỉnh ca đã qua", HttpStatus.BAD_REQUEST),
    WORK_SHIFT_REQUIREMENT_BELOW_ASSIGNED(5115, "Số nhân sự cần không thể thấp hơn số đã phân ca", HttpStatus.CONFLICT),
    WORK_DAILY_SHIFT_NOT_FOUND(5116, "Không tìm thấy ca làm việc trong ngày", HttpStatus.NOT_FOUND),
    WORK_DAILY_SHIFT_ALREADY_EXISTS(5117, "Ca này đã được mở trong ngày đã chọn", HttpStatus.CONFLICT),
    WORK_DAILY_SHIFT_CANNOT_CANCEL(5118, "Không thể hủy ca đã bắt đầu hoặc đã có nhân viên check-in", HttpStatus.CONFLICT),
    WORK_DAILY_SHIFT_NOT_OPEN(5119, "Ca làm việc không còn mở", HttpStatus.CONFLICT),
    WORK_DAILY_SHIFT_CANNOT_MODIFY(5120, "Chỉ có thể chỉnh sửa ca đang mở và chưa phát sinh dữ liệu không tương thích", HttpStatus.CONFLICT),
    WORK_DAILY_SHIFT_CANNOT_RESTORE(5121, "Chỉ có thể khôi phục ca đã hủy và chưa bắt đầu", HttpStatus.CONFLICT),
    WORK_DAILY_SHIFT_CANNOT_DELETE(5122, "Chỉ có thể xóa ca tương lai chưa phát sinh phân công, đăng ký hoặc chấm công", HttpStatus.CONFLICT),


    ;

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code       = code;
        this.message    = message;
        this.httpStatus = httpStatus;
    }
}
