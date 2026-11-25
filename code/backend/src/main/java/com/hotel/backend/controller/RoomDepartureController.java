package com.hotel.backend.controller;

import com.hotel.backend.constant.RoomBillingMode;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.request.RoomFolioSelection;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.RoomFolio;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.IdempotencyService;
import com.hotel.backend.service.RoomDepartureService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/reservations/{id}/room-departures")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@RequiredArgsConstructor
public class RoomDepartureController {
    private final RoomDepartureService service;
    private final IdempotencyService idempotency;

    public record BatchQuote(@NotEmpty @Size(max = 100) List<@NotNull @Positive Long> slotIds,
                             @NotNull @Valid RoomFolioSelection selection, RoomBillingMode billingMode) {}
    public record BatchSettlement(@NotEmpty @Size(max = 100) List<@NotNull @Positive Long> slotIds,
                                  @NotNull @Valid RoomDepartureService.Command command) {}
    public record BatchTender(@NotEmpty @Size(max = 100) List<@NotNull @Positive Long> slotIds,
                              @NotNull @Valid RoomDepartureService.Tender tender) {}
    public record Release(@NotNull @PositiveOrZero Long expectedReceived) {}

    @PostMapping("/payments")
    public PaymentResponse collect(@PathVariable Long id, @Valid @RequestBody BatchTender batch,
            @AuthenticationPrincipal User operator, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var sorted = batch.slotIds().stream().sorted().toList();
        return idempotency.execute(key, "ROOM_FOLIO_PAYMENT", idempotency.actorScope(operator, null),
                Map.of("id", id, "slots", sorted, "tender", batch.tender()), "PAYMENT_TRANSACTION",
                () -> service.collect(id, sorted, batch.tender(), operator, request), PaymentResponse::getTransactionId,
                transactionId -> service.replayTender(transactionId, operator));
    }

    @PostMapping("/payments/{collectionId}/release")
    public ApiResponse<Boolean> release(@PathVariable Long id, @PathVariable Long collectionId, @Valid @RequestBody Release release) {
        service.releaseCollection(id, collectionId, release.expectedReceived());
        return ApiResponse.success("Đã đóng phiếu thu", true);
    }

    @PostMapping("/quote")
    public ApiResponse<RoomDepartureService.Quote> quoteBatch(@PathVariable Long id, @Valid @RequestBody BatchQuote batch) {
        return ApiResponse.success("Chi phí các căn đã chọn", service.preview(id, batch.slotIds(), batch.selection(), batch.billingMode()));
    }

    @PostMapping
    public ApiResponse<RoomFolio> departBatch(@PathVariable Long id, @Valid @RequestBody BatchSettlement batch,
            @AuthenticationPrincipal User operator, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return settle(id, batch.slotIds(), batch.command(), operator, key, request);
    }

    @GetMapping
    public ApiResponse<RoomDepartureService.Options> options(@PathVariable Long id) {
        return ApiResponse.success("Danh sách phòng", service.options(id));
    }

    @PostMapping("/{slotId}/quote")
    public ApiResponse<RoomDepartureService.Quote> quote(@PathVariable Long id, @PathVariable Long slotId,
            @Valid @RequestBody RoomFolioSelection selection) {
        return ApiResponse.success("Chi phí của căn", service.preview(id, List.of(slotId), selection, RoomBillingMode.SEPARATE));
    }

    @PostMapping("/{slotId}")
    public ApiResponse<RoomFolio> depart(@PathVariable Long id, @PathVariable Long slotId,
            @Valid @RequestBody RoomDepartureService.Command command, @AuthenticationPrincipal User operator,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return settle(id, List.of(slotId), command, operator, key, request);
    }

    private ApiResponse<RoomFolio> settle(Long id, List<Long> slotIds, RoomDepartureService.Command command, User operator,
                                          String key, HttpServletRequest request) {
        var sorted = slotIds.stream().sorted().toList();
        var result = idempotency.executeSnapshot(key, "ROOM_DEPARTURE", idempotency.actorScope(operator, null),
                Map.of("id", id, "slots", sorted, "command", command), "RESERVATION", String.valueOf(id),
                () -> service.depart(id, sorted, command, operator, request), RoomFolio.class);
        return ApiResponse.success("Đã trả căn", result);
    }
}
