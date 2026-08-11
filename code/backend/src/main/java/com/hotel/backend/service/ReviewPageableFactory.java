package com.hotel.backend.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;

public final class ReviewPageableFactory {

    private static final int MAX_PAGE_SIZE = 12;

    private ReviewPageableFactory() {
    }

    public static Pageable create(String requestedSort, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        String sort = requestedSort == null ? "newest" : requestedSort.toLowerCase(Locale.ROOT);

        Sort safeSort = switch (sort) {
            case "highest" -> Sort.by(
                    Sort.Order.desc("rating"),
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"));
            case "lowest" -> Sort.by(
                    Sort.Order.asc("rating"),
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"));
            default -> Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"));
        };

        return PageRequest.of(safePage, safeSize, safeSort);
    }
}
