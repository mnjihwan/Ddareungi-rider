import test from "node:test";
import assert from "node:assert/strict";
import {
  realtimeStatus, availability, endpointActions, FRESH_SECONDS, MAX_STALE_SECONDS, MAX_CLOCK_SKEW_MS,
} from "../src/lib/status.ts";

const now = Date.parse("2026-11-16T10:00:00Z");
const at = (seconds) => new Date(now - seconds * 1000).toISOString();

test("freshness thresholds match backend realtime.freshness defaults", () => {
  assert.equal(FRESH_SECONDS, 120);
  assert.equal(MAX_STALE_SECONDS, 600);
  assert.equal(MAX_CLOCK_SKEW_MS, 30_000);
});

test("server FRESH is downgraded by client age: 119/120 s FRESH, 121 s STALE", () => {
  for (const serverStatus of ["FRESH", undefined]) {
    const s = (sec) => realtimeStatus({ realtimeUpdatedAt: at(sec), realtimeStatus: serverStatus }, now);
    assert.equal(s(119), "FRESH", `${serverStatus} 119`);
    assert.equal(s(120), "FRESH", `${serverStatus} 120`);
    assert.equal(s(121), "STALE", `${serverStatus} 121`);
    assert.equal(s(600), "STALE", `${serverStatus} 600`);
    assert.equal(s(601), "UNAVAILABLE", `${serverStatus} 601`);
  }
  // Sub-second ages floor: 120.9 s is still FRESH.
  assert.equal(realtimeStatus({ realtimeUpdatedAt: new Date(now - 120_900).toISOString(), realtimeStatus: "FRESH" }, now), "FRESH");
});

test("future collection times: up to 30 s accepted as FRESH, 31 s unavailable", () => {
  assert.equal(realtimeStatus({ realtimeUpdatedAt: at(-30), realtimeStatus: "FRESH" }, now), "FRESH");
  assert.equal(realtimeStatus({ realtimeUpdatedAt: at(-31), realtimeStatus: "FRESH" }, now), "UNAVAILABLE");
  assert.equal(realtimeStatus({ realtimeUpdatedAt: "not-a-date", realtimeStatus: "FRESH" }, now), "UNAVAILABLE");
});

test("server STALE and UNAVAILABLE are followed even when young", () => {
  assert.equal(realtimeStatus({ realtimeUpdatedAt: at(0), realtimeStatus: "STALE" }, now), "STALE");
  assert.equal(realtimeStatus({ realtimeUpdatedAt: at(0), realtimeStatus: "UNAVAILABLE" }, now), "UNAVAILABLE");
  assert.equal(realtimeStatus({ realtimeUpdatedAt: at(601), realtimeStatus: "STALE" }, now), "UNAVAILABLE");
  // Downgrade affects availability: a FRESH-labelled 0-bike station aged 121 s is "stale", not "empty"/rentable.
  assert.equal(availability({ realtimeUpdatedAt: at(121), realtimeStatus: "FRESH", parkingBikeTotCnt: 0 }, now), "stale");
  assert.equal(availability({ realtimeUpdatedAt: at(121), realtimeStatus: "FRESH", parkingBikeTotCnt: 9 }, now), "stale");
});

const station = (sec, realtimeStatus, parkingBikeTotCnt) => ({ realtimeUpdatedAt: at(sec), realtimeStatus, parkingBikeTotCnt });

test("departure ('여기서 출발') is enabled only for FRESH stations with bikes", () => {
  assert.deepEqual(endpointActions(station(10, "FRESH", 5), false, now).departure, { disabled: false, reason: null });
  assert.equal(endpointActions(station(10, "FRESH", 1), false, now).departure.disabled, false);
  for (const s of [station(10, "STALE", 5), station(121, "FRESH", 5), station(10, "STALE", 0)]) {
    const d = endpointActions(s, false, now).departure;
    assert.equal(d.disabled, true);
    assert.match(d.reason, /갱신이 지연/);
  }
  const unknown = endpointActions(station(10, "UNAVAILABLE", 5), false, now).departure;
  assert.equal(unknown.disabled, true);
  assert.match(unknown.reason, /확인할 수 없어/);
  assert.equal(endpointActions(station(10, "FRESH", null), false, now).departure.disabled, true);
  const empty = endpointActions(station(10, "FRESH", 0), false, now).departure;
  assert.equal(empty.disabled, true);
  assert.match(empty.reason, /자전거가 없어/);
});

test("arrival ('여기로 도착') is disabled only for UNAVAILABLE; STALE is allowed with a delay notice", () => {
  assert.deepEqual(endpointActions(station(10, "FRESH", 0), false, now).arrival, { disabled: false, reason: null });
  assert.deepEqual(endpointActions(station(10, "FRESH", null), false, now).arrival, { disabled: false, reason: null });
  for (const s of [station(10, "STALE", 3), station(300, "FRESH", 0)]) {
    const a = endpointActions(s, false, now).arrival;
    assert.equal(a.disabled, false);
    assert.match(a.reason, /지연/);
  }
  for (const s of [station(10, "UNAVAILABLE", 3), station(601, "STALE", 3), { realtimeUpdatedAt: null, parkingBikeTotCnt: 3 },
    station(-31, "FRESH", 3)]) {
    const a = endpointActions(s, false, now).arrival;
    assert.equal(a.disabled, true);
    assert.ok(a.reason);
  }
});

test("sample data disables both endpoint buttons", () => {
  const actions = endpointActions(station(10, "FRESH", 9), true, now);
  assert.equal(actions.departure.disabled, true);
  assert.equal(actions.arrival.disabled, true);
  assert.ok(actions.departure.reason);
});
