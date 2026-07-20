package com.hotel.backend.service;
 
import com.hotel.backend.dto.request.CreateReviewRequest;
import com.hotel.backend.dto.request.UpdateReviewRequest;
import com.hotel.backend.dto.response.ReviewResponse;
import com.hotel.backend.dto.response.RoomTypeRatingResponse;
 
import java.util.List;
 
public interface ReviewService {
 
    // Khách tạo review — chỉ khi đã CHECKED_OUT và chưa review reservation đó
    ReviewResponse createReview(Long userId, CreateReviewRequest request);
 
    // Lấy review theo room type (public)
    List<ReviewResponse> getReviewsByRoomType(Long roomTypeId);
 
    // Lấy review của 1 user
    List<ReviewResponse> getReviewsByUser(Long userId);
 
    // Lấy điểm trung bình + tổng số review của room type
    RoomTypeRatingResponse getRoomTypeRating(Long roomTypeId);
 
    // Khách sửa review của mình
    ReviewResponse updateReview(Long userId, Long reviewId, UpdateReviewRequest request);
 
    // Khách/Admin xóa review
    void deleteReview(Long userId, Long reviewId, boolean isAdmin);
}