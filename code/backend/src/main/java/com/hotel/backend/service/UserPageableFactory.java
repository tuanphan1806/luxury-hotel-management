package com.hotel.backend.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * Preserves the current user list paging and sort request interpretation.
 */
public final class UserPageableFactory {

    private static final Pattern SORT_PATTERN = Pattern.compile("^(\\w+?)(:)(.*)");
    private static final Set<String> ALLOWED_SORTS = Set.of(
            "id", "fullName", "username", "email", "phone", "type",
            "status", "createdAt", "updatedAt");
    private static final int MAX_PAGE_SIZE = 100;

    private UserPageableFactory() {
    }

    public static Pageable create(String sort, int page, int size) {
        Sort.Order order = new Sort.Order(Sort.Direction.ASC, "id");
        if (StringUtils.hasLength(sort)) {
            Matcher matcher = SORT_PATTERN.matcher(sort);
            if (matcher.find() && ALLOWED_SORTS.contains(matcher.group(1))) {
                String columnName = matcher.group(1);
                if (matcher.group(3).equalsIgnoreCase("asc")) {
                    order = new Sort.Order(Sort.Direction.ASC, columnName);
                } else {
                    order = new Sort.Order(Sort.Direction.DESC, columnName);
                }
            }
        }

        int pageNo = page > 0 ? page - 1 : 0;
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(pageNo, pageSize, Sort.by(order));
    }
}
