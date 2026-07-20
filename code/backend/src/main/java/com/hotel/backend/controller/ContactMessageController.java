package com.hotel.backend.controller;

import com.hotel.backend.dto.request.ContactMessageRequest;
import com.hotel.backend.dto.request.ContactMessageReplyRequest;
import com.hotel.backend.dto.request.ContactMessageStatusRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.ContactMessageResponse;
import com.hotel.backend.service.ContactMessageService;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.security.ClientIpResolver;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.time.Duration;

import static com.hotel.backend.util.SecurityTokenHasher.sha256;

@RestController
@RequestMapping("/api/contact-messages")
@RequiredArgsConstructor
public class ContactMessageController {

    private final ContactMessageService contactMessageService;
    private final AuthRateLimitService authRateLimitService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping
    public ResponseEntity<ApiResponse<ContactMessageResponse>> create(
            @Valid @RequestBody ContactMessageRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        String emailKey = sha256(request.getEmail().trim().toLowerCase(Locale.ROOT));
        authRateLimitService.check("contact-ip:" + clientIp, 20, Duration.ofHours(1));
        authRateLimitService.check("contact:" + clientIp + ":" + emailKey, 5, Duration.ofMinutes(15));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Đã tiếp nhận yêu cầu liên hệ", contactMessageService.create(request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<List<ContactMessageResponse>> getAll() {
        return ApiResponse.success(contactMessageService.getAll());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ContactMessageResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ContactMessageStatusRequest request) {
        return ApiResponse.success("Đã cập nhật trạng thái yêu cầu liên hệ", contactMessageService.updateStatus(id, request));
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ContactMessageResponse> reply(
            @PathVariable Long id,
            @Valid @RequestBody ContactMessageReplyRequest request,
            Authentication authentication) {
        return ApiResponse.success(
                "Đã gửi email phản hồi",
                contactMessageService.reply(id, request, authentication == null ? null : authentication.getName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        contactMessageService.delete(id);
        return ApiResponse.success("Đã xóa yêu cầu liên hệ");
    }
}
