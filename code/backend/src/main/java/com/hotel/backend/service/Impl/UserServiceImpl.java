package com.hotel.backend.service.Impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.CustomerProfileSource;
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
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.UserTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.hotel.backend.entity.User;
import com.hotel.backend.entity.CustomerProfile;
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
    private final CustomerProfileRepository customerProfileRepository;
    private final UserTokenRepository userTokenRepository;

    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final ApplicationEventPublisher eventPublisher;
    private final MediaAssetService mediaAssetService;
    private final ReservationAuditService reservationAuditService;

    @Override
    public UserPageResponse findAll(String keyword,String sort, int page,int size) {
        

        //Sorting
        Sort.Order order= new Sort.Order(Sort.Direction.ASC, "id");
        if (StringUtils.hasLength(sort)) {
            Pattern pattern= Pattern.compile("^(\\w+?)(:)(.*)");//ten cot:asc desc
            Matcher matcher=pattern.matcher(sort);
            if (matcher.find()) {
                String columnName =matcher.group(1);
                if (matcher.group(3).equalsIgnoreCase("asc")) {
                    order= new Sort.Order(Sort.Direction.ASC, columnName);
                } else{
                    order= new Sort.Order(Sort.Direction.DESC, columnName);
                }
            }
        }

        // xu ly TH FE muon bat dau voi page =1
        int pageNo=0;
        if (page>0) {
            pageNo=page-1;
        }
        //Paging
        Pageable pageable= PageRequest.of(pageNo, size, Sort.by(order));

        Page<User> entityPage=null;
        if (StringUtils.hasLength(keyword)) {
            keyword="%"+keyword.toLowerCase()+"%";
            entityPage=userRepository.searchByKeyword(keyword,pageable);
        }else{
            entityPage= userRepository.findAll(pageable);
        }

        UserPageResponse response= getUserPageResponse(pageNo, size, entityPage);
        return response;
    }
    
    @Override
    public UserResponse findById(Long id) {
        User user = getUserById(id);
        return UserResponse.builder()
                .id(id)
                .fullName(user.getFullName())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .address(user.getAddress())
                .type(user.getType())
                .status(user.getStatus())
                .imageUrl(user.getImageUrl())
                .build();
    }


    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long save(UserCreationRequest req) {
        log.info("Saving user", req.getUsername());

        String username = normalizeUsername(req.getUsername());
        String email = normalizeEmail(req.getEmail());

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
        ensureCustomerProfileForUser(user);
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getId()));
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Long createUserWithType(UserCreationWithTypeRequest req) {
        log.info("Saving user {} with type {}", req.getUsername(),req.getType());

        String username = normalizeUsername(req.getUsername());
        String email = normalizeEmail(req.getEmail());

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
            ensureCustomerProfileForUser(user);
        }
        log.info("User create with type {} successfully",user.getType());
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
        user.setUsername(normalizeUsername(req.getUsername()));
        user.setEmail(normalizeEmail(req.getEmail()));
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
        syncLinkedCustomerProfile(user);
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
        if (userRepository.existsByUsernameIgnoreCaseAndIdNot(normalizeUsername(req.getUsername()), id)) {
            throw new DuplicateResourceException("User", "username", req.getUsername());
        }
        if (userRepository.existsByEmailIgnoreCaseAndIdNot(normalizeEmail(req.getEmail()), id)) {
            throw new DuplicateResourceException("User", "email", req.getEmail());
        }
        if (userRepository.existsByPhoneAndIdNot(req.getPhone(), id)) {
            throw new DuplicateResourceException("User", "phone", req.getPhone());
        }
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
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
        reservationAuditService.recordTarget(
                "USER",
                String.valueOf(user.getId()),
                ReservationAuditAction.PASSWORD_CHANGED,
                "Người dùng đổi mật khẩu",
                null,
                null,
                Map.of("sessionsInvalidated", true),
                UUID.randomUUID().toString(),
                null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = getUserById(id);
        // Đây là soft-delete. Giữ avatar ACTIVE để audit/khôi phục tài
        // khoản không bị mất file; chỉ release khi có hard-delete thật sự.
        user.setStatus(UserStatus.INACTIVE);
        invalidateSessions(user);
        userRepository.save(user);
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

    private CustomerProfile ensureCustomerProfileForUser(User user) {
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

    private void syncLinkedCustomerProfile(User user) {
        customerProfileRepository.findByLinkedUserId(user.getId()).ifPresent(profile -> {
            profile.setFullName(user.getFullName());
            profile.setPhone(user.getPhone());
            profile.setEmail(user.getEmail());
            profile.setAddress(user.getAddress());
            customerProfileRepository.save(profile);
        });
    }


    //convert user entity to userResponse
    private static UserPageResponse getUserPageResponse (int page,int size, Page<User> users){
        List<UserResponse> userList = users.stream()
                .map(entity -> UserResponse.builder()
                .id(entity.getId())
                .fullName(entity.getFullName())
                .username(entity.getUsername())
                .email(entity.getEmail())
                .phone(entity.getPhone())
                .address(entity.getAddress())
                .type((entity.getType()))
                .status(entity.getStatus())
                .imageUrl(entity.getImageUrl())
                .build()
                ).toList();

        UserPageResponse response= new UserPageResponse();
        response.setPageNumber(page);
        response.setPageSize(size);
        response.setTotalElements(users.getTotalElements());
        response.setTotalPages(users.getTotalPages());
        response.setUsers(userList);
        return response;
    }
    

    @Transactional(rollbackFor = Exception.class)
    public void verifyEmail(String secretCode) {
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
