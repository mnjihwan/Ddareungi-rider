package com.ddareungi.status;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.exception.GlobalExceptionHandler;
import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.station.domain.RealtimeSummary;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A4: GET /api/v1/status/realtime (spec 15). */
class RealtimeStatusControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private StationSearchPort port;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        port = mock(StationSearchPort.class);
        var clock = new DefaultListableBeanFactory();
        clock.registerSingleton("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new RealtimeStatusController(port))
                .setControllerAdvice(new GlobalExceptionHandler(clock.getBeanProvider(Clock.class)))
                .addFilters(new RequestIdFilter()).build();
    }

    @ParameterizedTest
    @CsvSource({
            // total, fresh, stale, unavailable, expected
            "3, 3, 0, 0, NORMAL",
            "3, 2, 1, 0, DELAYED",
            "3, 1, 0, 2, DELAYED",
            "3, 0, 1, 2, DELAYED",
            "3, 0, 0, 3, UNAVAILABLE",
            "0, 0, 0, 0, UNAVAILABLE"})
    void statusFollowsTheCounts(int total, int fresh, int stale, int unavailable, RealtimeStatusResponse.Status expected) {
        var summary = new RealtimeSummary(total, fresh, stale, unavailable, null, NOW);
        assertThat(RealtimeStatusResponse.from(summary).status()).isEqualTo(expected);
    }

    @Test
    void lastUpdatedAtAndAgeComeFromTheLastSuccessTime() throws Exception {
        when(port.realtimeSummary()).thenReturn(
                new RealtimeSummary(4, 2, 1, 1, NOW.minusSeconds(90).minusMillis(900), NOW));

        mvc.perform(get("/api/v1/status/realtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DELAYED"))
                .andExpect(jsonPath("$.data.lastUpdatedAt").value("2026-11-16T02:58:29.100Z"))
                // floor(90.9) = 90
                .andExpect(jsonPath("$.data.ageSeconds").value(90))
                .andExpect(jsonPath("$.data.totalStationCount").value(4))
                .andExpect(jsonPath("$.data.freshStationCount").value(2))
                .andExpect(jsonPath("$.data.staleStationCount").value(1))
                .andExpect(jsonPath("$.data.unavailableStationCount").value(1));
    }

    @Test
    void unknownLastSuccessGivesNullsAndUnavailableDataIsStill200() throws Exception {
        when(port.realtimeSummary()).thenReturn(new RealtimeSummary(2, 0, 0, 2, null, NOW));

        mvc.perform(get("/api/v1/status/realtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.lastUpdatedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.ageSeconds").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void futureLastSuccessHasAgeZero() {
        var summary = new RealtimeSummary(1, 1, 0, 0, NOW.plusSeconds(10), NOW);
        assertThat(RealtimeStatusResponse.from(summary).ageSeconds()).isZero();
    }

    @Test
    void summaryFailureIs503RealtimeDataUnavailable() throws Exception {
        when(port.realtimeSummary()).thenThrow(new DataAccessResourceFailureException("db down"))
                .thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE));
        mvc.perform(get("/api/v1/status/realtime"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("REALTIME_DATA_UNAVAILABLE"));

        mvc.perform(get("/api/v1/status/realtime"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("REALTIME_DATA_UNAVAILABLE"));
    }
}
