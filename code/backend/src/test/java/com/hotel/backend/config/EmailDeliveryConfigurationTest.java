package com.hotel.backend.config;

import com.hotel.backend.service.BrevoEmailDeliveryGateway;
import com.hotel.backend.service.SendGridEmailDeliveryGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDeliveryConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EmailDeliveryConfiguration.class);

    @Test
    void preservesSendgridAsTheDefault() {
        context.run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getBean("verificationEmailDeliveryGateway"))
                    .isInstanceOf(SendGridEmailDeliveryGateway.class);
            assertThat(app.getBean("transactionalEmailDeliveryGateway"))
                    .isInstanceOf(SendGridEmailDeliveryGateway.class);
        });
    }

    @Test
    void selectsBrevoForBothChannelsWithoutSendgridCredentials() {
        context.withPropertyValues("app.email.provider=brevo", "app.email.brevo-api-key=test-only-key")
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    assertThat(app.getBean("verificationEmailDeliveryGateway"))
                            .isInstanceOf(BrevoEmailDeliveryGateway.class);
                    assertThat(app.getBean("transactionalEmailDeliveryGateway"))
                            .isInstanceOf(BrevoEmailDeliveryGateway.class);
                });
    }

    @Test
    void rejectsMissingBrevoCredentialAndUnknownProvider() {
        context.withPropertyValues("app.email.provider=brevo")
                .run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("app.email.provider=unknown")
                .run(app -> assertThat(app).hasFailed());
    }
}
