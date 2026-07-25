package com.hotel.backend.service;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.dto.request.RoomRequest;
import com.hotel.backend.dto.response.RoomPageResponse;
import com.hotel.backend.dto.response.RoomResponse;

import java.util.List;

public interface RoomCatalogUseCases {

    RoomResponse create(RoomRequest request);

    RoomResponse update(Long id, RoomRequest request);

    RoomResponse getById(Long id);

    List<RoomResponse> getAll();

    List<RoomResponse> search(String keyword, RoomStatus status, CleaningStatus cleaningStatus);

    RoomPageResponse findAll(String keyword, String sort, int page, int size);

    void delete(Long id);
}
