package com.hotel.backend.service;


import org.springframework.security.core.GrantedAuthority;

import com.hotel.backend.constant.TokenType;

import java.util.Collection;
import java.util.Date;
import java.util.List;
public interface JwtService {
    String generateAccessToken(String username, List<String> authorities, long securityVersion);
    String generateRefreshToken(String username, List<String> authorities, long securityVersion);
    String extractUsername(String token, TokenType type);
    String extractJti(String token, TokenType type);
    Date extractExpiration(String token, TokenType type);
    long extractSecurityVersion(String token, TokenType type);
}
