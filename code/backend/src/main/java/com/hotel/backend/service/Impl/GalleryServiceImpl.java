package com.hotel.backend.service.Impl;

import com.hotel.backend.dto.request.GalleryRequest;
import com.hotel.backend.dto.response.GalleryResponse;
import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.entity.Gallery;
import com.hotel.backend.exception.DuplicateResourceException;
import com.hotel.backend.exception.ResourceNotFoundException;
import com.hotel.backend.repository.GalleryRepository;
import com.hotel.backend.service.GalleryService;
import com.hotel.backend.service.MediaAssetService;
import com.hotel.backend.service.ReservationAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GalleryServiceImpl implements GalleryService {

    private final GalleryRepository galleryRepository;
    private final MediaAssetService mediaAssetService;
    private final ReservationAuditService auditService;

    // ==================== READ ====================

    @Override
    @Transactional(readOnly = true)
    public List<GalleryResponse> getAll() {
        log.debug("Lấy tất cả galleries");

        return galleryRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public GalleryResponse getById(Long id) {
        log.debug("Lấy gallery id={}", id);
        return mapToResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GalleryResponse> getByType(String type) {
        log.debug("Lọc gallery theo type={}", type);

        return galleryRepository
                .findByTypeIgnoreCaseOrderByCreatedAtDesc(type)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<GalleryResponse> search(String keyword) {
        log.debug("Tìm kiếm gallery keyword={}", keyword);

        return galleryRepository
                .findByTitleContainingIgnoreCaseOrderByCreatedAtDesc(keyword)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ==================== WRITE ====================

    @Override
    @Transactional
    public GalleryResponse create(GalleryRequest request) {

        log.info("Tạo gallery: {}", request.getTitle());

        if (galleryRepository.existsByImageUrl(request.getImageUrl())) {
            throw new DuplicateResourceException(
                    "Gallery",
                    "imageUrl",
                    request.getImageUrl()
            );
        }

        Gallery gallery = Gallery.builder()
                .title(request.getTitle())
                .titleEn(request.getTitleEn())
                .type(request.getType())
                .imageUrl(request.getImageUrl())
                .build();

        Gallery saved = galleryRepository.save(gallery);
        saved.setImageUrl(mediaAssetService.replaceReference(
                null,
                saved.getImageUrl(),
                UploadFolder.GALLERY,
                MediaAssetOwnerType.GALLERY,
                saved.getId()));
        auditGallery(saved, ReservationAuditAction.GALLERY_CREATED,
                "Tạo ảnh thư viện", null, gallerySnapshot(saved));

        log.info("Đã tạo gallery id={}", saved.getId());

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public GalleryResponse update(Long id, GalleryRequest request) {

        log.info("Cập nhật gallery id={}", id);

        Gallery gallery = findOrThrow(id);
        Map<String, Object> oldValue = gallerySnapshot(gallery);
        String previousImageUrl = gallery.getImageUrl();

        if (galleryRepository.existsByImageUrlAndIdNot(
                request.getImageUrl(), id)) {

            throw new DuplicateResourceException(
                    "Gallery",
                    "imageUrl",
                    request.getImageUrl()
            );
        }

        gallery.setTitle(request.getTitle());
        gallery.setTitleEn(request.getTitleEn());
        gallery.setType(request.getType());
        gallery.setImageUrl(mediaAssetService.replaceReference(
                previousImageUrl,
                request.getImageUrl(),
                UploadFolder.GALLERY,
                MediaAssetOwnerType.GALLERY,
                gallery.getId()));

        Gallery saved = galleryRepository.save(gallery);
        auditGallery(saved, ReservationAuditAction.GALLERY_UPDATED,
                "Cập nhật ảnh thư viện", oldValue, gallerySnapshot(saved));

        log.info("Đã cập nhật gallery id={}", saved.getId());

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {

        log.info("Xóa gallery id={}", id);

        Gallery gallery = findOrThrow(id);
        Map<String, Object> oldValue = gallerySnapshot(gallery);

        mediaAssetService.releaseReference(
                gallery.getImageUrl(),
                MediaAssetOwnerType.GALLERY,
                gallery.getId());

        galleryRepository.delete(gallery);
        auditGallery(gallery, ReservationAuditAction.GALLERY_DELETED,
                "Xóa ảnh thư viện", oldValue, null);

        log.info("Đã xóa gallery id={}", id);
    }

   

    private Gallery findOrThrow(Long id) {

        return galleryRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Gallery", id));
    }

    private void auditGallery(
            Gallery gallery,
            ReservationAuditAction action,
            String details,
            Map<String, ?> oldValue,
            Map<String, ?> newValue) {
        auditService.recordTarget(
                "GALLERY",
                String.valueOf(gallery.getId()),
                action,
                details,
                oldValue,
                newValue,
                null,
                null,
                null);
    }

    private Map<String, Object> gallerySnapshot(Gallery gallery) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", gallery.getId());
        value.put("title", gallery.getTitle());
        value.put("titleEn", gallery.getTitleEn());
        value.put("type", gallery.getType());
        value.put("imageUrl", gallery.getImageUrl());
        return value;
    }

    private GalleryResponse mapToResponse(Gallery entity) {

        return GalleryResponse.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .titleEn(entity.getTitleEn())
                .type(entity.getType())
                .imageUrl(entity.getImageUrl())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
