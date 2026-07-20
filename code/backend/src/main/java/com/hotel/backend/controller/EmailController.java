package com.hotel.backend.controller;

import org.springframework.web.bind.annotation.RestController;

import com.hotel.backend.service.EmailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;


@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j(topic = "EMAIL-CONTROLLER")
public class EmailController {
    private final EmailService emailService;

    @Operation(summary = "Send email", description = "API send an email with recipient, subject, and content")
    @GetMapping("/send-email")
    @PreAuthorize("hasRole('ADMIN')")
    public void send(@RequestParam String to,@RequestParam String subject,@RequestParam String content){
        log.info("Sending email to {}",to);
        emailService.send(to, subject, content);
        log.info("Email sent");
    }

    @Operation(summary = "Verify email", description = "API send an email verification message to a user")
    @GetMapping("/verify-email")
    @PreAuthorize("hasRole('ADMIN')")
    public void emailVerification(@RequestParam String to,@RequestParam String name) throws IOException{
        log.info("Verifying email to {}",to);
        emailService.emailVerification(to, name);
        log.info("Email sent");
    }
}
