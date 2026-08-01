package com.hotel.backend.service.Impl;

import java.util.Map;
import java.util.UUID;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.dto.request.UserCreationRequest;
import com.hotel.backend.dto.request.UserCreationWithTypeRequest;
import com.hotel.backend.dto.request.UserPasswordRequest;
import com.hotel.backend.dto.request.UserUpdateRequest;
import com.hotel.backend.dto.request.AdminResetPasswordRequest;
import com.hotel.backend.dto.response.UserPageResponse;
import com.hotel.backend.dto.response.UserResponse;
import com.hotel.backend.event.UserRegisteredEvent;
import com.hotel.backend.event.UserEmailVerifiedEvent;
import com.hotel.backend.service.EmailService;
import com.hotel.backend.service.MediaAssetService;
import com.hotel.backend.service.UserService;
import com.hotel.backend.service.ReservationAuditService;
import com.hotel.backend.service.CustomerProfileLinkService;
import com.hotel.backend.service.UserIdentityNormalizer;
import com.hotel.backend.service.UserPageableFactory;
import com.hotel.backend.service.UserViewMapper;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.UserTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.hotel.backend.entity.User;
import com.hotel.backend.exception.DuplicateResourceException;
import com.hotel.backend.exception.InvalidDataException;
import com.hotel.backend.exception.ResourceNotFoundException;
import static com.hotel.backend.util.SecurityTokenHasher.sha256;
@Service
@Slf4j(topic = "USER-SERVICE")
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    // inject your repos/mappers here via constructor (Lombok handles it)
    private final UserRepository userRepository;
    private final UserTokenRepository userTokenRepository;
    private final CustomerProfileLinkService customerProfileLinkService;

    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final ApplicationEventPublisher eventPublisher;
    private final MediaAssetService mediaAssetService;
    private final ReservationAuditService reservationAuditService;

    @Override
    public UserPageResponse findAll(String keyword,String sort, int page,int size) {
        int pageNo = page > 0 ? page - 1 : 0;
        Pageable pageable = UserPageableFactory.create(sort, page, size);

        Page<User> entityPage;
        if (StringUtils.hasLength(keyword)) {
            keyword="%"+keyword.toLowerCase()+"%";
            entityPage=userRepository.searchByKeyword(keyword,pageable);
        }else{
            entityPage= userRepository.findAll(pageable);
        }

        return UserViewMapper.toPage(
                pageNo, pageable.getPageSize(), entityPage);
    }
    
    @Override
    public UserResponse findById(Long id) {
        User user = getUserById(id);
        return UserViewMapper.toResponse(user);
    }


    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long save(UserCreationRequest req) {
        log.info("Saving user {}", req.getUsername());

        String username = UserIdentityNormalizer.username(req.getUsername());
        String email = UserIdentityNormalizer.email(req.getEmail());

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new DuplicateResourceException("User", "username", username);
        }
    
        // Check duplicate email (nếu có)
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateResourceException("User", "email", email);
        }
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new DuplicateResourceException("User", "phone", req.getPhone());
        }
        User user = User.builder()
           .fullName(req.getFullName())
           .username(username)
           .email(email)
           .phone(req.getPhone())
           .address(req.getAddress())
           .imageUrl(req.getImageUrl()) 
            .password(passwordEncoder.encode(req.getPassword()))
           .build();

        userRepository.save(user);
        user.setImageUrl(mediaAssetService.replaceReference(
                null,
                user.getImageUrl(),
                UploadFolder.AVATAR,
                MediaAssetOwnerType.USER_AVATAR,
                user.getId()));
        customerProfileLinkService.ensureForUser(user);
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getId()));
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createUserWithType(UserCreationWithTypeRequest req) {
        log.info("Saving user {} with type {}", req.getUsername(),req.getType());

        String username = UserIdentityNormalizer.username(req.getUsername());
        String email = UserIdentityNormalizer.email(req.getEmail());

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new DuplicateResourceException("User", "username", username);
        }
    
        // Check duplicate email (nếu có)
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateResourceException("User", "email", email);
        }
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new DuplicateResourceException("User", "phone", req.getPhone());
        }
        UserType type = req.getType() != null ? req.getType() : UserType.CUSTOMER;
        User user = User.builder()
           .fullName(req.getFullName())
           .username(username)
           .email(email)
           .type(type)
           .phone(req.getPhone())
           .address(req.getAddress())
           .imageUrl(req.getImageUrl()) 
            .password(passwordEncoder.encode(req.getPassword()))
           .build();

        userRepository.save(user);
        user.setImageUrl(mediaAssetService.replaceReference(
                null,
                user.getImageUrl(),
                UploadFolder.AVATAR,
                MediaAssetOwnerType.USER_AVATAR,
                user.getId()));
        if (UserType.CUSTOMER.equals(user.getType())) {
            customerProfileLinkService.ensureForUser(user);
        }
        log.info("User create with type {} successfully",user.getType());
        reservationAuditService.recordTarget(
                "USER", String.valueOf(user.getId()),
                ReservationAuditAction.USER_CREATED,
                "ADMIN tạo tài khoản vận hành",
                null,
                Map.of("role", user.getType().name(), "status", user.getStatus().name()),
                Map.of("account", username),
                UUID.randomUUID().toString(),
                null);
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getId()));
        return user.getId();
    }


    @Override
    @Transactional(rollbackFor=Exception.class)
    public void update(UserUpdateRequest req,Long id) {
        //get user by id
        User user = getUserById(id);
        validateUniqueFieldsForUpdate(req, id);
        //set data
        user.setFullName(req.getFullName());
        user.setUsername(UserIdentityNormalizer.username(req.getUsername()));
        user.setEmail(UserIdentityNormalizer.email(req.getEmail()));
        UserType oldType = user.getType();
        boolean roleChanged = req.getType() != null && !req.getType().equals(oldType);
        if (roleChanged) {
            user.setType(req.getType());
            invalidateSessions(user);
        }
        user.setPhone(req.getPhone());
        user.setAddress(req.getAddress());
        if (req.getImageUrl() != null) {
            user.setImageUrl(mediaAssetService.replaceReference(
                    user.getImageUrl(),
                    req.getImageUrl(),
                    UploadFolder.AVATAR,
                    MediaAssetOwnerType.USER_AVATAR,
                    user.getId()));
        }
        //save to db
        userRepository.save(user);
        customerProfileLinkService.sync(user);
        if (roleChanged) {
            reservationAuditService.recordTarget(
                    "USER", String.valueOf(user.getId()),
                    ReservationAuditAction.USER_ROLE_CHANGED,
                    "Thay đổi vai trò người dùng",
                    Map.of("role", oldType.name()),
                    Map.of("role", user.getType().name()),
                    Map.of("sessionsInvalidated", true),
                    UUID.randomUUID().toString(),
                    null);
        }
        log.info("Update User successfully");
    }

    private void validateUniqueFieldsForUpdate(UserUpdateRequest req, Long id) {
        if (userRepository.existsByUsernameIgnoreCaseAndIdNot(
                UserIdentityNormalizer.username(req.getUsername()), id)) {
            throw new DuplicateResourceException("User", "username", req.getUsername());
        }
        if (userRepository.existsByEmailIgnoreCaseAndIdNot(
                UserIdentityNormalizer.email(req.getEmail()), id)) {
            throw new DuplicateResourceException("User", "email", req.getEmail());
        }
        if (userRepository.existsByPhoneAndIdNot(req.getPhone(), id)) {
            throw new DuplicateResourceException("User", "phone", req.getPhone());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(UserPasswordRequest req) {
        User user = getUserById(req.getId());
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPassword())) {
            throw new InvalidDataException("Mật khẩu hiện tại không đúng");
        }
        if(!req.getPassword().equals(req.getConfirmPassword())){
            throw new InvalidDataException("Password and confirm password do not match");
        }
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        invalidateSessions(user);
        userRepository.save(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = getUserById(id);
        UserStatus previousStatus = user.getStatus();
        // Đây là soft-delete. Giữ avatar ACTIVE để audit/khôi phục tài
        // khoản không bị mất file; chỉ release khi có hard-delete thật sự.
        user.setStatus(UserStatus.INACTIVE);
        invalidateSessions(user);
        userRepository.save(user);
        reservationAuditService.recordTarget(
                "USER", String.valueOf(user.getId()),
                ReservationAuditAction.USER_DEACTIVATED,
                "ADMIN vô hiệu hóa tài khoản",
                Map.of("status", previousStatus.name()),
                Map.of("status", UserStatus.INACTIVE.name()),
                Map.of("sessionsInvalidated", true, "role", user.getType().name()),
                UUID.randomUUID().toString(),
                null);
        log.info("delete user: {}", user);
    }


    private User getUserById(Long id){
        return userRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("User not found"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPasswordByAdmin(Long userId, AdminResetPasswordRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new InvalidDataException("Mật khẩu và xác nhận mật khẩu không khớp");
        }
        User user = getUserById(userId);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        invalidateSessions(user);
        userRepository.save(user);
        reservationAuditService.recordTarget(
                "USER",
                String.valueOf(user.getId()),
                ReservationAuditAction.PASSWORD_RESET_BY_ADMIN,
                "ADMIN đặt lại mật khẩu người dùng",
                null,
                null,
                Map.of("sessionsInvalidated", true),
                UUID.randomUUID().toString(),
                null);
        log.info("Admin reset password for userId={}", userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long verifyEmail(String secretCode) {
        String normalizedCode = secretCode == null ? "" : secretCode.trim();
        if (!StringUtils.hasText(normalizedCode)) {
            throw new InvalidDataException("Mã xác thực không hợp lệ hoặc đã hết hạn");
        }

        User user = userRepository
                .findByVerificationCodeAndStatusAndEmailVerifiedFalseAndVerificationExpiresAtAfter(
                        sha256(normalizedCode),
                        UserStatus.PENDING_VERIFICATION,
                        java.time.LocalDateTime.now())
                .orElseThrow(() -> new InvalidDataException("Mã xác thực không hợp lệ hoặc đã hết hạn"));

        user.setEmailVerified(true);
        user.setVerificationCode(null);
        user.setVerificationExpiresAt(null);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.saveAndFlush(user);
        eventPublisher.publishEvent(new UserEmailVerifiedEvent(user.getId()));
        log.info("Email verified for userId={}", user.getId());
        return user.getId();
    }

    @Override
    public void resendVerification(String email) {
        User user = userRepository.findByEmailIgnoreCase(email.trim()).orElse(null);
        if (user == null || user.isEmailVerified()
                || !UserStatus.PENDING_VERIFICATION.equals(user.getStatus())) {
            log.info("Verification resend requested for an unavailable account");
            return;
        }
        try {
            emailService.emailVerification(user.getEmail(), user.getFullName());
        } catch (Exception e) {
            throw new InvalidDataException("Không thể gửi email xác thực. Vui lòng thử lại sau");
        }
    }

    private void invalidateSessions(User user) {
        user.invalidateSessions();
        if (user.getId() != null && userTokenRepository.existsById(user.getId())) {
            userTokenRepository.deleteById(user.getId());
        }
    }
}
