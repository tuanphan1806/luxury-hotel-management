package com.hotel.backend.entity;

import com.hotel.backend.dto.request.RoomFolioSelection;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** The desk can recover this selection across devices while its payments settle. */
@Entity
@Table(name = "room_folio_collections")
@Getter @Setter @NoArgsConstructor
public class RoomFolioCollection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Pick selection;
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    public record Pick(List<Long> slotIds, RoomFolioSelection selection, Map<String, Long> appliedPayments) {
        public Pick(List<Long> slotIds, RoomFolioSelection selection) {
            this(slotIds, selection, Map.of());
        }
        public Pick {
            slotIds = slotIds.stream().sorted().toList();
            appliedPayments = appliedPayments == null ? Map.of() : Map.copyOf(appliedPayments);
        }
    }
}
