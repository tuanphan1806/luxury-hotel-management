package com.hotel.backend.integration;

import com.hotel.backend.constant.ContactMessageStatus;
import com.hotel.backend.dto.request.ContactMessageRequest;
import com.hotel.backend.dto.request.ContactMessageStatusRequest;
import com.hotel.backend.repository.ContactMessageRepository;
import com.hotel.backend.service.ContactMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContactMessageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContactMessageRepository contactMessageRepository;

    @Autowired
    private ContactMessageService contactMessageService;

    @AfterEach
    void cleanUp() {
        contactMessageRepository.deleteAll();
    }

    @Test
    void guestCanSubmitContactMessageAndItIsPersisted() throws Exception {
        mockMvc.perform(post("/api/contact-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Nguyen Van A",
                                  "email": "guest@example.com",
                                  "phone": "0900000000",
                                  "subject": "Tu van dat phong",
                                  "message": "Toi can tu van loai phong phu hop."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("NEW"));

        assertThat(contactMessageRepository.count()).isEqualTo(1);
        assertThat(contactMessageRepository.findAll().get(0).getEmail()).isEqualTo("guest@example.com");
    }

    @Test
    void dashboardWorkflowCanUpdateStatusAndDeleteMessage() {
        ContactMessageRequest createRequest = new ContactMessageRequest();
        createRequest.setName("Nguyen Van B");
        createRequest.setEmail("operator@example.com");
        createRequest.setSubject("Can ho tro");
        createRequest.setMessage("Vui long lien he lai voi toi.");

        var created = contactMessageService.create(createRequest);

        ContactMessageStatusRequest statusRequest = new ContactMessageStatusRequest();
        statusRequest.setStatus(ContactMessageStatus.RESOLVED);
        var updated = contactMessageService.updateStatus(created.getId(), statusRequest);

        assertThat(updated.getStatus()).isEqualTo(ContactMessageStatus.RESOLVED);
        assertThat(updated.getUpdatedAt()).isNotNull();

        contactMessageService.delete(created.getId());
        assertThat(contactMessageRepository.existsById(created.getId())).isFalse();
    }
}
