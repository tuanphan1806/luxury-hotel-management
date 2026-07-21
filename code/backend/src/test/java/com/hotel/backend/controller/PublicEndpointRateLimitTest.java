package com.hotel.backend.controller;

import com.hotel.backend.dto.request.ChatRequest;
import com.hotel.backend.dto.request.ContactMessageRequest;
import com.hotel.backend.dto.request.UserCreationRequest;
import com.hotel.backend.dto.response.ChatResponse;
import com.hotel.backend.security.ClientIpResolver;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.service.AuthCookieService;
import com.hotel.backend.service.AuthenticationService;
import com.hotel.backend.service.ChatBotService;
import com.hotel.backend.service.ContactMessageService;
import com.hotel.backend.service.PasswordResetService;
import com.hotel.backend.service.UserService;
import com.hotel.backend.service.OAuthPostVerificationLoginService;
import com.hotel.backend.config.OAuthProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static com.hotel.backend.util.SecurityTokenHasher.sha256;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicEndpointRateLimitTest {

    @Mock
    private AuthRateLimitService rateLimitService;
    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private ChatBotService chatBotService;
    @Mock
    private ContactMessageService contactMessageService;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private PasswordResetService passwordResetService;
    @Mock
    private AuthCookieService authCookieService;
    @Mock
    private UserService userService;
    @Mock
    private OAuthPostVerificationLoginService oauthPostVerificationLoginService;
    @Mock
    private OAuthProperties oauthProperties;

    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();

    @Test
    void chatHasAnIpWideLimitAndAnIpBoundConversationKey() {
        ChatController controller = new ChatController(chatBotService, rateLimitService, clientIpResolver);
        ChatRequest request = new ChatRequest();
        request.setQuestion("Khách sạn có hồ bơi không?");
        request.setConversationId("browser-conversation");
        when(clientIpResolver.resolve(httpRequest)).thenReturn("203.0.113.10");
        when(chatBotService.askWithAction(
                request.getQuestion(),
                "chat:203.0.113.10:" + sha256("browser-conversation")))
                .thenReturn(ChatResponse.builder().answer("Có").build());

        controller.chat(request, httpRequest);

        verify(rateLimitService).check("chat-ip:203.0.113.10", 30, Duration.ofMinutes(1));
        verify(chatBotService).askWithAction(
                request.getQuestion(),
                "chat:203.0.113.10:" + sha256("browser-conversation"));
    }

    @Test
    void contactFormIsLimitedByIpAndHashedEmail() {
        ContactMessageController controller = new ContactMessageController(
                contactMessageService,
                rateLimitService,
                clientIpResolver);
        ContactMessageRequest request = new ContactMessageRequest();
        request.setName("Nguyen Van A");
        request.setEmail("Guest@Example.com");
        request.setSubject("Can ho tro");
        request.setMessage("Noi dung");
        when(clientIpResolver.resolve(httpRequest)).thenReturn("203.0.113.11");

        controller.create(request, httpRequest);

        verify(rateLimitService).check("contact-ip:203.0.113.11", 20, Duration.ofHours(1));
        verify(rateLimitService).check(
                "contact:203.0.113.11:" + sha256("guest@example.com"),
                5,
                Duration.ofMinutes(15));
    }

    @Test
    void registrationIsLimitedByIpAndHashedEmail() {
        AuthenticationController controller = new AuthenticationController(
                authenticationService,
                passwordResetService,
                rateLimitService,
                clientIpResolver,
                authCookieService,
                oauthPostVerificationLoginService,
                oauthProperties,
                userService);
        UserCreationRequest request = new UserCreationRequest();
        ReflectionTestUtils.setField(request, "fullName", "Nguyen Van A");
        ReflectionTestUtils.setField(request, "username", "guest");
        ReflectionTestUtils.setField(request, "email", "Guest@Example.com");
        ReflectionTestUtils.setField(request, "phone", "0900000000");
        ReflectionTestUtils.setField(request, "password", "Password123");
        when(clientIpResolver.resolve(httpRequest)).thenReturn("203.0.113.12");

        controller.CreateUser(request, httpRequest);

        verify(rateLimitService).check("register-ip:203.0.113.12", 20, Duration.ofHours(1));
        verify(rateLimitService).check(
                "register:203.0.113.12:" + sha256("guest@example.com"),
                5,
                Duration.ofHours(1));
        verify(userService).save(request);
    }
}
