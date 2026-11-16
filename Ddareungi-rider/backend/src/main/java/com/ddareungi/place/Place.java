package com.ddareungi.place;

import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;

/**
 * A place returned by a {@link PlaceSearchProvider}. Internal model; the public shape is {@link PlaceResponse}.
 *
 * @param sourceUrl provider detail page, already validated and normalized to https (null when absent or invalid)
 * @param categoryGroupCode provider category code (e.g. {@code SW8}); empty when unknown
 */
public record Place(String name, String address, Location location, @Nullable String sourceUrl,
                    String categoryGroupCode) {}
