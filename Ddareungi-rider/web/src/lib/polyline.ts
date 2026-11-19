import type { Coordinates } from "./types.ts";

/**
 * Decodes a Google Encoded Polyline (spec 13.1 `encodedPolyline`, phase 3 A6): precision 1e5, latitude then
 * longitude, each value a zigzag-encoded delta from the previous point. Mirrors the backend `EncodedPolyline`.
 *
 * Uses arithmetic instead of JavaScript's 32-bit bitwise operators, so long deltas (e.g. a 180° longitude jump,
 * whose zigzag value 36,000,000 needs six 5-bit chunks) and negative values stay exact.
 * Malformed input (a character outside `?`..`~`, or a value cut off mid-chunk) throws instead of returning a
 * silently wrong line.
 */
export function decodePolyline(encoded: string): Coordinates[] {
  const points: Coordinates[] = [];
  let index = 0, latitude = 0, longitude = 0;
  const next = (): number => {
    let result = 0, factor = 1, chunk: number;
    do {
      if (index >= encoded.length) throw new Error("Truncated polyline");
      chunk = encoded.charCodeAt(index++) - 63;
      if (chunk < 0 || chunk > 0x3f || factor > 2 ** 50) throw new Error("Malformed polyline");
      result += (chunk & 0x1f) * factor;
      factor *= 32;
    } while (chunk >= 0x20);
    // Zigzag: odd values are negative, ~(n >> 1) === -(n + 1) / 2.
    return result % 2 === 1 ? -(result + 1) / 2 : result / 2;
  };
  while (index < encoded.length) {
    latitude += next();
    longitude += next();
    points.push({ latitude: latitude / 1e5, longitude: longitude / 1e5 });
  }
  return points;
}
