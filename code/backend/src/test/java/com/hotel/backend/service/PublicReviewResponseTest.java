package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.dto.response.PublicReviewResponse;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.Review;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicReviewResponseTest {

    @Test
    void mapsOnlyVerifiedPublicReviewFields() {
        Review review = mock(Review.class);
        User user = mock(User.class);
        RoomType roomType = mock(RoomType.class);
        Reservation reservation = mock(Reservation.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 12, 9, 30);

        when(review.getId()).thenReturn(9L);
        when(review.getUser()).thenReturn(user);
        when(review.getRoomType()).thenReturn(roomType);
        when(review.getReservation()).thenReturn(reservation);
        when(review.getRating()).thenReturn(5);
        when(review.getComment()).thenReturn("Kỳ nghỉ tốt");
        when(review.getCreatedAt()).thenReturn(createdAt);
        when(user.getFullName()).thenReturn("Nguyễn Minh Anh");
        when(user.getImageUrl()).thenReturn("https://example.com/avatar.jpg");
        when(roomType.getId()).thenReturn(3L);
        when(roomType.getTypeName()).thenReturn("Phòng Deluxe");
        when(roomType.getTypeNameEn()).thenReturn("Deluxe Room");
        when(reservation.getStatus()).thenReturn(ReservationStatus.CHECKED_OUT);

        PublicReviewResponse response = PublicReviewResponse.from(review);

        assertEquals(9L, response.getId());
        assertEquals("Nguyễn Minh Anh", response.getUserName());
        assertEquals(5, response.getRating());
        assertEquals(createdAt, response.getCreatedAt());
        assertTrue(response.isVerifiedStay());

        Set<String> publicFields = Arrays.stream(PublicReviewResponse.class.getDeclaredFields())
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        assertFalse(publicFields.contains("userId"));
        assertFalse(publicFields.contains("reservationId"));
        assertFalse(publicFields.contains("reservationCode"));
    }

    @Test
    void doesNotMarkNonCompletedReservationAsVerifiedStay() {
        Review review = mock(Review.class);
        User user = mock(User.class);
        RoomType roomType = mock(RoomType.class);
        Reservation reservation = mock(Reservation.class);

        when(review.getUser()).thenReturn(user);
        when(review.getRoomType()).thenReturn(roomType);
        when(review.getReservation()).thenReturn(reservation);
        when(reservation.getStatus()).thenReturn(ReservationStatus.CONFIRMED);

        assertFalse(PublicReviewResponse.from(review).isVerifiedStay());
    }
}
