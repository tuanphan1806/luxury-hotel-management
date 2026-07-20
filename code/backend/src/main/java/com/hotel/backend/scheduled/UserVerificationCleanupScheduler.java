package com.hotel.backend.scheduled;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j(topic = "USER_VERIFICATION_CLEANUP_SCHEDULER")
@Component
@RequiredArgsConstructor
public class UserVerificationCleanupScheduler {

    @Value("${app.email-verification-ttl-hours:48}")
    private long verificationTimeoutHours;

    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional(rollbackFor = Exception.class)
    public void expireUnverifiedUsers() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(verificationTimeoutHours);
        List<User> expiredUsers = userRepository.findByStatusAndEmailVerifiedFalseAndCreatedAtBefore(
                UserStatus.PENDING_VERIFICATION,
                cutoff);

        int deletedProfiles = 0;
        for (User user : expiredUsers) {
            user.setStatus(UserStatus.INACTIVE);
            user.setVerificationCode(null);
            user.setVerificationExpiresAt(null);
            user.invalidateSessions();
            var profileWithoutReservations =
                    customerProfileRepository.findWithoutReservationsByLinkedUserId(user.getId());
            if (profileWithoutReservations.isPresent()) {
                customerProfileRepository.delete(profileWithoutReservations.get());
                deletedProfiles++;
            }
        }

        userRepository.saveAll(expiredUsers);

        if (!expiredUsers.isEmpty()) {
            log.info("Marked {} unverified users as INACTIVE and deleted {} empty customer profiles",
                    expiredUsers.size(),
                    deletedProfiles);
        }
    }
}
