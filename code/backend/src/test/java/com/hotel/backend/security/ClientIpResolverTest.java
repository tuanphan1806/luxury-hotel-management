package com.hotel.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void ignoresForwardedHeaderWhenForwardingIsDisabled() {
        ClientIpResolver resolver = new ClientIpResolver(false, "127.0.0.1,::1");
        MockHttpServletRequest request = request("127.0.0.1", "203.0.113.10");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void ignoresForgedForwardedHeaderFromAnUntrustedPeer() {
        ClientIpResolver resolver = new ClientIpResolver(true, "127.0.0.1,::1");
        MockHttpServletRequest request = request("198.51.100.20", "203.0.113.10");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void resolvesFirstUntrustedHopBehindExplicitlyTrustedProxies() {
        ClientIpResolver resolver = new ClientIpResolver(true, "127.0.0.1,10.0.0.2");
        MockHttpServletRequest request = request(
                "127.0.0.1",
                "203.0.113.10, 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void fallsBackToDirectPeerForMalformedForwardingChain() {
        ClientIpResolver resolver = new ClientIpResolver(true, "127.0.0.1");
        MockHttpServletRequest request = request("127.0.0.1", "not-an-ip");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
