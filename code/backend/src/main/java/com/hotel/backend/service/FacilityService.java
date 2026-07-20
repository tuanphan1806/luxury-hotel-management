package com.hotel.backend.service;

import com.hotel.backend.dto.request.FacilityRequest;
import com.hotel.backend.dto.response.FacilityResponse;

import java.util.List;

public interface FacilityService {

    List<FacilityResponse> getAll();

    FacilityResponse getById(Long id);

    List<FacilityResponse> getByType(String type);

    List<FacilityResponse> search(String keyword);

    FacilityResponse create(FacilityRequest request);

    FacilityResponse update(Long id, FacilityRequest request);

    void delete(Long id);
}