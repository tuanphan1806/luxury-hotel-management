package com.hotel.backend.entity;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "permissions")
@Getter @Setter @NoArgsConstructor
public class Permission extends AbstractEntity<Long> implements Serializable {

    @Column(nullable = false, unique = true, length = 100)
    private String name; // e.g. ROOM_CREATE, BOOKING_VIEW

    @ManyToMany(mappedBy = "permissions")
    private Set<Role> roles = new HashSet<>();
}
