package com.hotel.backend.scheduled;

import java.util.Date;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hotel.backend.repository.InvalidatedTokenRepository;
import com.hotel.backend.repository.UserTokenRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "TOKEN-CLEANUP-JOB")
public class TokenCleanupJob {

    private final InvalidatedTokenRepository invalidatedTokenRepository;
    private final UserTokenRepository userTokenRepository;

    @Scheduled(cron = "0 0 2 * * *") // 2:00 AM mỗi ngày
    @Transactional
    public void cleanExpiredTokens() {
        Date now = new Date();
        invalidatedTokenRepository.deleteAllByExpiryTimeBefore(now);
        userTokenRepository.deleteAllByRefreshTokenExpiresAtBefore(now);
        log.info("Cleaned expired invalidated tokens and user sessions");
    }
}
