package com.ddareungi.station.presentation;

import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.station.application.FindNearbyStationsUseCase;
import com.ddareungi.station.domain.Station;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/stations")
public class StationController {
    private final FindNearbyStationsUseCase useCase;

    public StationController(FindNearbyStationsUseCase useCase) { this.useCase = useCase; }

    @GetMapping("/nearby")
    public ApiResponse<List<Station>> nearby(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double latitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam(defaultValue = "1000") @Min(100) @Max(5000) int radiusMeters
    ) {
        return ApiResponse.ok(useCase.execute(latitude, longitude, radiusMeters));
    }

    @PostMapping("/batch")
    public ApiResponse<List<Station>> batch(@Valid @RequestBody StationBatchRequest request) {
        return ApiResponse.ok(useCase.findByIds(request.stationIds(), request.latitude(), request.longitude()));
    }

    @GetMapping("/viewport")
    public ApiResponse<List<Station>> viewport(@RequestParam double south, @RequestParam double north,
            @RequestParam double west, @RequestParam double east) {
        return ApiResponse.ok(useCase.findInBounds(south, north, west, east));
    }

    @GetMapping("/search")
    public ApiResponse<List<Station>> search(@RequestParam String query,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double latitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude) {
        return ApiResponse.ok(useCase.searchByName(query, latitude, longitude));
    }

    public record StationBatchRequest(
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 30) String> stationIds,
            @DecimalMin("-90") @DecimalMax("90") double latitude,
            @DecimalMin("-180") @DecimalMax("180") double longitude
    ) {}
}
