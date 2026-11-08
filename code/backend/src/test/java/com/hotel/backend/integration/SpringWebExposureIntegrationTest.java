package com.hotel.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.view.xslt.XsltView;
import org.springframework.web.servlet.view.xslt.XsltViewResolver;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the application-specific evidence in spring-web.openvex.json. */
@SpringBootTest
@ActiveProfiles("test")
class SpringWebExposureIntegrationTest {
    @Autowired ApplicationContext context;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void applicationHandlersRemainResponseBodyEndpointsWithoutViewFragmentsOrSse() {
        var applicationHandlers = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getPackageName().startsWith("com.hotel.backend"))
                .toList();
        assertThat(applicationHandlers).isNotEmpty();
        applicationHandlers.forEach(entry -> {
            var handler = entry.getValue();
            assertThat(AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RestController.class))
                    .as("Review the XSLT/SSE applicability evidence before adding view controllers: %s", handler)
                    .isTrue();
            assertThat(entry.getKey().getProducesCondition().getProducibleMediaTypes())
                    .as("Review the SSE fragment advisory before adding streaming responses: %s", handler)
                    .doesNotContain(MediaType.TEXT_EVENT_STREAM);
            assertThat(handler.getMethod().getGenericReturnType().getTypeName())
                    .doesNotContain("XsltView", "ModelAndView", "FragmentsRendering", "SseEmitter",
                            "ResponseBodyEmitter", "ServerSentEvent", "org.springframework.web.reactive.result.view");
        });
    }

    @Test
    void applicationDoesNotInstallXsltViewsOrFunctionalServerRoutes() {
        assertThat(context.getBeanNamesForType(XsltView.class)).isEmpty();
        assertThat(context.getBeanNamesForType(XsltViewResolver.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.springframework.web.reactive.function.server.RouterFunction.class))
                .as("WebFlux is a client dependency; functional server routes need a fresh CVE-2026-47892 review")
                .isEmpty();
        assertThat(context.getBeanNamesForType(org.springframework.web.servlet.function.RouterFunction.class))
                .isEmpty();
    }
}
