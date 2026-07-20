package com.hotel.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SePayTransactionListResponse(
        String status,
        List<SePayApiTransaction> data,
        Meta meta) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(Pagination pagination) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(
            int total,
            @JsonProperty("per_page") int perPage,
            @JsonProperty("current_page") int currentPage,
            @JsonProperty("last_page") int lastPage,
            @JsonProperty("has_more") boolean hasMore) {}
}
