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
import jakarta.persistence.JoinColumn;
@Entity
@Table(name = "roles")
@Getter @Setter @NoArgsConstructor
public class Role extends AbstractEntity<Long> implements Serializable {

    @Column(nullable = false, unique = true, length = 50)
    private String name; // e.g. ROLE_ADMIN, ROLE_RECEPTIONIST

    @ManyToMany(mappedBy = "roles")
    private Set<Group> groups = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "role_has_permission",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions = new HashSet<>();
}
