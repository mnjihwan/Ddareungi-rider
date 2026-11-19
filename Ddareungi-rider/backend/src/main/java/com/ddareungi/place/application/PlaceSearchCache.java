package com.ddareungi.place.application;

import com.ddareungi.place.Place;
import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Short-lived cache of provider results (D9). Implementations must fail open: a cache error behaves as a miss and
 * never fails the search. Only successful provider answers are stored.
 */
public interface PlaceSearchCache {
    String KEY_PREFIX = "place:v1:";

    /** The cached places for {@code key}, or null on a miss or any cache error. */
    @Nullable List<Place> get(String key);

    /** Stores {@code places}; errors are swallowed. */
    void put(String key, List<Place> places);

    /** No caching (local profile, or {@code place.cache.enabled=false}). */
    PlaceSearchCache NONE = new PlaceSearchCache() {
        @Override public @Nullable List<Place> get(String key) { return null; }
        @Override public void put(String key, List<Place> places) {}
    };

    /**
     * {@code place:v1:{sha256(normalized query|latitude|longitude|category)}}. Coordinates are rounded to three
     * decimals (about 100 m) so nearby users share entries; the raw query never appears in the Redis key.
     */
    static String key(String query, @Nullable Location center, String category) {
        String material = Keys.normalize(query) + "|" + Keys.round(center == null ? null : center.latitude())
                + "|" + Keys.round(center == null ? null : center.longitude()) + "|" + category;
        return KEY_PREFIX + Keys.sha256(material);
    }

    /** Key helpers kept out of the interface's public surface. */
    final class Keys {
        private static final Pattern WHITESPACE = Pattern.compile("\\s+");
        private Keys() {}

        static String normalize(String query) {
            String nfc = Normalizer.normalize(query, Normalizer.Form.NFC).trim();
            return WHITESPACE.matcher(nfc).replaceAll(" ").toLowerCase(Locale.ROOT);
        }

        static String round(@Nullable Double coordinate) {
            return coordinate == null ? "-" : BigDecimal.valueOf(coordinate).setScale(3, RoundingMode.HALF_UP).toPlainString();
        }

        static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
