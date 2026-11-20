package com.ddareungi.station.presentation;

import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.station.infrastructure.StationInventoryReader;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("infra")
public class StationInventoryController {
    private final StationInventoryReader reader;
    public StationInventoryController(StationInventoryReader reader) { this.reader = reader; }

    @GetMapping("/api/v1/stations/{stationId}/inventory-history")
    public ApiResponse<List<StationInventoryReader.Observation>> history(@PathVariable String stationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
            @RequestParam(defaultValue = "false") boolean includePrevious) {
        return ApiResponse.ok(reader.read(stationId, startTime, includePrevious));
    }
}
