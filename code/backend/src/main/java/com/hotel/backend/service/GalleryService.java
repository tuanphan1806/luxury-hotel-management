package com.hotel.backend.service;

import com.hotel.backend.dto.request.GalleryRequest;
import com.hotel.backend.dto.response.GalleryResponse;

import java.util.List;

public interface GalleryService {

    List<GalleryResponse> getAll();

    GalleryResponse getById(Long id);

    List<GalleryResponse> getByType(String type);

    List<GalleryResponse> search(String keyword);

    GalleryResponse create(GalleryRequest request);

    GalleryResponse update(Long id, GalleryRequest request);

    void delete(Long id);
}