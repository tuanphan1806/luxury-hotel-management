package com.hotel.backend.service;

import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.dto.request.CreateWalkInReservationRequest;
import com.hotel.backend.dto.request.CustomerProfileRequest;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.CustomerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReservationCustomerProfileService {

    private final CustomerProfileRepository customerProfileRepository;

    public CustomerProfile findOrCreateOnlineCustomerProfile(User user) {
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

    public CustomerProfile resolveWalkInCustomerProfile(CreateWalkInReservationRequest request) {
        return resolveWalkInCustomerProfile(request.getCustomerProfileId(), request.getCustomer());
    }

    public CustomerProfile resolveWalkInCustomerProfile(
            Long customerProfileId,
            CustomerProfileRequest customer) {
        if (customerProfileId != null) {
            return customerProfileRepository.findById(customerProfileId)
                    .orElseThrow(() -> new AppException(ErrorCode.CUSTOMER_NOT_FOUND));
        }

        return resolveCustomerProfileFromRequest(
                customer,
                CustomerProfileSource.WALK_IN,
                "customerProfileId hoặc thông tin khách vãng lai là bắt buộc",
                "Tên khách vãng lai không được để trống khi tạo hồ sơ mới",
                true);
    }

    public CustomerProfile resolveGuestOnlineCustomerProfile(CustomerProfileRequest customer) {
        if (customer == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thông tin khách đặt phòng là bắt buộc khi chưa đăng nhập");
        }
        if (!hasText(customer.getEmail())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Email là bắt buộc khi đặt phòng online");
        }
        validateCustomerProfile(customer, "Tên khách đặt phòng không được để trống khi chưa đăng nhập");
        // Không cập nhật profile tìm bằng email/số điện thoại từ một request public.
        // Sau khi xác minh email, luồng claim sẽ liên kết/ghép các profile cùng email.
        return customerProfileRepository.save(CustomerProfile.builder()
                .fullName(customer.getFullName().trim())
                .phone(trimToNull(customer.getPhone()))
                .email(customer.getEmail().trim())
                .address(trimToNull(customer.getAddress()))
                .idCardNumber(trimToNull(customer.getIdCardNumber()))
                .source(CustomerProfileSource.ONLINE)
                .build());
    }

    public void validateCustomerProfile(CustomerProfileRequest customer, String missingNameMessage) {
        if (customer == null || !hasText(customer.getFullName())) {
            throw new AppException(ErrorCode.INVALID_REQUEST, missingNameMessage);
        }
        if (customer.getFullName().trim().length() > 150) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Tên khách không được quá 150 ký tự");
        }
        if (hasText(customer.getPhone()) && !customer.getPhone().trim().matches("^(0|\\+84)[0-9]{9,10}$")) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số điện thoại không hợp lệ");
        }
        if (hasText(customer.getEmail()) && customer.getEmail().trim().length() > 255) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Email không được quá 255 ký tự");
        }
        if (hasText(customer.getIdCardNumber()) && customer.getIdCardNumber().trim().length() > 50) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số giấy tờ không được quá 50 ký tự");
        }
    }

    private CustomerProfile resolveCustomerProfileFromRequest(
            CustomerProfileRequest customer,
            CustomerProfileSource source,
            String missingCustomerMessage,
            String missingNameMessage,
            boolean allowLinkedProfileReuse) {
        if (customer == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST, missingCustomerMessage);
        }
        validateCustomerProfile(customer, missingNameMessage);

        // Chỉ dùng lại hồ sơ khi tất cả định danh được gửi lên cùng khớp một profile.
        // Không lấy profile chỉ vì trùng một trường rồi ghi đè các trường còn lại.
        if (hasText(customer.getIdCardNumber())) {
            var existing = customerProfileRepository.findFirstByIdCardNumber(customer.getIdCardNumber().trim());
            if (existing.isPresent()
                    && canReuseCustomerProfile(existing.get(), allowLinkedProfileReuse)
                    && matchesProvidedIdentity(existing.get(), customer)) {
                return updateCustomerProfile(existing.get(), customer, source);
            }
        }
        return customerProfileRepository.save(CustomerProfile.builder()
                .fullName(customer.getFullName().trim())
                .phone(trimToNull(customer.getPhone()))
                .email(trimToNull(customer.getEmail()))
                .address(trimToNull(customer.getAddress()))
                .idCardNumber(trimToNull(customer.getIdCardNumber()))
                .source(source)
                .build());
    }

    private boolean matchesProvidedIdentity(CustomerProfile profile, CustomerProfileRequest request) {
        return (!hasText(request.getPhone()) || request.getPhone().trim().equals(profile.getPhone()))
                && (!hasText(request.getEmail()) || request.getEmail().trim().equalsIgnoreCase(profile.getEmail()))
                && (!hasText(request.getIdCardNumber())
                    || request.getIdCardNumber().trim().equals(profile.getIdCardNumber()));
    }

    private boolean canReuseCustomerProfile(CustomerProfile profile, boolean allowLinkedProfileReuse) {
        return allowLinkedProfileReuse || profile.getLinkedUser() == null;
    }

    private CustomerProfile updateCustomerProfile(
            CustomerProfile profile,
            CustomerProfileRequest request,
            CustomerProfileSource source) {
        if (hasText(request.getFullName())) {
            profile.setFullName(request.getFullName().trim());
        }
        if (hasText(request.getPhone())) {
            profile.setPhone(request.getPhone().trim());
        }
        if (hasText(request.getEmail())) {
            profile.setEmail(request.getEmail().trim());
        }
        if (hasText(request.getAddress())) {
            profile.setAddress(request.getAddress().trim());
        }
        if (hasText(request.getIdCardNumber())) {
            profile.setIdCardNumber(request.getIdCardNumber().trim());
        }
        if (profile.getSource() == null) {
            profile.setSource(source);
        }
        return customerProfileRepository.save(profile);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
