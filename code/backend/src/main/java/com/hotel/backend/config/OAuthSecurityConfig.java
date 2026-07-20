package com.hotel.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthSecurityConfig {

    @Bean
    public ConfiguredClientRegistrationRepository clientRegistrationRepository(
            OAuthProperties properties) {
        List<ClientRegistration> registrations = new ArrayList<>(2);
        String redirectUri = properties.normalizedBackendBaseUrl()
                + "/login/oauth2/code/{registrationId}";

        if (properties.getGoogle().isConfigured()) {
            registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(properties.getGoogle().getClientId().trim())
                    .clientSecret(properties.getGoogle().getClientSecret().trim())
                    .redirectUri(redirectUri)
                    .scope("openid", "profile", "email")
                    .build());
        }
        if (properties.getFacebook().isConfigured()) {
            registrations.add(CommonOAuth2Provider.FACEBOOK.getBuilder("facebook")
                    .clientId(properties.getFacebook().getClientId().trim())
                    .clientSecret(properties.getFacebook().getClientSecret().trim())
                    .redirectUri(redirectUri)
                    .scope("public_profile", "email")
                    .userInfoUri("https://graph.facebook.com/me?fields=id,name,email,picture.type(large)")
                    .build());
        }
        return new ConfiguredClientRegistrationRepository(registrations);
    }

    @Bean
    public OAuth2AuthorizedClientService oauth2AuthorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain oauthSecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            OAuthAuthenticationSuccessHandler successHandler,
            OAuthAuthenticationFailureHandler failureHandler) throws Exception {
        http.securityMatcher("/oauth2/**", "/login/oauth2/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .oauth2Login(oauth -> oauth
                        .clientRegistrationRepository(clientRegistrationRepository)
                        .authorizedClientService(authorizedClientService)
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        return http.build();
    }
}
