package com.hotel.backend.entity;



import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;

import jakarta.persistence.*;
import lombok.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.time.LocalDateTime;


@Entity
@Table(name = "users")
@Getter 
@Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class User extends AbstractEntity<Long> implements UserDetails,Serializable{

    @Column(name = "full_name", nullable = false)
    private String fullName;
    @Column(name = "username",nullable = false,unique = true)
    private String username;
    @Column(nullable = false,unique = true)
    private String email;

    private String verificationCode;

    @Column(name = "verification_expires_at")
    private LocalDateTime verificationExpiresAt;

    @Builder.Default
    @Column(name = "security_version", nullable = false)
    private long securityVersion = 0L;

    @Column(name = "password_reset_token_hash", length = 64)
    private String passwordResetTokenHash;

    @Column(name = "password_reset_expires_at")
    private LocalDateTime passwordResetExpiresAt;

    @Builder.Default
    private boolean emailVerified = false;
    
    @Column(nullable = true)
    private String password;
    @Column(unique = true)
    private String phone;
    
    @Column(columnDefinition = "TEXT")
    private String address;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private UserType type= UserType.CUSTOMER; // metadata only — không dùng cho @PreAuthorize

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private UserStatus status= UserStatus.PENDING_VERIFICATION;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @JsonIgnore
    @OneToOne(mappedBy = "linkedUser", fetch = FetchType.LAZY)
    private CustomerProfile customerProfile;

    @Override 
    public Collection<? extends GrantedAuthority> getAuthorities(){
        //lay role,role name, add role name
        // return groups.stream()
        // .flatMap(g -> g.getRoles().stream())
        // .map(r -> new SimpleGrantedAuthority(r.getName())) // "ADMIN,STAFF,CUSTOMER"
        // .collect(Collectors.toSet());
        return Set.of(new SimpleGrantedAuthority("ROLE_" + type.name()));
    }

    @Override
    public boolean isAccountNonExpired(){
        return UserDetails.super.isAccountNonExpired();
    }

    @Override
    public boolean isCredentialsNonExpired(){
        return UserDetails.super.isCredentialsNonExpired();
    }

    @Override
    public boolean isEnabled(){
        return UserStatus.ACTIVE.equals(status);
    }

    public void invalidateSessions() {
        securityVersion++;
    }

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "group_has_user",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "group_id")
    )
    @Builder.Default
    private Set<Group> groups = new HashSet<>();

}
