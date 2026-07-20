package com.hotel.backend.service;

import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.dto.response.SePayApiTransaction;
import com.hotel.backend.dto.response.SePayTransactionListResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

@Component
public class SePayApiClient {

    private static final DateTimeFormatter QUERY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final RestClient restClient;

    public SePayApiClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public List<SePayApiTransaction> listRecentIncoming(SePayConfig config) {
        return listIncoming(config, Instant.now()
                .minus(Duration.ofHours(config.getReconciliationLookbackHours())));
    }

    public List<SePayApiTransaction> listIncoming(SePayConfig config, Instant fromUtc) {
        return listByTransferType(config, fromUtc, "in");
    }

    /** Reconciliation fallback covers both money-in and refund money-out. */
    public List<SePayApiTransaction> listTransactions(SePayConfig config, Instant fromUtc) {
        List<SePayApiTransaction> result = new ArrayList<>();
        result.addAll(listByTransferType(config, fromUtc, "in"));
        result.addAll(listByTransferType(config, fromUtc, "out"));
        return result;
    }

    private List<SePayApiTransaction> listByTransferType(
            SePayConfig config,
            Instant fromUtc,
            String transferType) {
        String baseUrl = trimTrailingSlash(config.getApiBaseUrl());
        Instant safeFromUtc = fromUtc != null
                ? fromUtc
                : Instant.now().minus(Duration.ofHours(config.getReconciliationLookbackHours()));
        String fromDate = LocalDateTime.ofInstant(
                safeFromUtc, ZoneId.of("Asia/Ho_Chi_Minh")).format(QUERY_DATE);
        int maxPages = Math.max(1, Math.min(config.getReconciliationMaxPages(), 100));
        List<SePayApiTransaction> result = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            String url = UriComponentsBuilder.fromUriString(baseUrl + "/transactions")
                    .queryParam("transfer_type", transferType)
                    .queryParamIfPresent("bank_account_id", optional(config.getApiBankAccountId()))
                    .queryParam("transaction_date_from", fromDate)
                    .queryParam("transaction_date_sort", "asc")
                    .queryParam("page", page)
                    .queryParam("per_page", 100)
                    .queryParam("timestamp_format", "iso8601")
                    .build()
                    .encode()
                    .toUriString();

            SePayTransactionListResponse response = restClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiAccessToken().trim())
                    .retrieve()
                    .body(SePayTransactionListResponse.class);
            if (response == null || response.data() == null) break;
            result.addAll(response.data());
            boolean hasMore = response.meta() != null
                    && response.meta().pagination() != null
                    && response.meta().pagination().hasMore();
            if (!hasMore) break;
            // SePay API v2 giới hạn 3 request/giây.
            try {
                Thread.sleep(350L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return result;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private Optional<String> optional(String value) {
        return value == null || value.isBlank()
                ? Optional.empty() : Optional.of(value.trim());
    }
}
