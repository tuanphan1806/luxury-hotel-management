package com.hotel.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomPageableFactoryTest {

    @Test
    void preservesExistingPageAndSortInterpretation() {
        Pageable descending = RoomPageableFactory.create("roomName:desc", 2, 10);

        assertEquals(1, descending.getPageNumber());
        assertEquals(10, descending.getPageSize());
        assertEquals(Sort.Direction.DESC, descending.getSort().getOrderFor("roomName").getDirection());

        Pageable fallback = RoomPageableFactory.create("invalid", 0, 20);
        assertEquals(0, fallback.getPageNumber());
        assertEquals(Sort.Direction.ASC, fallback.getSort().getOrderFor("id").getDirection());
    }
}
