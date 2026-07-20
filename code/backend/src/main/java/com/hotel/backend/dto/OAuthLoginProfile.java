package com.hotel.backend.dto;

import com.hotel.backend.constant.OAuthProvider;

public record OAuthLoginProfile(
        OAuthProvider provider,
        String providerSubject,
        String email,
        boolean emailVerified,
        String hostedDomain,
        String fullName,
        String imageUrl
) {
}
