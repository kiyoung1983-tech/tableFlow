package com.example.fullstack.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PublicApiRateLimitFilterTests {
    @Test
    void removesInactiveClientCountersWhenTheWindowAdvances() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-08-17T00:00:00Z"));
        var filter = new PublicApiRateLimitFilter(new ObjectMapper(), clock, 10, 10, 60);

        perform(filter, "192.0.2.1");
        perform(filter, "192.0.2.2");
        perform(filter, "192.0.2.3");
        assertEquals(3, filter.trackedClientCount());

        clock.advance(Duration.ofSeconds(121));
        perform(filter, "192.0.2.4");

        assertEquals(1, filter.trackedClientCount());
    }

    private static void perform(PublicApiRateLimitFilter filter, String remoteAddress)
            throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/public/reservations");
        request.setRemoteAddr(remoteAddress);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
