package com.ddareungi.place;

import com.ddareungi.global.response.CoordinateResponse;
import org.jspecify.annotations.Nullable;

/** Place search result. Fields are unchanged from before phase 1 (A1 only separates the DTO). */
public record PlaceResponse(String name, String address, CoordinateResponse location,
                            @Nullable String sourceUrl, String categoryGroupCode) {}
