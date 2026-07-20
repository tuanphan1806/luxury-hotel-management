package com.hotel.backend.service;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP client nhỏ, có timeout rõ ràng, dành riêng cho QueryDR/Refund của VNPay. */
@Component
public class VNPayApiClient {

    private final RestClient restClient;

    public VNPayApiClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public Map<String, String> postJson(String url, Map<String, Object> payload) {
        Map<String, Object> response = restClient.post()
                .uri(url)
                .body(payload)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (response == null) {
            throw new IllegalStateException("VNPay trả về response rỗng");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        response.forEach((key, value) -> normalized.put(key, value != null ? String.valueOf(value) : ""));
        return normalized;
    }
}
