package com.hotel.backend.repository;

import com.hotel.backend.constant.OAuthProvider;
import com.hotel.backend.entity.OAuthAccount;
import com.hotel.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderSubject(
        OAuthProvider provider,
        String providerSubject
    );

    boolean existsByProviderAndProviderSubject(
        OAuthProvider provider,
        String providerSubject
    );

    List<OAuthAccount> findAllByUser(User user);

    Optional<OAuthAccount> findByUserAndProvider(User user, OAuthProvider provider);

    boolean existsByUser(User user);
}
