package com.hotel.backend.entity;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

@Entity
@Table(name = "galleries")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Gallery extends AbstractEntity<Long> implements Serializable{

    @Column(name = "type")
    private String type;

    @Column(name = "title")
    private String title;
    @Column(name = "title_en")
    private String titleEn;
    @Column(name = "image_url", length = 500)
    private String imageUrl;

}
