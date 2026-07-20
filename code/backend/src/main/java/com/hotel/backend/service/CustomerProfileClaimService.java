package com.hotel.backend.service;

import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.ResourceNotFoundException;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "CUSTOMER-PROFILE-CLAIM")
public class CustomerProfileClaimService {

    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final ReservationRepository reservationRepository;

    @Transactional(rollbackFor = Exception.class)
    public void claimForVerifiedUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Chỉ liên kết lịch sử đặt phòng sau khi email đã được xác thực.
        // Tránh trường hợp một tài khoản chưa xác thực chiếm các đơn guest
        // chỉ vì nhập cùng địa chỉ email.
        if (!user.isEmailVerified() || !user.isEnabled()) {
            return;
        }

        List<CustomerProfile> claimableProfiles = customerProfileRepository.findAllByEmail(user.getEmail()).stream()
                .filter(profile -> profile.getLinkedUser() == null
                        || profile.getLinkedUser().getId().equals(user.getId()))
                .toList();

        if (claimableProfiles.isEmpty()) {
            return;
        }

        CustomerProfile canonicalProfile = selectCanonicalClaimProfile(claimableProfiles, user);
        long movedReservations = 0;
        int deletedProfiles = 0;

        for (CustomerProfile profile : claimableProfiles) {
            if (profile.getId().equals(canonicalProfile.getId())) {
                continue;
            }

            if (profile.getLinkedUser() != null) {
                profile.setLinkedUser(null);
                customerProfileRepository.saveAndFlush(profile);
            }

            movedReservations += reservationRepository.reassignCustomerProfile(profile, canonicalProfile);
            customerProfileRepository.delete(profile);
            deletedProfiles++;
        }

        canonicalProfile.setLinkedUser(user);
        if (!StringUtils.hasText(canonicalProfile.getFullName())) {
            canonicalProfile.setFullName(user.getFullName());
        }
        if (!StringUtils.hasText(canonicalProfile.getPhone())) {
            canonicalProfile.setPhone(user.getPhone());
        }
        if (!StringUtils.hasText(canonicalProfile.getAddress())) {
            canonicalProfile.setAddress(user.getAddress());
        }
        customerProfileRepository.save(canonicalProfile);

        log.info("Claimed customer profile {} for verified user {}, movedReservations={}, deletedProfiles={}",
                canonicalProfile.getId(), user.getId(), movedReservations, deletedProfiles);
    }

    private CustomerProfile selectCanonicalClaimProfile(List<CustomerProfile> profiles, User user) {
        return profiles.stream()
                .filter(profile -> profile.getLinkedUser() == null)
                .max((left, right) -> Long.compare(
                        reservationRepository.countByCustomerProfile(left),
                        reservationRepository.countByCustomerProfile(right)))
                .orElseGet(() -> profiles.stream()
                        .filter(profile -> profile.getLinkedUser() != null
                                && profile.getLinkedUser().getId().equals(user.getId()))
                        .findFirst()
                        .orElse(profiles.get(0)));
    }
}
