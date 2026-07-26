package com.hotel.backend.service;

import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.CustomerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns the existing User to CustomerProfile synchronization rules.
 */
@Service
@RequiredArgsConstructor
public class CustomerProfileLinkService {

    private final CustomerProfileRepository customerProfileRepository;

    public CustomerProfile ensureForUser(User user) {
        return customerProfileRepository.findByLinkedUserId(user.getId())
                .orElseGet(() -> customerProfileRepository.save(CustomerProfile.builder()
                        .fullName(user.getFullName())
                        .phone(user.getPhone())
                        .email(user.getEmail())
                        .address(user.getAddress())
                        .source(CustomerProfileSource.ONLINE)
                        .linkedUser(user)
                        .build()));
    }

    public void sync(User user) {
        customerProfileRepository.findByLinkedUserId(user.getId()).ifPresent(profile -> {
            profile.setFullName(user.getFullName());
            profile.setPhone(user.getPhone());
            profile.setEmail(user.getEmail());
            profile.setAddress(user.getAddress());
            customerProfileRepository.save(profile);
        });
    }
}
