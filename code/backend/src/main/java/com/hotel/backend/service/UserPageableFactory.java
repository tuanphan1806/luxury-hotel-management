package com.hotel.backend.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preserves the current user list paging and sort request interpretation.
 */
public final class UserPageableFactory {

    private static final Pattern SORT_PATTERN = Pattern.compile("^(\\w+?)(:)(.*)");

    private UserPageableFactory() {
    }

    public static Pageable create(String sort, int page, int size) {
        Sort.Order order = new Sort.Order(Sort.Direction.ASC, "id");
        if (StringUtils.hasLength(sort)) {
            Matcher matcher = SORT_PATTERN.matcher(sort);
            if (matcher.find()) {
                String columnName = matcher.group(1);
                if (matcher.group(3).equalsIgnoreCase("asc")) {
                    order = new Sort.Order(Sort.Direction.ASC, columnName);
                } else {
                    order = new Sort.Order(Sort.Direction.DESC, columnName);
                }
            }
        }

        int pageNo = page > 0 ? page - 1 : 0;
        return PageRequest.of(pageNo, size, Sort.by(order));
    }
}
