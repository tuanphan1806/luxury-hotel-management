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
