package com.hotel.backend.service.Impl;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.CreateReviewRequest;
import com.hotel.backend.dto.request.UpdateReviewRequest;
import com.hotel.backend.dto.response.ReviewResponse;
import com.hotel.backend.dto.response.RoomTypeRatingResponse;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.Review;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.ReviewRepository;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j(topic = "REVIEW_SERVICE")
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository       reviewRepository;
    private final ReservationRepository  reservationRepository;
    private final UserRepository         userRepository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewResponse createReview(Long userId, CreateReviewRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.CUSTOMER_NOT_FOUND));

        if (user.getType() != UserType.CUSTOMER) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ tài khoản khách hàng mới có thể đánh giá kỳ nghỉ");
        }

        Reservation reservation = reservationRepository.findById(request.getReservationId())
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));

        // Chỉ chủ reservation mới được review
        if (reservation.getCustomerProfile() == null
                || reservation.getCustomerProfile().getLinkedUser() == null
                || !reservation.getCustomerProfile().getLinkedUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.REVIEW_NOT_OWNER);
        }

        // Chỉ review khi đã CHECKED_OUT — đảm bảo khách đã thực sự ở
        if (reservation.getStatus() != ReservationStatus.CHECKED_OUT) {
            throw new AppException(ErrorCode.REVIEW_RESERVATION_NOT_COMPLETED);
        }

        // Không cho review trùng cùng room type trong một reservation
        if (reviewRepository.existsByUserIdAndReservationIdAndRoomTypeId(
                userId, request.getReservationId(), request.getRoomTypeId())) {
            throw new AppException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        RoomType roomType = reservation.getRoomTypes().stream()
                .filter(rrt -> rrt.getRoomType().getId().equals(request.getRoomTypeId()))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.ROOM_TYPE_NOT_FOUND))
                .getRoomType();

        Review review = Review.builder()
                .user(user)
                .roomType(roomType)
                .reservation(reservation)
                .rating(request.getRating())
                .comment(request.getComment())
                .build();
        reviewRepository.save(review);

        log.info("Review created: userId={} reservationId={} roomTypeId={} rating={}",
                userId, request.getReservationId(), request.getRoomTypeId(), request.getRating());
        return ReviewResponse.from(review);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewResponse> getReviewsByRoomType(Long roomTypeId) {
        return reviewRepository.findByRoomTypeIdWithDetails(roomTypeId)
                .stream()
                .map(ReviewResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewResponse> getReviewsByUser(Long userId) {
        return reviewRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(ReviewResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RoomTypeRatingResponse getRoomTypeRating(Long roomTypeId) {
        Double avg = reviewRepository.getAverageRatingByRoomType(roomTypeId);
        long total = reviewRepository.countByRoomTypeId(roomTypeId);

        return RoomTypeRatingResponse.builder()
                .roomTypeId(roomTypeId)
                .averageRating(avg != null ? Math.round(avg * 10) / 10.0 : 0.0)
                .totalReviews(total)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewResponse updateReview(Long userId, Long reviewId, UpdateReviewRequest request) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));

        if (!review.getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.REVIEW_NOT_OWNER);
        }

        if (request.getRating() != null) {
            review.setRating(request.getRating());
        }
        if (request.getComment() != null) {
            review.setComment(request.getComment());
        }

        reviewRepository.save(review);
        log.info("Review updated: id={}", reviewId);
        return ReviewResponse.from(review);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteReview(Long userId, Long reviewId, boolean isAdmin) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));

        // Admin xóa được mọi review, khách chỉ xóa được review của mình
        if (!isAdmin && !review.getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.REVIEW_NOT_OWNER);
        }

        reviewRepository.delete(review);
        log.info("Review deleted: id={} by userId={}", reviewId, userId);
    }
}
