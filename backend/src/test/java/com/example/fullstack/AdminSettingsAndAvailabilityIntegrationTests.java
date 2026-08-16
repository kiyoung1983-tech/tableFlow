package com.example.fullstack;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@Import({
    TestcontainersConfiguration.class,
    AdminSettingsAndAvailabilityIntegrationTests.FixedClockConfiguration.class
})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminSettingsAndAvailabilityIntegrationTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminReadRequiresAuthenticationBeforeResourceLookup() throws Exception {
        mockMvc.perform(get("/api/admin/branches/{branchId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void adminCanCreateRestaurantBranchTableHoursAndBlock() throws Exception {
        var setup = createBookableBranch();

        mockMvc.perform(admin(get("/api/admin/restaurants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(setup.restaurantId().toString()));
        mockMvc.perform(admin(get("/api/admin/restaurants/{restaurantId}", setup.restaurantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TableFlow"));
        mockMvc.perform(admin(get(
                                "/api/admin/restaurants/{restaurantId}/branches",
                                setup.restaurantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(setup.branchId().toString()));
        mockMvc.perform(admin(get("/api/admin/branches/{branchId}", setup.branchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.defaultDurationMinutes").value(90));
        mockMvc.perform(admin(get("/api/admin/branches/{branchId}/tables", setup.branchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("T1"))
                .andExpect(jsonPath("$[1].name").value("T2"));
        mockMvc.perform(admin(get("/api/admin/branches/{branchId}/business-hours", setup.branchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].dayOfWeek").value(2));
        mockMvc.perform(admin(get("/api/admin/branches/{branchId}/booking-blocks", setup.branchId())
                        .queryParam("startsAt", "2026-08-18T00:00:00Z")
                        .queryParam("endsAt", "2026-08-19T00:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("창가 정비"));
    }

    @Test
    void overlappingBusinessHoursAreRejected() throws Exception {
        var setup = createRestaurantAndBranch();

        mockMvc.perform(admin(put("/api/admin/branches/{branchId}/business-hours", setup.branchId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"dayOfWeek":2,"opensAt":"11:00:00","closesAt":"14:00:00"},
                                  {"dayOfWeek":2,"opensAt":"13:30:00","closesAt":"16:00:00"}
                                ]}
                                """)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BOOKING_POLICY_VIOLATION"));
    }

    @Test
    void businessHoursCanBeReplacedWithTheSameStartTime() throws Exception {
        var setup = createBookableBranch();

        mockMvc.perform(admin(put("/api/admin/branches/{branchId}/business-hours", setup.branchId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"dayOfWeek":2,"opensAt":"11:00:00","closesAt":"14:00:00"}
                                ]}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].opensAt").value("11:00:00"));
    }

    @Test
    void updateRequiresExpectedVersion() throws Exception {
        var setup = createRestaurantAndBranch();

        mockMvc.perform(admin(patch("/api/admin/branches/{branchId}", setup.branchId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"역삼점\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.expectedVersion[0]").isNotEmpty());
    }

    @Test
    void availabilityUsesCapacityBusinessHoursAndHalfOpenBlocks() throws Exception {
        var setup = createBookableBranch();

        mockMvc.perform(get("/api/public/branches/{branchId}/availability", setup.branchId())
                        .queryParam("date", "2026-08-18")
                        .queryParam("partySize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.asOf").value("2026-08-17T00:00:00Z"))
                .andExpect(jsonPath("$.slots.length()").value(4))
                .andExpect(jsonPath("$.slots[0].startsAt").value("2026-08-18T02:00:00Z"))
                .andExpect(jsonPath("$.slots[0].endsAt").value("2026-08-18T03:30:00Z"))
                .andExpect(jsonPath("$.slots[0].availableTableCount").value(1))
                .andExpect(jsonPath("$.slots[1].startsAt").value("2026-08-18T02:30:00Z"))
                .andExpect(jsonPath("$.slots[1].availableTableCount").value(2));
    }

    @Test
    void availabilityRejectsDateOutsideBranchHorizon() throws Exception {
        var setup = createRestaurantAndBranch();

        mockMvc.perform(get("/api/public/branches/{branchId}/availability", setup.branchId())
                        .queryParam("date", "2026-09-17")
                        .queryParam("partySize", "2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BOOKING_POLICY_VIOLATION"));
    }

    @Test
    void publicBookingContextListsOnlyActiveBranchesWithPrivacyPolicy() throws Exception {
        var setup = createRestaurantAndBranch();

        mockMvc.perform(get("/api/public/booking-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privacyPolicyVersion").value("2026-08-16"))
                .andExpect(jsonPath("$.privacyRetentionDays").value(365))
                .andExpect(jsonPath("$.branches.length()").value(1))
                .andExpect(jsonPath("$.branches[0].id").value(setup.branchId().toString()))
                .andExpect(jsonPath("$.branches[0].restaurantName").value("TableFlow"))
                .andExpect(jsonPath("$.branches[0].name").value("강남점"))
                .andExpect(jsonPath("$.branches[0].maxPartySize").value(8));

        mockMvc.perform(admin(patch("/api/admin/branches/{branchId}", setup.branchId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"expectedVersion\":0}")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/booking-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branches.length()").value(0));
    }

    @Test
    void inactiveRestaurantRejectsDirectAvailabilityRequests() throws Exception {
        var setup = createBookableBranch();

        mockMvc.perform(admin(patch("/api/admin/restaurants/{restaurantId}", setup.restaurantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"expectedVersion\":0}")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/branches/{branchId}/availability", setup.branchId())
                        .queryParam("date", "2026-08-18")
                        .queryParam("partySize", "2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BOOKING_POLICY_VIOLATION"));
    }

    private BookableBranch createBookableBranch() throws Exception {
        var setup = createRestaurantAndBranch();
        var firstTableId = createTable(setup.branchId(), "T1", 2);
        createTable(setup.branchId(), "T2", 4);
        mockMvc.perform(admin(put("/api/admin/branches/{branchId}/business-hours", setup.branchId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"dayOfWeek":2,"opensAt":"11:00:00","closesAt":"14:00:00"}
                                ]}
                                """)))
                .andExpect(status().isOk());
        mockMvc.perform(admin(post("/api/admin/branches/{branchId}/booking-blocks", setup.branchId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "diningTableId":"%s",
                                  "startsAt":"2026-08-18T02:00:00Z",
                                  "endsAt":"2026-08-18T02:30:00Z",
                                  "reason":"창가 정비"
                                }
                                """.formatted(firstTableId))))
                .andExpect(status().isCreated());
        return new BookableBranch(setup.restaurantId(), setup.branchId());
    }

    private BookableBranch createRestaurantAndBranch() throws Exception {
        var restaurantResult = mockMvc.perform(admin(post("/api/admin/restaurants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TableFlow\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        var restaurantId = UUID.fromString(JsonPath.read(
                restaurantResult.getResponse().getContentAsString(), "$.id"));

        var branchResult = mockMvc.perform(admin(post(
                                "/api/admin/restaurants/{restaurantId}/branches", restaurantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"강남점",
                                  "address":"서울시 강남구",
                                  "timezone":"Asia/Seoul"
                                }
                                """)))
                .andExpect(status().isCreated())
                .andReturn();
        var branchId = UUID.fromString(JsonPath.read(
                branchResult.getResponse().getContentAsString(), "$.id"));
        return new BookableBranch(restaurantId, branchId);
    }

    private UUID createTable(UUID branchId, String name, int capacity) throws Exception {
        var result = mockMvc.perform(admin(post("/api/admin/branches/{branchId}/tables", branchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"capacity\":%d}".formatted(name, capacity))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.with(httpBasic("developer", "change-me-locally"));
    }

    record BookableBranch(UUID restaurantId, UUID branchId) {}

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
