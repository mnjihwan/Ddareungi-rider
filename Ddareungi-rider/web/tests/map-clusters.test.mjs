import test from "node:test";
import assert from "node:assert/strict";
import {
  mapQueryMode, clusterLevel, clusterRequestBounds, clusterZoomLevel, isTruncated, mergeOverlappingClusters,
  CLUSTER_CELL_DEGREES, MAX_BOX_DEGREES,
} from "../src/lib/mapClusters.ts";
import { api, viewportStations, stationClusters } from "../src/lib/api.ts";

test("levels 1..4 use the viewport query, 5..8 server clusters; unknown level stays on viewport", () => {
  assert.deepEqual([1, 3, 4].map(mapQueryMode), ["viewport", "viewport", "viewport"]);
  assert.deepEqual([5, 6, 7, 8].map(mapQueryMode), ["clusters", "clusters", "clusters", "clusters"]);
  assert.equal(mapQueryMode(null), "viewport");
  assert.equal(clusterLevel(9), 8);
  assert.equal(clusterLevel(5), 5);
});

test("cluster request box: padded by one cell, snapped outward to the cell grid, stable within a cell", () => {
  const view = { south: 37.4951, north: 37.5049, west: 127.0201, east: 127.0399 };
  const box = clusterRequestBounds(view, 8);   // cell 0.02
  assert.deepEqual(box, { south: 37.46, north: 37.54, west: 127.0, east: 127.06 });
  for (const key of ["south", "north", "west", "east"]) {
    const cells = box[key] / CLUSTER_CELL_DEGREES[8];
    assert.ok(Math.abs(cells - Math.round(cells)) < 1e-6, `${key} on the grid`);
  }
  assert.ok(box.south < view.south && box.north > view.north && box.west < view.west && box.east > view.east);
  // A small pan inside the same cells gives the same key (no refetch).
  assert.deepEqual(clusterRequestBounds({ south: 37.4952, north: 37.5048, west: 127.0202, east: 127.0398 }, 8), box);
  const l5 = clusterRequestBounds(view, 5);   // cell 0.003
  assert.deepEqual(l5, { south: 37.491, north: 37.509, west: 127.017, east: 127.044 });
});

test("cluster request box never exceeds the server's 1° limit or the coordinate range", () => {
  const wide = clusterRequestBounds({ south: 37.0, north: 37.99, west: 126.5, east: 127.49 }, 8);
  assert.ok(wide.north - wide.south <= MAX_BOX_DEGREES + 1e-9);
  assert.ok(wide.east - wide.west <= MAX_BOX_DEGREES + 1e-9);
  assert.ok(wide.south > 37.0 - 0.1 && wide.north < 37.99 + 0.1);   // shrunk around the centre
  const edge = clusterRequestBounds({ south: 89.99, north: 90, west: 179.99, east: 180 }, 8);
  assert.equal(edge.north, 90);
  assert.equal(edge.east, 180);
});

test("cluster click zooms in two levels and stops at level 4 (individual markers)", () => {
  assert.deepEqual([5, 6, 7, 8].map(clusterZoomLevel), [4, 4, 5, 6]);
});

test("overlapping neighbour cells are merged with summed counts; separated ones stay apart", () => {
  const cell = (latitude, longitude, x, y, stationCount, parkingBikeTotCnt, freshCount = stationCount) =>
    ({ latitude, longitude, x, y, stationCount, parkingBikeTotCnt, freshCount });
  const merged = mergeOverlappingClusters([
    cell(37.50, 127.00, 100, 100, 3, 12),
    cell(37.51, 127.01, 130, 110, 1, 4, 0),
    cell(37.60, 127.10, 400, 400, 2, 0),
  ]);
  assert.equal(merged.length, 2);
  const big = merged.find(marker => marker.cells === 2);
  assert.equal(big.stationCount, 4);
  assert.equal(big.parkingBikeTotCnt, 16);
  assert.equal(big.freshCount, 3);
  assert.ok(Math.abs(big.latitude - (37.50 * 3 + 37.51) / 4) < 1e-9, "station-weighted mean");
  const lone = merged.find(marker => marker.cells === 1);
  assert.deepEqual([lone.stationCount, lone.parkingBikeTotCnt, lone.latitude], [2, 0, 37.60]);
  assert.deepEqual(mergeOverlappingClusters([]), []);
  assert.equal(new Set(merged.map(marker => marker.id)).size, 2);
});

const station = { stationId: "ST-1", stationName: "강남역", stationLatitude: 37.4979, stationLongitude: 127.0276,
  rackTotCnt: 20, parkingBikeTotCnt: 3, shared: 15, realtimeStatus: "FRESH", realtimeUpdatedAt: "t",
  realtimeAgeSeconds: 5, distanceMeters: 10, address: null };

function stubGet(t, data) {
  const calls = [];
  const original = api.get;
  api.get = async (url, config) => { calls.push({ url, config }); return { data }; };
  t.after(() => { api.get = original; });
  return calls;
}

test("viewport reports truncation only for partial + RESULT_TRUNCATED", async (t) => {
  const bounds = { south: 37.49, north: 37.51, west: 127.02, east: 127.04 };
  let calls = stubGet(t, { success: true, data: [station], meta: { requestId: "r", partial: true,
    warnings: [{ code: "STALE_DATA" }, { code: "RESULT_TRUNCATED" }] }, timestamp: "t" });
  assert.deepEqual(await viewportStations(bounds), { stations: [station], truncated: true });
  assert.equal(calls[0].url, "/stations/viewport");
  assert.deepEqual(calls[0].config.params, bounds);
  assert.equal(isTruncated({ requestId: "r", partial: true, warnings: [{ code: "STALE_DATA" }] }), false);
  assert.equal(isTruncated({ requestId: "r" }), false);
  calls = stubGet(t, { success: true, data: [station], meta: { requestId: "r" }, timestamp: "t" });
  assert.deepEqual(await viewportStations(bounds), { stations: [station], truncated: false });
});

test("clusters call sends the box and level and validates items", async (t) => {
  const bounds = { south: 37.46, north: 37.54, west: 127.0, east: 127.08 };
  const data = { level: 8, cellDegrees: 0.02, items: [{ latitude: 37.4979, longitude: 127.0276, stationCount: 1,
    parkingBikeTotCnt: 7, freshCount: 1 }] };
  const calls = stubGet(t, { success: true, data, meta: { requestId: "r" }, timestamp: "t" });
  assert.deepEqual(await stationClusters(bounds, 8), data);
  assert.equal(calls[0].url, "/stations/clusters");
  assert.deepEqual(calls[0].config.params, { ...bounds, level: 8 });
  api.get = async () => ({ data: { success: true, data: [station], meta: {}, timestamp: "t" } });
  await assert.rejects(stationClusters(bounds, 8));
});
