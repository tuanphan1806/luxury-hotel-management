package com.hotel.backend.service;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.OAuthAccountRepository;
import com.hotel.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OAuthPostVerificationLoginService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final OAuthLoginTicketService loginTicketService;

    @Transactional
    public Optional<String> issueLoginTicketIfEligible(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null
                || user.getPassword() != null
                || user.getType() != UserType.CUSTOMER
                || user.getStatus() != UserStatus.ACTIVE
                || !user.isEmailVerified()
                || !oauthAccountRepository.existsByUser(user)) {
            return Optional.empty();
        }
        return Optional.of(loginTicketService.issue(user));
    }
}
