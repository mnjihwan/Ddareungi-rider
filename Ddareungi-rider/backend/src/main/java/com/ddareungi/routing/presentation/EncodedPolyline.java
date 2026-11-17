package com.ddareungi.routing.presentation;

import com.ddareungi.station.domain.Location;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Encoded Polyline Algorithm (spec 13.1 {@code encodedPolyline}): WGS84, latitude then longitude, precision
 * 1e5. Each coordinate is rounded to an integer first (half away from zero) and the deltas are taken between those
 * integers, so rounding errors never accumulate along the line. Reference vector (Google documentation):
 * {@code (38.5,-120.2) (40.7,-120.95) (43.252,-126.453)} ↔ {@code _p~iF~ps|U_ulLnnqC_mqNvxq`@}.
 */
public final class EncodedPolyline {
    private static final double PRECISION = 1e5;

    private EncodedPolyline() {}

    public static String encode(List<Location> points) {
        var out = new StringBuilder(points.size() * 8);
        long previousLatitude = 0, previousLongitude = 0;
        for (var point : points) {
            long latitude = scale(point.latitude());
            long longitude = scale(point.longitude());
            append(out, latitude - previousLatitude);
            append(out, longitude - previousLongitude);
            previousLatitude = latitude;
            previousLongitude = longitude;
        }
        return out.toString();
    }

    /** Inverse of {@link #encode} (values at 1e-5 precision). Rejects malformed input. */
    public static List<Location> decode(String encoded) {
        var points = new ArrayList<Location>();
        int[] index = {0};
        long latitude = 0, longitude = 0;
        while (index[0] < encoded.length()) {
            latitude += next(encoded, index);
            longitude += next(encoded, index);
            points.add(new Location(latitude / PRECISION, longitude / PRECISION));
        }
        return points;
    }

    /** Round half away from zero (e.g. -0.000005 -> -1, 0.000005 -> 1), as the reference implementation does. */
    static long scale(double value) {
        long magnitude = Math.round(Math.abs(value) * PRECISION);
        return value < 0 ? -magnitude : magnitude;
    }

    private static void append(StringBuilder out, long delta) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 0x20) {
            out.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        out.append((char) (value + 63));
    }

    private static long next(String encoded, int[] index) {
        long result = 0;
        int shift = 0;
        int chunk;
        do {
            if (index[0] >= encoded.length()) throw new IllegalArgumentException("truncated polyline");
            chunk = encoded.charAt(index[0]++) - 63;
            if (chunk < 0 || chunk > 0x3f || shift > 60) throw new IllegalArgumentException("malformed polyline");
            result |= (long) (chunk & 0x1f) << shift;
            shift += 5;
        } while (chunk >= 0x20);
        return (result & 1) != 0 ? ~(result >> 1) : result >> 1;
    }
}
