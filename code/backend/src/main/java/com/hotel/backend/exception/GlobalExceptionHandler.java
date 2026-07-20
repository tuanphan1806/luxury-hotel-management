package com.hotel.backend.exception;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Bắt tập trung tất cả exception trong ứng dụng, trả về ErrorResponse JSON chuẩn.
 *
 * Thứ tự ưu tiên xử lý:
 *   1. ResourceNotFoundException      → 404 Not Found
 *   2. DuplicateResourceException     → 409 Conflict
 *   3. ValidationException            → 400 Bad Request  (nghiệp vụ)
 *   4. MethodArgumentNotValidException → 400 Bad Request  (@Valid annotation)
 *   5. HttpMessageNotReadableException → 400 Bad Request  (JSON parse error)
 *   6. MethodArgumentTypeMismatchException → 400 Bad Request (sai kiểu path variable)
 *   7. MissingServletRequestParameterException → 400 Bad Request (thiếu query param bắt buộc)
 *   8. MissingRequestHeaderException   → 400 Bad Request (thiếu header bắt buộc)
 *   9. Exception                       → 500 Internal Server Error (fallback)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 404 Not Found ─────────────────────────────────────────────────────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {

        log.warn("ResourceNotFoundException: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.NOT_FOUND.value())
                        .error("Not Found")
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .build());
    }

    // ── 409 Conflict ──────────────────────────────────────────────────────────

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResource(
            DuplicateResourceException ex, HttpServletRequest request) {

        log.warn("DuplicateResourceException: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.CONFLICT.value())
                        .error("Conflict")
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .build());
    }

    // ── 400 Bad Request — nghiệp vụ ───────────────────────────────────────────

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            ValidationException ex, HttpServletRequest request) {

        log.warn("ValidationException: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Bad Request")
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .build());
    }

    // ── 400 Bad Request — @Valid annotation ───────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .sorted()
                .collect(Collectors.toList());

        log.warn("Validation failed: {}", errors);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Validation Failed")
                        .message("Dữ liệu đầu vào không hợp lệ")
                        .errors(errors)
                        .path(request.getRequestURI())
                        .build());
    }

    // ── 400 Bad Request — JSON parse error ────────────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        log.warn("HttpMessageNotReadableException: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Bad Request")
                        .message("Request body không đúng định dạng JSON")
                        .path(request.getRequestURI())
                        .build());
    }

    // ── 400 Bad Request — sai kiểu path variable / query param ───────────────

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String message = String.format(
                "Tham số '%s' có giá trị '%s' không hợp lệ. Kiểu dữ liệu yêu cầu: %s",
                ex.getName(),
                ex.getValue(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown"
        );

        log.warn("MethodArgumentTypeMismatchException: {}", message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Bad Request")
                        .message(message)
                        .path(request.getRequestURI())
                        .build());
    }

    // ── 400 Bad Request — thiếu query param bắt buộc ─────────────────────────

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParams(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        String message = String.format("Thiếu tham số bắt buộc: '%s'", ex.getParameterName());

        log.warn("MissingServletRequestParameterException: {}", message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Bad Request")
                        .message(message)
                        .path(request.getRequestURI())
                        .build());
    }

    // ── 400 Bad Request — thiếu request header bắt buộc ─────────────────────

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {

        String message = String.format("Thiếu header bắt buộc: '%s'", ex.getHeaderName());

        log.warn("MissingRequestHeaderException: {}", message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Bad Request")
                        .message(message)
                        .path(request.getRequestURI())
                        .build());
    }

    /**
     * Database uniqueness constraints are part of the idempotency boundary for
     * payment/refund references. Return a conflict instead of leaking a 500
     * when two operators submit the same bank transfer reference concurrently.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity conflict at {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.CONFLICT.value())
                        .error("Conflict")
                        .message("Dữ liệu đã được cập nhật hoặc mã giao dịch đã được sử dụng")
                        .path(request.getRequestURI())
                        .build());
    }

    // Multipart bị Spring chặn trước khi request đi vào FileStorageService.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("Upload exceeds configured multipart limit at {}", request.getRequestURI());
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.builder()
                        .status(HttpStatus.PAYLOAD_TOO_LARGE.value())
                        .error("Payload Too Large")
                        .message("Ảnh vượt quá dung lượng tối đa 5 MB")
                        .path(request.getRequestURI())
                        .build());
    }

    

        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<ErrorResponse> handleAccessDenied(
                AccessDeniedException ex, HttpServletRequest request) {
            return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.builder()
                .status(403)
                .error("Forbidden")
                .message("Không có quyền truy cập")
                .path(request.getRequestURI())
                .build());
        }

        // Sai username/password khi login
        @ExceptionHandler(BadCredentialsException.class)
        public ResponseEntity<ErrorResponse> handleBadCredentials(
                BadCredentialsException ex, HttpServletRequest request) {
            return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.builder()
                .status(401)
                .error("Unauthorized")
                .message("Sai tên đăng nhập hoặc mật khẩu")
                .path(request.getRequestURI())
                .build());
        }

        // Chưa đăng nhập / JWT invalid
        @ExceptionHandler(InternalAuthenticationServiceException.class)
        public ResponseEntity<ErrorResponse> handleInternalAuthenticationServiceException(
                InternalAuthenticationServiceException ex, HttpServletRequest request) {
            return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.builder()
                .status(401)
                .error("Unauthorized")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build());
        }
        
        @ExceptionHandler(InvalidDataException.class)
        public ResponseEntity<ErrorResponse> handleInvalidData(
                InvalidDataException ex, HttpServletRequest request) {

            log.warn("InvalidDataException: {}", ex.getMessage());

            return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.builder()
                    .status(401)
                    .error("Unauthorized")
                    .message(ex.getMessage())
                    .path(request.getRequestURI())
                    .build());
        }

        @ExceptionHandler(AppException.class)
        public ResponseEntity<ErrorResponse> handleAppException(
                AppException ex, HttpServletRequest request) {
                
            ErrorCode code = ex.getErrorCode();
            log.warn("AppException: code={} message={}", code.getCode(), ex.getMessage());
                
            return ResponseEntity
                    .status(code.getHttpStatus())
                    .body(ErrorResponse.builder()
                            .status(code.getHttpStatus().value())
                            .error(code.getHttpStatus().getReasonPhrase())
                            .message(ex.getMessage())
                            .path(request.getRequestURI())
                            .build());
        }




        // ── 500 Internal Server Error — fallback ──────────────────────────────────

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorResponse> handleGeneral(
                Exception ex, HttpServletRequest request) {     
                log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);   
                return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                            .error("Internal Server Error")
                            .message("Đã có lỗi xảy ra, vui lòng thử lại sau")
                            .path(request.getRequestURI())
                            .build());
        }
}
