package com.hotel.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionId; // Đồng bộ phiên chat của từng khách
    private String sender;    // "USER" hoặc "AI"

    @Column(columnDefinition = "TEXT")
    private String text;

    private LocalDateTime timestamp;

    public ChatMessage() {}

    public ChatMessage(String sessionId, String sender, String text) {
        this.sessionId = sessionId;
        this.sender = sender;
        this.text = text;
        this.timestamp = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getSender() { return sender; }
    public String getText() { return text; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
