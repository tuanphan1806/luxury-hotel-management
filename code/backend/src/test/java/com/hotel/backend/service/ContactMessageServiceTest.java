package com.hotel.backend.service;

import com.hotel.backend.constant.ContactMessageStatus;
import com.hotel.backend.dto.request.ContactMessageReplyRequest;
import com.hotel.backend.entity.ContactMessage;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.ContactMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactMessageServiceTest {

    @Mock
    private ContactMessageRepository contactMessageRepository;
    @Mock
    private EmailService emailService;

    private ContactMessageService contactMessageService;
    private ContactMessage contactMessage;
    private ContactMessageReplyRequest replyRequest;

    @BeforeEach
    void setUp() {
        contactMessageService = new ContactMessageService(contactMessageRepository, emailService);
        contactMessage = ContactMessage.builder()
                .name("Nguyen Van A")
                .email("guest@example.com")
                .subject("Tu van dat phong")
                .message("Toi can ho tro")
                .build();
        contactMessage.setId(11L);

        replyRequest = new ContactMessageReplyRequest();
        replyRequest.setSubject("Re: Tu van dat phong");
        replyRequest.setMessage("Khach san da tiep nhan yeu cau.");
    }

    @Test
    void replyIsRecordedOnlyAfterEmailIsAccepted() {
        when(contactMessageRepository.findById(11L)).thenReturn(Optional.of(contactMessage));
        when(contactMessageRepository.saveAndFlush(contactMessage)).thenReturn(contactMessage);

        var response = contactMessageService.reply(11L, replyRequest, "staff01");

        verify(emailService).send(
                org.mockito.ArgumentMatchers.eq("guest@example.com"),
                org.mockito.ArgumentMatchers.eq("Re: Tu van dat phong"),
                contains("Khach san da tiep nhan yeu cau."));
        assertThat(response.getStatus()).isEqualTo(ContactMessageStatus.RESOLVED);
        assertThat(response.getRepliedBy()).isEqualTo("staff01");
        assertThat(response.getRepliedAt()).isNotNull();
    }

    @Test
    void failedEmailDoesNotMarkContactAsResolved() {
        when(contactMessageRepository.findById(11L)).thenReturn(Optional.of(contactMessage));
        doThrow(new AppException(ErrorCode.EMAIL_DELIVERY_FAILED))
                .when(emailService).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> contactMessageService.reply(11L, replyRequest, "staff01"))
                .isInstanceOf(AppException.class);

        assertThat(contactMessage.getStatus()).isEqualTo(ContactMessageStatus.NEW);
        assertThat(contactMessage.getRepliedAt()).isNull();
        verify(contactMessageRepository, never()).saveAndFlush(contactMessage);
    }
}
