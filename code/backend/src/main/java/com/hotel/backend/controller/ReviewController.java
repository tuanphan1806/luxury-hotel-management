package com.hotel.backend.controller;
 
import com.hotel.backend.dto.request.CreateReviewRequest;
import com.hotel.backend.dto.request.UpdateReviewRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.ReviewResponse;
import com.hotel.backend.dto.response.PublicReviewPageResponse;
import com.hotel.backend.dto.response.PublicReviewResponse;
import com.hotel.backend.dto.response.RoomTypeRatingResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
 
import java.util.List;
 
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
@Slf4j(topic = "REVIEW-CONTROLLER")
@Validated
public class ReviewController {
 
    private final ReviewService reviewService;
 
    // ── Public: xem review theo room type ────────────────────────────────────
    @Operation(summary = "Get Room Type reviews", description = "Compatibility endpoint returning the latest public reviews; use the paged endpoint for full browsing")
    @GetMapping("/room-type/{roomTypeId}")
    public ApiResponse<List<PublicReviewResponse>> getReviewsByRoomType(@PathVariable Long roomTypeId) {
        return ApiResponse.success(reviewService.getReviewsByRoomType(roomTypeId));
    }

    @Operation(summary = "Get paged Room Type reviews", description = "API retrieve a safe, paginated public review list")
    @GetMapping("/room-type/{roomTypeId}/page")
    public ApiResponse<PublicReviewPageResponse> getReviewPageByRoomType(
            @PathVariable Long roomTypeId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "6") @Min(1) @Max(12) int size,
            @RequestParam(defaultValue = "newest") String sort) {
        return ApiResponse.success(reviewService.getReviewPageByRoomType(roomTypeId, page, size, sort));
    }
 
    // ── Public: điểm trung bình + tổng số review ─────────────────────────────
    @Operation(summary = "Get Room Type rating", description = "API retrieve average rating and review count for a room type")
    @GetMapping("/room-type/rating/{roomTypeId}")
    public ApiResponse<RoomTypeRatingResponse> getRoomTypeRating(@PathVariable Long roomTypeId) {
        return ApiResponse.success(reviewService.getRoomTypeRating(roomTypeId));
    }
 
    // ── Customer: tạo review ──────────────────────────────────────────────────
    @Operation(summary = "Create Review", description = "API create a review for the current customer")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReviewResponse> createReview(@Valid @RequestBody CreateReviewRequest request) {
        Long userId = getCurrentUserId();
        return ApiResponse.success("Đánh giá thành công",
                reviewService.createReview(userId, request));
    }
 
    // ── Customer: xem review của mình ────────────────────────────────────────
    @Operation(summary = "Get my Reviews", description = "API retrieve reviews of the current customer")
    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ReviewResponse>> getMyReviews() {
        return ApiResponse.success(reviewService.getReviewsByUser(getCurrentUserId()));
    }
 
    // ── Customer: sửa review của mình ────────────────────────────────────────
    @Operation(summary = "Update Review", description = "API update a review of the current customer")
    @PatchMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReviewResponse> updateReview(
            @PathVariable Long id,
            @Valid @RequestBody UpdateReviewRequest request) {
        return ApiResponse.success("Cập nhật đánh giá thành công",
                reviewService.updateReview(getCurrentUserId(), id, request));
    }
 
    // ── Customer/Admin: xóa review ────────────────────────────────────────────
    @Operation(summary = "Delete Review", description = "API delete a review by id for the current customer or admin")
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> deleteReview(@PathVariable Long id) {
        User currentUser = getCurrentUser();
        boolean isAdmin = currentUser.getType().name().equals("ADMIN");
        reviewService.deleteReview(currentUser.getId(), id, isAdmin);
        return ApiResponse.success("Xóa đánh giá thành công", null);
    }
 
    // ── Helpers ────────────────────────────────────────────────────────────────
    private User getCurrentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return (User) authentication.getPrincipal();
    }
 
    private Long getCurrentUserId() {
        return getCurrentUser().getId();
    }
}
