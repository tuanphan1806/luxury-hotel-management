package com.hotel.backend.service.Impl;


import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hotel.backend.constant.TokenType;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.SignInRequest;
import com.hotel.backend.dto.response.TokenResponse;
import com.hotel.backend.entity.InvalidatedToken;
import com.hotel.backend.entity.UserToken;
import com.hotel.backend.exception.InvalidDataException;
import com.hotel.backend.repository.InvalidatedTokenRepository;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.UserTokenRepository;
import com.hotel.backend.service.AuthenticationService;
import com.hotel.backend.service.JwtService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTHENTICATION-SERVICE")
public class AuthenticationServiceImpl implements AuthenticationService{

    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    private final InvalidatedTokenRepository invalidatedTokenRepository;
    private final UserTokenRepository userTokenRepository;

    @Transactional(rollbackFor = Exception.class)
    public TokenResponse getAccessToken(SignInRequest request){
        log.info("Get AccessToken");

        List<String> authorities= new ArrayList<>();

        // 1. Xác thực username/password qua AuthenticationManager
        // Nếu sai → throw AuthenticationException (BadCredentials, DisabledException, ...)
        try {
            Authentication authenticate= authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            authenticate.getAuthorities().forEach(authority->authorities.add(authority.getAuthority()));

            SecurityContextHolder.getContext().setAuthentication(authenticate);            
        } catch (AuthenticationException e) {
            log.info("Login failed with authentication error type={}", e.getClass().getSimpleName());
            throw new BadCredentialsException("Invalid credentials");
        }

        String normalizedLogin = request.getUsername().trim();
        var user = userRepository.findByUsernameIgnoreCase(normalizedLogin)
                .or(() -> userRepository.findByEmailIgnoreCase(normalizedLogin))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        TokenResponse response = issueTokensInternal(user, authorities);
        log.info("Login success for username={}", request.getUsername());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TokenResponse issueTokens(com.hotel.backend.entity.User user) {
        if (user == null || !user.isEnabled()) {
            throw new InvalidDataException("Tài khoản không hoạt động");
        }
        List<String> authorities = user.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .toList();
        return issueTokensInternal(user, authorities);
    }

    private TokenResponse issueTokensInternal(
            com.hotel.backend.entity.User user,
            List<String> authorities) {
        boolean isSingleSession = user.getType() == UserType.STAFF
                || user.getType() == UserType.ADMIN;

        if (isSingleSession) {
            userTokenRepository.findById(user.getId()).ifPresent(existing -> {
                log.warn("User {} (type={}) logging in again — invalidating old session",
                        user.getUsername(), user.getType());
                if (existing.getAccessToken() != null) {
                    invalidatedTokenRepository.save(InvalidatedToken.builder()
                            .token(existing.getAccessToken())
                            .expiryTime(resolveExpiry(existing.getAccessTokenExpiresAt()))
                            .reason("SESSION_REPLACED")
                            .build());
                }
                invalidatedTokenRepository.save(InvalidatedToken.builder()
                        .token(existing.getRefreshToken())
                        .expiryTime(resolveExpiry(existing.getRefreshTokenExpiresAt()))
                        .reason("SESSION_REPLACED")
                        .build());
                userTokenRepository.delete(existing);
            });
        }

        String accessToken = jwtService.generateAccessToken(
                user.getUsername(), authorities, user.getSecurityVersion());
        String refreshToken = jwtService.generateRefreshToken(
                user.getUsername(), authorities, user.getSecurityVersion());

        if (isSingleSession) {
            String accessJti = jwtService.extractJti(stripBearer(accessToken), TokenType.ACCESS_TOKEN);
            String refreshJti = jwtService.extractJti(stripBearer(refreshToken), TokenType.REFRESH_TOKEN);
            userTokenRepository.save(UserToken.builder()
                    .userId(user.getId())
                    .accessToken(accessJti)
                    .accessTokenExpiresAt(jwtService.extractExpiration(
                            stripBearer(accessToken), TokenType.ACCESS_TOKEN))
                    .refreshToken(refreshJti)
                    .refreshTokenExpiresAt(jwtService.extractExpiration(
                            stripBearer(refreshToken), TokenType.REFRESH_TOKEN))
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("Single-session token saved for username={}, type={}",
                    user.getUsername(), user.getType());
        }

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }


    @Transactional(rollbackFor = Exception.class)
    public TokenResponse getRefreshToken(String refreshToken) {
        log.info("Get RefreshToken");

        if (refreshToken == null || !refreshToken.startsWith("Bearer ")) {
            throw new InvalidDataException("Invalid refresh token format");
        }

        String token = refreshToken.substring(7).trim();
        // 1. Extract username từ refresh token
        String username = jwtService.extractUsername(token, TokenType.REFRESH_TOKEN);

        //  Check blacklist
        String rJti = jwtService.extractJti(token, TokenType.REFRESH_TOKEN);
        if (invalidatedTokenRepository.existsByToken(rJti)) {
            throw new InvalidDataException("Refresh token đã bị vô hiệu hóa, vui lòng đăng nhập lại");
        }

        // 2. Load user
        var user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (!user.isEnabled()) {
            throw new InvalidDataException("Tài khoản không còn hoạt động, vui lòng đăng nhập lại");
        }

        if (jwtService.extractSecurityVersion(token, TokenType.REFRESH_TOKEN) != user.getSecurityVersion()) {
            throw new InvalidDataException("Phiên đăng nhập không còn hợp lệ, vui lòng đăng nhập lại");
        }

        boolean isSingleSession = user.getType() == UserType.STAFF
                               || user.getType() == UserType.ADMIN;

        if (isSingleSession) {
            UserToken currentToken = userTokenRepository.findById(user.getId())
                    .orElseThrow(() -> new InvalidDataException("Phiên đăng nhập không hợp lệ, vui lòng đăng nhập lại"));

            if (!rJti.equals(currentToken.getRefreshToken())) {
                throw new InvalidDataException("Refresh token không thuộc phiên đăng nhập hiện tại");
            }

            if (currentToken.getAccessToken() != null
                    && !invalidatedTokenRepository.existsByToken(currentToken.getAccessToken())) {
                invalidatedTokenRepository.save(InvalidatedToken.builder()
                        .token(currentToken.getAccessToken())
                        .expiryTime(resolveExpiry(currentToken.getAccessTokenExpiresAt()))
                        .reason("REFRESH_ROTATED")
                        .build());
            }
        }

        List<String> authorities=new ArrayList<>();
        user.getAuthorities().forEach(authority->authorities.add(authority.getAuthority()));

        invalidateTokenStrict(
                rJti,
                jwtService.extractExpiration(token, TokenType.REFRESH_TOKEN),
                "REFRESH_ROTATED",
                "Refresh token đã bị sử dụng, vui lòng đăng nhập lại");

        // 3. Generate token mới
        String newAccessToken = jwtService.generateAccessToken(username, authorities, user.getSecurityVersion());
        String newRefreshToken = jwtService.generateRefreshToken(username, authorities, user.getSecurityVersion());

        if (isSingleSession) {
            String newAccessJti = jwtService.extractJti(stripBearer(newAccessToken), TokenType.ACCESS_TOKEN);
            String newRefreshJti = jwtService.extractJti(stripBearer(newRefreshToken), TokenType.REFRESH_TOKEN);
            userTokenRepository.save(UserToken.builder()
                    .userId(user.getId())
                    .accessToken(newAccessJti)
                    .accessTokenExpiresAt(jwtService.extractExpiration(stripBearer(newAccessToken), TokenType.ACCESS_TOKEN))
                    .refreshToken(newRefreshJti)
                    .refreshTokenExpiresAt(jwtService.extractExpiration(stripBearer(newRefreshToken), TokenType.REFRESH_TOKEN))
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        
        log.info("Refresh token rotated for username={}", username);
        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public void logout(String accessToken, String refreshToken) {
        log.info("Logout request");

        if (accessToken == null || !accessToken.startsWith("Bearer ")
                || refreshToken == null || !refreshToken.startsWith("Bearer ")) {
            throw new InvalidDataException("Phiên đăng nhập không hợp lệ");
        }

        String token = accessToken.substring(7).trim();
        String rToken = refreshToken.substring(7).trim();
        // 1. Extract thông tin từ token
        String jti = jwtService.extractJti(token, TokenType.ACCESS_TOKEN);
        Date expiryTime = jwtService.extractExpiration(token, TokenType.ACCESS_TOKEN);
        
        if (invalidatedTokenRepository.existsByToken(jti)) {
            throw new InvalidDataException("Token đã bị vô hiệu hóa");
        }
        invalidateTokenStrict(jti, expiryTime, "LOGOUT", "Token đã bị vô hiệu hóa");

        // Blacklist refresh token
        String rJti        = jwtService.extractJti(rToken, TokenType.REFRESH_TOKEN);
        Date   rExpiryTime = jwtService.extractExpiration(rToken, TokenType.REFRESH_TOKEN);
        invalidateTokenStrict(rJti, rExpiryTime, "LOGOUT", "Refresh token đã bị vô hiệu hóa");

        // Xóa record trong user_tokens
         String username = jwtService.extractUsername(token, TokenType.ACCESS_TOKEN);
        userRepository.findByUsernameIgnoreCase(username).ifPresent(user -> {
            boolean isSingleSession = user.getType() == UserType.STAFF
                                   || user.getType() == UserType.ADMIN;
            if (isSingleSession) {
                userTokenRepository.deleteById(user.getId());
                log.info("Single-session record removed for username={}", username);
            }
        });

        log.info("Logout success — tokens invalidated, jti={}", jti);
        }


        // ---- private helper ----
private String stripBearer(String token) {
    if (token == null) return null;
    return token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
}

private Date resolveExpiry(Date expiryTime) {
    return expiryTime != null
            ? expiryTime
            : Date.from(Instant.now().plus(Duration.ofDays(30)));
}

private void invalidateTokenStrict(String jti, Date expiryTime, String reason, String duplicateMessage) {
    try {
        invalidatedTokenRepository.insertInvalidatedToken(jti, expiryTime, reason);
    } catch (DataIntegrityViolationException e) {
        throw new InvalidDataException(duplicateMessage);
    }
}
}
