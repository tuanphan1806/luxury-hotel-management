package com.hotel.backend.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record PublicReviewPageResponse(
        List<PublicReviewResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static PublicReviewPageResponse from(Page<PublicReviewResponse> reviews) {
        return new PublicReviewPageResponse(
                reviews.getContent(),
                reviews.getNumber(),
                reviews.getSize(),
                reviews.getTotalElements(),
                reviews.getTotalPages(),
                reviews.hasNext());
    }
}
