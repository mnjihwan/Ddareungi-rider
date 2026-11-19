package com.ddareungi.status;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.station.domain.RealtimeSummary;
import com.ddareungi.station.domain.StationSearchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate realtime data status (spec 15). Always 200 while the summary can be computed, even when the data status
 * is UNAVAILABLE. When the summary itself cannot be computed (catalog read failure, or a 5xx {@link ApiException}
 * such as an unloaded catalog) the counts are not guessed: 503 {@code REALTIME_DATA_UNAVAILABLE}.
 * The existing {@code GET /api/v1/status} (data mode) stays in {@code global.response.StatusController}.
 */
@RestController
public class RealtimeStatusController {
    private static final Logger log = LoggerFactory.getLogger(RealtimeStatusController.class);
    private final StationSearchPort port;

    public RealtimeStatusController(StationSearchPort port) {
        this.port = port;
    }

    @GetMapping("/api/v1/status/realtime")
    public ApiResponse<RealtimeStatusResponse> realtime() {
        RealtimeSummary summary;
        try {
            summary = port.realtimeSummary();
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        } catch (ApiException exception) {
            if (!exception.errorCode().status().is5xxServerError()) throw exception;
            throw unavailable(exception);
        }
        return ApiResponse.ok(RealtimeStatusResponse.from(summary));
    }

    /** ApiException has no cause constructor, so the cause is logged here (the handler logs the 503 itself). */
    private static ApiException unavailable(Exception cause) {
        log.warn("Realtime status summary failed", cause);
        return new ApiException(ErrorCode.REALTIME_DATA_UNAVAILABLE);
    }
}
