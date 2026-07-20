package com.hotel.backend.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.hotel.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserServiceDetail implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalizedLogin = username == null ? "" : username.trim();
        return userRepository.findByUsernameIgnoreCase(normalizedLogin)
                .or(() -> userRepository.findByEmailIgnoreCase(normalizedLogin))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
