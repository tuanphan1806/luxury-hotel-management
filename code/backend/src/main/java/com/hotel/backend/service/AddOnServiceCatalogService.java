package com.hotel.backend.service;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.ReservationServiceOrigin;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.dto.request.AddOnServiceRequest;
import com.hotel.backend.dto.response.AddOnServiceResponse;
import com.hotel.backend.entity.AddOnService;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.AddOnServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AddOnServiceCatalogService {

    private final AddOnServiceRepository repository;
    private final MediaAssetService mediaAssetService;
    private final ReservationAuditService auditService;

    @Transactional(readOnly = true)
    public List<AddOnServiceResponse> listActive(ReservationServiceOrigin flow) {
        return repository.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .filter(service -> flow == null || isEnabledFor(service, flow))
                .map(AddOnServiceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AddOnServiceResponse getActive(Long id) {
        AddOnService service = find(id);
        if (!service.isActive()) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Dịch vụ không còn hoạt động");
        }
        return AddOnServiceResponse.from(service);
    }

    @Transactional(readOnly = true)
    public List<AddOnServiceResponse> listAll() {
        return repository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(AddOnServiceResponse::from)
                .toList();
    }

    @Transactional
    public AddOnServiceResponse create(AddOnServiceRequest request) {
        String code = normalizeCode(request.getCode());
        if (repository.existsByCodeIgnoreCase(code)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE, "Mã dịch vụ đã tồn tại");
        }
        AddOnService service = AddOnService.builder()
                .code(code)
                .name(normalizeRequired(request.getName(), "Tên dịch vụ không hợp lệ"))
                .nameEn(normalizeOptional(request.getNameEn()))
                .description(normalizeOptional(request.getDescription()))
                .descriptionEn(normalizeOptional(request.getDescriptionEn()))
                .category(request.getCategory())
                .price(normalizePrice(request.getPrice()))
                .pricingUnit(normalizePricingUnit(request.getPricingUnit()))
                .bookingEnabled(defaultTrue(request.getBookingEnabled()))
                .inStayEnabled(defaultTrue(request.getInStayEnabled()))
                .active(true)
                .sortOrder(normalizeSortOrder(request.getSortOrder()))
                .build();
        AddOnService saved = repository.saveAndFlush(service);
        saved.setImageUrl(mediaAssetService.replaceReference(
                null,
                normalizeOptional(request.getImageUrl()),
                UploadFolder.ADD_ON_SERVICES,
                MediaAssetOwnerType.ADD_ON_SERVICE,
                saved.getId()));
        repository.save(saved);
        audit(saved, ReservationAuditAction.SERVICE_CATALOG_CREATED,
                "Tạo dịch vụ thêm", null, snapshot(saved));
        return AddOnServiceResponse.from(saved);
    }

    @Transactional
    public AddOnServiceResponse update(Long id, AddOnServiceRequest request) {
        AddOnService service = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy dịch vụ"));
        String code = normalizeCode(request.getCode());
        if (repository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE, "Mã dịch vụ đã tồn tại");
        }
        Map<String, Object> before = snapshot(service);
        service.setCode(code);
        service.setName(normalizeRequired(request.getName(), "Tên dịch vụ không hợp lệ"));
        service.setNameEn(normalizeOptional(request.getNameEn()));
        service.setDescription(normalizeOptional(request.getDescription()));
        service.setDescriptionEn(normalizeOptional(request.getDescriptionEn()));
        service.setCategory(request.getCategory());
        service.setPrice(normalizePrice(request.getPrice()));
        service.setPricingUnit(normalizePricingUnit(request.getPricingUnit()));
        service.setBookingEnabled(defaultTrue(request.getBookingEnabled()));
        service.setInStayEnabled(defaultTrue(request.getInStayEnabled()));
        service.setSortOrder(normalizeSortOrder(request.getSortOrder()));
        service.setImageUrl(mediaAssetService.replaceReference(
                service.getImageUrl(),
                normalizeOptional(request.getImageUrl()),
                UploadFolder.ADD_ON_SERVICES,
                MediaAssetOwnerType.ADD_ON_SERVICE,
                service.getId()));
        AddOnService saved = repository.save(service);
        audit(saved, ReservationAuditAction.SERVICE_CATALOG_UPDATED,
                "Cập nhật dịch vụ thêm", before, snapshot(saved));
        return AddOnServiceResponse.from(saved);
    }

    @Transactional
    public AddOnServiceResponse setActive(Long id, boolean active) {
        AddOnService service = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy dịch vụ"));
        if (service.isActive() == active) {
            return AddOnServiceResponse.from(service);
        }
        Map<String, Object> before = snapshot(service);
        service.setActive(active);
        AddOnService saved = repository.save(service);
        audit(saved,
                active
                        ? ReservationAuditAction.SERVICE_CATALOG_REACTIVATED
                        : ReservationAuditAction.SERVICE_CATALOG_DEACTIVATED,
                active ? "Kích hoạt lại dịch vụ thêm" : "Ngừng cung cấp dịch vụ thêm",
                before,
                snapshot(saved));
        return AddOnServiceResponse.from(saved);
    }

    private AddOnService find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy dịch vụ"));
    }

    private boolean isEnabledFor(AddOnService service, ReservationServiceOrigin flow) {
        return flow == ReservationServiceOrigin.BOOKING_TIME
                ? service.isBookingEnabled()
                : service.isInStayEnabled();
    }

    private void audit(
            AddOnService service,
            ReservationAuditAction action,
            String details,
            Map<String, ?> before,
            Map<String, ?> after) {
        auditService.recordTarget(
                "ADD_ON_SERVICE",
                String.valueOf(service.getId()),
                action,
                details,
                before,
                after,
                null,
                null,
                null);
    }

    private Map<String, Object> snapshot(AddOnService service) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", service.getId());
        value.put("code", service.getCode());
        value.put("name", service.getName());
        value.put("price", service.getPrice());
        value.put("pricingUnit", service.getPricingUnit());
        value.put("category", service.getCategory());
        value.put("bookingEnabled", service.isBookingEnabled());
        value.put("inStayEnabled", service.isInStayEnabled());
        value.put("active", service.isActive());
        return value;
    }

    private String normalizeCode(String code) {
        return normalizeRequired(code, "Mã dịch vụ không hợp lệ").toUpperCase(Locale.ROOT);
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal normalizePrice(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Giá dịch vụ không hợp lệ");
        }
        try {
            return price.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Giá dịch vụ phải là số VND nguyên");
        }
    }

    private AddOnPricingUnit normalizePricingUnit(AddOnPricingUnit unit) {
        if (unit == null || unit == AddOnPricingUnit.PER_NIGHT) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    unit == null
                            ? "Đơn vị tính dịch vụ không hợp lệ"
                            : "PER_NIGHT là đơn vị cũ; hãy dùng PER_PACKAGE_CYCLE");
        }
        return unit;
    }

    private int normalizeSortOrder(Integer sortOrder) {
        if (sortOrder == null) return 0;
        if (sortOrder < 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Thứ tự hiển thị không hợp lệ");
        }
        return sortOrder;
    }

    private boolean defaultTrue(Boolean value) {
        return value == null || value;
    }
}
