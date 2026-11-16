import test from "node:test";
import assert from "node:assert/strict";
import { api, nearby, NEARBY_LIMIT } from "../src/lib/api.ts";

const meta = { requestId: "req-1" };
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

test("nearby sends radius and limit=100 (no radiusMeters) and unwraps data.items", async (t) => {
  const calls = stubGet(t, { success: true, data: { center: { latitude: 37.5, longitude: 127 }, radiusMeters: 3000,
    items: [station] }, meta, timestamp: "t" });
  const result = await nearby({ latitude: 37.5, longitude: 127 }, 3000);
  assert.deepEqual(result, [station]);
  assert.equal(calls[0].url, "/stations/nearby");
  assert.deepEqual(calls[0].config.params, { latitude: 37.5, longitude: 127, radius: 3000, limit: 100 });
  assert.equal(NEARBY_LIMIT, 100);
  assert.equal("radiusMeters" in calls[0].config.params, false);
});

test("nearby returns an empty list for empty items and rejects the old bare-array shape", async (t) => {
  stubGet(t, { success: true, data: { center: { latitude: 37.5, longitude: 127 }, radiusMeters: 500, items: [] },
    meta, timestamp: "t" });
  assert.deepEqual(await nearby({ latitude: 37.5, longitude: 127 }, 500), []);
});

test("nearby rejects a pre-A5 array payload", async (t) => {
  stubGet(t, { success: true, data: [station], meta, timestamp: "t" });
  await assert.rejects(nearby({ latitude: 37.5, longitude: 127 }, 500));
});
