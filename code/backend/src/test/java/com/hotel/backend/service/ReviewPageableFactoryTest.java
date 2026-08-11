package com.hotel.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewPageableFactoryTest {

    @Test
    void createsStableWhitelistedSorts() {
        Pageable newest = ReviewPageableFactory.create("newest", 0, 6);
        assertEquals(Sort.Direction.DESC, newest.getSort().getOrderFor("createdAt").getDirection());
        assertEquals(Sort.Direction.DESC, newest.getSort().getOrderFor("id").getDirection());

        Pageable highest = ReviewPageableFactory.create("highest", 1, 6);
        assertEquals(Sort.Direction.DESC, highest.getSort().getOrderFor("rating").getDirection());
        assertEquals(1, highest.getPageNumber());

        Pageable lowest = ReviewPageableFactory.create("lowest", 0, 6);
        assertEquals(Sort.Direction.ASC, lowest.getSort().getOrderFor("rating").getDirection());
    }

    @Test
    void clampsPageAndSizeAndFallsBackForUnknownSort() {
        Pageable safe = ReviewPageableFactory.create("reservation.password:desc", -5, 50_000);
        assertEquals(0, safe.getPageNumber());
        assertEquals(12, safe.getPageSize());
        assertEquals(Sort.Direction.DESC, safe.getSort().getOrderFor("createdAt").getDirection());
    }
}
