import test from "node:test";
import assert from "node:assert/strict";
import { decodePolyline } from "../src/lib/polyline.ts";

// Reference encoder (Google algorithm, as the backend EncodedPolyline: integers rounded half away from zero, deltas
// between those integers) used only to build round-trip vectors here.
function encode(points) {
  let out = "", lat = 0, lng = 0;
  const scale = (value) => Math.sign(value) * Math.round(Math.abs(value) * 1e5);
  const append = (delta) => {
    let value = delta < 0 ? -2 * delta - 1 : 2 * delta;
    while (value >= 0x20) { out += String.fromCharCode((0x20 | (value % 32)) + 63); value = Math.floor(value / 32); }
    out += String.fromCharCode(value + 63);
  };
  for (const point of points) {
    const a = scale(point.latitude), b = scale(point.longitude);
    append(a - lat); append(b - lng); lat = a; lng = b;
  }
  return out;
}

test("decodes the Google reference vector shared with the backend EncodedPolylineTest", () => {
  assert.deepEqual(decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@"), [
    { latitude: 38.5, longitude: -120.2 },
    { latitude: 40.7, longitude: -120.95 },
    { latitude: 43.252, longitude: -126.453 },
  ]);
});

test("latitude comes before longitude, an empty string is an empty line", () => {
  assert.deepEqual(decodePolyline(encode([{ latitude: 37.4979, longitude: 127.0276 }])),
    [{ latitude: 37.4979, longitude: 127.0276 }]);
  assert.deepEqual(decodePolyline(""), []);
});

test("negative values, long deltas and a zero-distance two-point WALK round-trip exactly", () => {
  const points = [
    { latitude: -89.99999, longitude: -179.99999 },
    { latitude: 89.99999, longitude: 179.99999 },   // ±180° jump: 36,000,000 zigzag needs six chunks (> 2^31 safe)
    { latitude: 37.56647, longitude: 126.97796 },
    { latitude: 37.56647, longitude: 126.97796 },   // 0-distance segment keeps two identical points
    { latitude: -0.00001, longitude: 0.00001 },
  ];
  assert.deepEqual(decodePolyline(encode(points)), points);
});

test("malformed or truncated input throws instead of drawing a wrong line", () => {
  assert.throws(() => decodePolyline("_p~iF~ps|U_"), /Truncated/);   // last chunk has its continuation bit set
  assert.throws(() => decodePolyline("_p~iF"), /Truncated/);          // latitude without longitude
  assert.throws(() => decodePolyline("_p~iF ps|U"), /Malformed/);     // space is below '?'
});
