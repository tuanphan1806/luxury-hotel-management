package com.hotel.backend.config;

import com.hotel.backend.service.BrevoEmailDeliveryGateway;
import com.hotel.backend.service.EmailDeliveryGateway;
import com.hotel.backend.service.SendGridEmailDeliveryGateway;
import com.sendgrid.SendGrid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class EmailDeliveryConfiguration {
    @Bean("verificationEmailDeliveryGateway")
    public EmailDeliveryGateway verificationEmailDeliveryGateway(
            @Value("${app.email.provider:sendgrid}") String provider,
            @Value("${app.email.brevo-api-key:}") String brevoKey,
            @Value("${spring.sendgrid.api-key:}") String sendgridKey) {
        return gateway(provider, brevoKey, sendgridKey);
    }

    @Bean("transactionalEmailDeliveryGateway")
    public EmailDeliveryGateway transactionalEmailDeliveryGateway(
            @Value("${app.email.provider:sendgrid}") String provider,
            @Value("${app.email.brevo-api-key:}") String brevoKey,
            @Value("${app.transactional-email.api-key:}") String sendgridKey) {
        return gateway(provider, brevoKey, sendgridKey);
    }

    private EmailDeliveryGateway gateway(String provider, String brevoKey, String sendgridKey) {
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "brevo" -> new BrevoEmailDeliveryGateway(brevoKey);
            case "sendgrid" -> new SendGridEmailDeliveryGateway(new SendGrid(sendgridKey));
            default -> throw new IllegalArgumentException("Unsupported EMAIL_PROVIDER");
        };
    }
}
