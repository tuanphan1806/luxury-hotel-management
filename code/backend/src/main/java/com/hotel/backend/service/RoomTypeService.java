package com.hotel.backend.service;

import com.hotel.backend.dto.request.RoomTypeRequest;
import com.hotel.backend.dto.response.RoomTypeResponse;

import java.math.BigDecimal;
import java.util.List;

public interface RoomTypeService {

    List<RoomTypeResponse> getAll();

    RoomTypeResponse getById(Long id);

    List<RoomTypeResponse> getByPriceRange(BigDecimal min, BigDecimal max);

    RoomTypeResponse create(RoomTypeRequest request);

    RoomTypeResponse update(Long id, RoomTypeRequest request);

    void delete(Long id);
}