package com.hotel.backend.service;

import com.hotel.backend.dto.request.ContactMessageRequest;
import com.hotel.backend.dto.request.ContactMessageReplyRequest;
import com.hotel.backend.dto.request.ContactMessageStatusRequest;
import com.hotel.backend.dto.response.ContactMessageResponse;
import com.hotel.backend.entity.ContactMessage;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.ContactMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ContactMessageService {

    private final ContactMessageRepository contactMessageRepository;
    private final EmailService emailService;

    @Value("${app.hotel-name:Luxury Hotel}")
    private String hotelName = "Luxury Hotel";

    @Transactional
    public ContactMessageResponse create(ContactMessageRequest request) {
        ContactMessage saved = contactMessageRepository.save(ContactMessage.builder()
                .name(request.getName().trim())
                .email(request.getEmail().trim().toLowerCase())
                .phone(normalizeOptional(request.getPhone()))
                .subject(request.getSubject().trim())
                .message(request.getMessage().trim())
                .build());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ContactMessageResponse> getAll() {
        return contactMessageRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ContactMessageResponse updateStatus(Long id, ContactMessageStatusRequest request) {
        ContactMessage message = getById(id);
        message.setStatus(request.getStatus());
        return toResponse(contactMessageRepository.saveAndFlush(message));
    }

    @Transactional
    public ContactMessageResponse reply(Long id, ContactMessageReplyRequest request, String repliedBy) {
        ContactMessage message = getById(id);
        String subject = request.getSubject().trim();
        String replyMessage = request.getMessage().trim();
        String content = """
                Xin chào %s,

                %s

                Trân trọng,
                %s
                """.formatted(message.getName(), replyMessage, hotelName);

        // Nếu gửi thất bại EmailService sẽ ném lỗi; trạng thái và lịch sử không bị cập nhật giả.
        emailService.send(message.getEmail(), subject, content);

        message.setReplySubject(subject);
        message.setReplyMessage(replyMessage);
        message.setRepliedAt(LocalDateTime.now());
        message.setRepliedBy(repliedBy == null || repliedBy.isBlank() ? "staff" : repliedBy);
        message.setStatus(com.hotel.backend.constant.ContactMessageStatus.RESOLVED);
        return toResponse(contactMessageRepository.saveAndFlush(message));
    }

    @Transactional
    public void delete(Long id) {
        ContactMessage message = getById(id);
        contactMessageRepository.delete(message);
    }

    private ContactMessage getById(Long id) {
        return contactMessageRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu liên hệ"));
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ContactMessageResponse toResponse(ContactMessage message) {
        return ContactMessageResponse.builder()
                .id(message.getId())
                .name(message.getName())
                .email(message.getEmail())
                .phone(message.getPhone())
                .subject(message.getSubject())
                .message(message.getMessage())
                .status(message.getStatus())
                .replySubject(message.getReplySubject())
                .replyMessage(message.getReplyMessage())
                .repliedAt(message.getRepliedAt())
                .repliedBy(message.getRepliedBy())
                .createdAt(message.getCreatedAt())
                .updatedAt(message.getUpdatedAt())
                .build();
    }
}
