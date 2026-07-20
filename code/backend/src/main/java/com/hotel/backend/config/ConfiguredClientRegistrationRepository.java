package com.hotel.backend.config;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client registration repository that may be empty. Spring's in-memory
 * implementation rejects an empty list, while this application must start when
 * OAuth credentials have not been configured yet.
 */
public final class ConfiguredClientRegistrationRepository
        implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    private final Map<String, ClientRegistration> registrations;

    public ConfiguredClientRegistrationRepository(List<ClientRegistration> registrations) {
        Map<String, ClientRegistration> byId = new LinkedHashMap<>();
        registrations.forEach(registration -> byId.put(registration.getRegistrationId(), registration));
        this.registrations = Map.copyOf(byId);
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return registrations.get(registrationId);
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return registrations.values().iterator();
    }
}
