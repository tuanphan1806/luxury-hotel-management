package com.hotel.backend.service;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.AddOnServiceCategory;
import com.hotel.backend.constant.ReservationServiceOrigin;
import com.hotel.backend.entity.AddOnService;
import com.hotel.backend.repository.AddOnServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddOnServiceCatalogServiceTest {

    @Mock private AddOnServiceRepository repository;
    @Mock private MediaAssetService mediaAssetService;
    @Mock private ReservationAuditService auditService;

    private AddOnServiceCatalogService service;

    @BeforeEach
    void setUp() {
        service = new AddOnServiceCatalogService(repository, mediaAssetService, auditService);
    }

    @Test
    void publicBookingCatalogOnlyContainsActiveBookingEnabledServices() {
        AddOnService booking = catalog(1L, "BOOKING", true, false, true);
        AddOnService inStayOnly = catalog(2L, "IN_STAY", false, true, true);
        when(repository.findByActiveTrueOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(booking, inStayOnly));

        var result = service.listActive(ReservationServiceOrigin.BOOKING_TIME);

        assertThat(result).extracting(item -> item.getCode())
                .containsExactly("BOOKING");
    }

    @Test
    void publicInStayCatalogOnlyContainsActiveInStayEnabledServices() {
        AddOnService bookingOnly = catalog(1L, "BOOKING", true, false, true);
        AddOnService inStay = catalog(2L, "IN_STAY", false, true, true);
        when(repository.findByActiveTrueOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(bookingOnly, inStay));

        var result = service.listActive(ReservationServiceOrigin.IN_STAY);

        assertThat(result).extracting(item -> item.getCode())
                .containsExactly("IN_STAY");
    }

    @Test
    void adminCatalogRetainsInactiveServicesForReactivationAndHistory() {
        AddOnService active = catalog(1L, "ACTIVE", true, true, true);
        AddOnService inactive = catalog(2L, "INACTIVE", true, true, false);
        when(repository.findAllByOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(active, inactive));

        var result = service.listAll();

        assertThat(result).extracting(item -> item.isActive())
                .containsExactly(true, false);
    }

    private AddOnService catalog(
            Long id,
            String code,
            boolean bookingEnabled,
            boolean inStayEnabled,
            boolean active) {
        AddOnService item = AddOnService.builder()
                .code(code)
                .name(code)
                .category(AddOnServiceCategory.OTHER)
                .price(new BigDecimal("100000.00"))
                .pricingUnit(AddOnPricingUnit.PER_USE)
                .bookingEnabled(bookingEnabled)
                .inStayEnabled(inStayEnabled)
                .active(active)
                .sortOrder(id.intValue())
                .build();
        item.setId(id);
        return item;
    }
}
