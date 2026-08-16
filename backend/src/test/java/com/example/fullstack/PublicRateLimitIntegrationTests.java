package com.example.fullstack;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
    "app.public-rate-limit.enabled=true",
    "app.public-rate-limit.create-limit=1",
    "app.public-rate-limit.management-limit=1",
    "app.public-rate-limit.window-seconds=60"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicRateLimitIntegrationTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void sensitivePublicRoutesHaveSeparateFixedWindowLimits() throws Exception {
        mockMvc.perform(post("/api/public/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/public/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(get("/api/public/reservations/AAAAAAAAAA"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/reservations/BBBBBBBBBB"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }
}
