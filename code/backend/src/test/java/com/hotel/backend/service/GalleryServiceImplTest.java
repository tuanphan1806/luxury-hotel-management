package com.hotel.backend.service;

import com.hotel.backend.constant.MediaAssetOwnerType;
import com.hotel.backend.entity.Gallery;
import com.hotel.backend.repository.GalleryRepository;
import com.hotel.backend.service.Impl.GalleryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GalleryServiceImplTest {

    @Mock GalleryRepository galleryRepository;
    @Mock MediaAssetService mediaAssetService;
    @Mock ReservationAuditService reservationAuditService;
    @InjectMocks GalleryServiceImpl service;

    @Test
    void deleteCommitsDatabaseRemovalBeforeReleasingMediaReference() {
        Gallery gallery = Gallery.builder()
                .title("Lobby")
                .imageUrl("https://cdn.example/gallery/lobby.webp")
                .build();
        gallery.setId(7L);
        when(galleryRepository.findById(7L)).thenReturn(Optional.of(gallery));

        service.delete(7L);

        InOrder order = inOrder(galleryRepository, mediaAssetService);
        order.verify(galleryRepository).delete(gallery);
        order.verify(galleryRepository).flush();
        order.verify(mediaAssetService).releaseReference(
                gallery.getImageUrl(),
                MediaAssetOwnerType.GALLERY,
                7L);
    }
}
