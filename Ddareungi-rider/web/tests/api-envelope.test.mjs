import test from "node:test";
import assert from "node:assert/strict";
import { unwrap, apiError, apiErrorMessage } from "../src/lib/apiEnvelope.ts";
import { stationLocation } from "../src/lib/types.ts";
import { buildSearchSuggestions } from "../src/lib/searchSuggestions.ts";

const meta = { requestId: "req-1" };
const failure = (body) => ({ isAxiosError: true, response: { status: 400, data: body } });

test("unwrap returns data of a success envelope, including null and empty arrays", () => {
  assert.deepEqual(unwrap({ success: true, data: [1, 2], meta, timestamp: "2026-11-13T00:00:00Z" }), [1, 2]);
  assert.deepEqual(unwrap({ success: true, data: [], meta, timestamp: "t" }), []);
  assert.equal(unwrap({ success: true, data: null, meta, timestamp: "t" }), null);
});

test("unwrap rejects failure envelopes and pre-envelope bodies", () => {
  assert.throws(() => unwrap({ success: false, error: { code: "X", message: "m", details: [] }, meta, timestamp: "t" }));
  // The old `{data}` shape without `success` is no longer accepted.
  assert.throws(() => unwrap({ data: [] }));
  assert.throws(() => unwrap(null));
  assert.throws(() => unwrap("<html>"));
});

test("error message comes from error.message of the failure envelope", () => {
  const error = failure({ success: false, error: { code: "NO_AVAILABLE_ORIGIN_STATION", message: "출발지 주변에 대여 가능한 자전거가 없어요.", details: [] }, meta, timestamp: "t" });
  assert.equal(apiErrorMessage(error, "fallback"), "출발지 주변에 대여 가능한 자전거가 없어요.");
  assert.equal(apiError(error).code, "NO_AVAILABLE_ORIGIN_STATION");
});

test("fallback is used for network errors, old error bodies and blank messages", () => {
  assert.equal(apiErrorMessage(new Error("Network Error"), "fallback"), "fallback");
  assert.equal(apiErrorMessage({ response: undefined }, "fallback"), "fallback");
  // Pre-phase-1 `{code,message}` bodies are not read any more.
  assert.equal(apiErrorMessage(failure({ code: "INVALID_REQUEST", message: "old" }), "fallback"), "fallback");
  assert.equal(apiErrorMessage(failure({ success: false, error: { code: "X", message: "  ", details: [] } }), "fallback"), "fallback");
  assert.equal(apiErrorMessage(failure("<html>502</html>"), "fallback"), "fallback");
  assert.equal(apiErrorMessage(null, "fallback"), "fallback");
});

test("station coordinates use the source field names", () => {
  const station = { stationId: "ST-1", stationName: "강남역", stationLatitude: 37.4979, stationLongitude: 127.0276,
    rackTotCnt: null, parkingBikeTotCnt: 0, shared: null, realtimeUpdatedAt: null, distanceMeters: 1, address: null };
  assert.deepEqual(stationLocation(station), { latitude: 37.4979, longitude: 127.0276 });
  assert.equal("location" in station, false);
  // A station without the address extension field still produces a suggestion.
  const items = buildSearchSuggestions([], [station], "강남");
  assert.equal(items[0].kind, "station");
  assert.equal(items[0].address, "");
});
