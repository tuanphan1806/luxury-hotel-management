package com.hotel.backend.dto.response;

import com.hotel.backend.constant.ContactMessageStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ContactMessageResponse {
    private Long id;
    private String name;
    private String email;
    private String phone;
    private String subject;
    private String message;
    private ContactMessageStatus status;
    private String replySubject;
    private String replyMessage;
    private LocalDateTime repliedAt;
    private String repliedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
