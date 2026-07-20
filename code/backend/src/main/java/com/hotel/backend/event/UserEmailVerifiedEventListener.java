package com.hotel.backend.event;

import com.hotel.backend.service.CustomerProfileClaimService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "USER-EMAIL-VERIFIED-LISTENER")
public class UserEmailVerifiedEventListener {

    private final CustomerProfileClaimService customerProfileClaimService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void claimCustomerProfile(UserEmailVerifiedEvent event) {
        try {
            customerProfileClaimService.claimForVerifiedUser(event.userId());
        } catch (RuntimeException ex) {
            // Xác thực tài khoản đã commit; lỗi đồng bộ hồ sơ không được phép đổi user về pending.
            log.error("Unable to claim customer profile after email verification: userId={}, error={}",
                    event.userId(), ex.getMessage());
        }
    }
}
