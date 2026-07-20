package com.hotel.backend.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.hotel.backend.service.UserServiceDetail;
import com.sendgrid.SendGrid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@EnableMethodSecurity
public class AppConfig {
    //khoi tao spring web security
    //config spring web configurer
    //khoi tao bean cho password encoder
    private final CustomizeRequestFilter requestFilter;
    private final UserServiceDetail userServiceDetail;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
//     .authorizeHttpRequests(request -> request
//     // Public endpoints
//     .requestMatchers(HttpMethod.POST, "/api/user/add").permitAll()
//     .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
//     .requestMatchers("/uploads/**").permitAll()

//     // Admin only
//     .requestMatchers("/api/admin/**").hasRole("ADMIN")

//     // Còn lại phải đăng nhập
//     .anyRequest().authenticated()
// )

    @Bean
    @Order(2)
public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AuthenticationManager authenticationManager
) throws Exception {
    http.cors(Customizer.withDefaults())
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(request -> request
       
            .requestMatchers(HttpMethod.POST, "/auth/logout").authenticated()
            .requestMatchers("/auth/**").permitAll()
            .requestMatchers("/actuator/health", "/actuator/info", "/v3/**", "/swagger-ui*/*swagger-initializer*", "/swagger-ui*/**", "/favicon.ico").permitAll()
            .requestMatchers("/actuator/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/facilities/**", "/room_types/**", "/galeries/**", "/avatar/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/chat").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/contact-messages").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/payments/vnpay/return").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/payments/vnpay/ipn").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/payments/sepay/webhook").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/payments/result/*").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/payments/result/*/abandon").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/payments/cash").hasAnyRole("ADMIN", "STAFF")
            .requestMatchers(HttpMethod.POST, "/api/payments/refund").hasAnyRole("ADMIN", "STAFF")
            .requestMatchers(HttpMethod.GET, "/api/payments/**").authenticated()
            .requestMatchers(HttpMethod.POST, "/api/payments/create").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/room-types/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/facilities/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/galleries/**").permitAll()
            // Chỉ review theo loại phòng là public. /api/reviews/my phải đi
            // qua authentication để frontend có thể refresh access token khi reload.
            .requestMatchers(HttpMethod.GET, "/api/reviews/room-type/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/rooms/available").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/reservations/availability").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/reservations/lookup").permitAll()
            // Đặt rule /my trước /{id}; nếu không "my" có thể bị xem như
            // path variable public và trả 403 từ method security thay vì 401 để refresh.
            .requestMatchers(HttpMethod.GET, "/api/reservations/my").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/reservations/{id}").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/reservations").permitAll()
            .requestMatchers(HttpMethod.PATCH, "/api/reservations/cancel/{id}").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/reservations/{id}/refund-recipient").permitAll()
            .requestMatchers(HttpMethod.PUT, "/api/reservations/{id}/refund-recipient").permitAll()

            // .requestMatchers(HttpMethod.PATCH, "/api/user/change-password").permitAll()
            // .requestMatchers(HttpMethod.PATCH,"/api/reservations").permitAll()
            // .requestMatchers("/api/payments/**").permitAll()
            .anyRequest().authenticated())
        .sessionManagement(manager -> manager.sessionCreationPolicy(STATELESS))
        .authenticationManager(authenticationManager)
        .addFilterBefore(requestFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(authenticationEntryPoint)
            // .accessDeniedHandler((req, res, e) -> {
            //     res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            //     res.setContentType("application/json");
            //     res.setCharacterEncoding("UTF-8");
            //     res.getWriter().write("""
            //         {"status": 403, "error": "Forbidden", "message": "%s"}
            //         """.formatted(e.getMessage()));
            // })
        );
    return http.build();
}

    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userServiceDetail);
        authProvider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(authProvider);
    }


    @Value("${spring.sendgrid.api-key:}")
    private String verificationSendgridApiKey;

    @Value("${app.transactional-email.api-key:}")
    private String transactionalSendgridApiKey;

    @Bean
    @Qualifier("verificationSendGrid")
    public SendGrid verificationSendGrid() {
        return new SendGrid(verificationSendgridApiKey);
    }

    @Bean
    @Qualifier("transactionalSendGrid")
    public SendGrid transactionalSendGrid() {
        return new SendGrid(transactionalSendgridApiKey);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
