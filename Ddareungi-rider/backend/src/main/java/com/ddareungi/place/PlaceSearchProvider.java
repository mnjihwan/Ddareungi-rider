package com.ddareungi.place;

import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * External place search (Kakao Local today). Implementations never expose provider bodies or credentials: every
 * failure surfaces as {@link PlaceProviderException}, without the original cause in its message.
 */
public interface PlaceSearchProvider {
    /** False when the provider has no credentials; callers then answer 503 without calling it. */
    boolean configured();

    /**
     * Keyword search (up to 15 results).
     *
     * @param center when given, results are sorted by distance from it
     * @param categoryGroupCode when given, only places of this category are requested and returned
     */
    List<Place> keyword(String query, @Nullable Location center, @Nullable String categoryGroupCode);

    /** Address search (up to 5 results); the address doubles as the name. */
    List<Place> address(String query);

    /** Address of a coordinate; the returned place keeps {@code location}. Null when nothing is known there. */
    @Nullable Place reverse(Location location);

    /** Category search around {@code center}, sorted by distance (up to 15 results). */
    List<Place> category(String categoryGroupCode, Location center, int radiusMeters);
}
