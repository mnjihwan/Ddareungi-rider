import test from "node:test";
import assert from "node:assert/strict";
import { availability, forecastAt } from "../src/lib/status.ts";
import { parseFavorites } from "../src/lib/favoriteStorage.ts";
import { clusterPoints, separateMarkerGroups } from "../src/lib/clusters.ts";
const now = Date.parse("2026-10-06T10:00:00Z");
const station = {
  updatedAt: new Date(now).toISOString(),
  availableBikeCount: 0,
};
test("zero, low, available and missing quantities remain distinct", () => {
  assert.equal(availability(station, now), "empty");
  assert.equal(availability({ ...station, availableBikeCount: 3 }, now), "low");
  assert.equal(
    availability({ ...station, availableBikeCount: 4 }, now),
    "available",
  );
  assert.equal(
    availability({ ...station, availableBikeCount: null }, now),
    "unknown",
  );
  assert.equal(
    availability(
      { ...station, updatedAt: new Date(now - 301000).toISOString() },
      now,
    ),
    "unknown",
  );
  assert.equal(
    availability(
      { ...station, updatedAt: new Date(now + 60000).toISOString() },
      now,
    ),
    "unknown",
  );
});
test("forecast horizon follows current arrival time rather than an old baseline", () => {
  const f = {
    baseTime: new Date(now - 300000).toISOString(),
    dataStatus: "fresh",
    predictions: [
      { targetTime: new Date(now + 600000).toISOString(), expectedBikes: 8 },
      { targetTime: new Date(now + 900000).toISOString(), expectedBikes: 2.4 },
    ],
  };
  assert.equal(forecastAt(f, 15, now).count, 2);
  assert.equal(forecastAt({ ...f, dataStatus: "stale" }, 15, now), null);
  assert.equal(forecastAt(f, 60, now), null);
});
test("favorites deduplicate, discard cached station values, and reject corrupt storage", () => {
  const item = {
    stationId: "ST-001",
    addedAt: new Date(now).toISOString(),
    availableBikeCount: 99,
  };
  assert.deepEqual(
    parseFavorites(
      JSON.stringify({
        schemaVersion: 1,
        items: [item, item, { stationId: "" }],
      }),
    ),
    [{ stationId: "ST-001", addedAt: item.addedAt }],
  );
  assert.throws(() => parseFavorites("{bad"));
  assert.throws(() =>
    parseFavorites(JSON.stringify({ schemaVersion: 2, items: [] })),
  );
  assert.deepEqual(parseFavorites(null), []);
});
test("selected marker stays selectable outside its cluster", () => {
  const points = [
    { id: "a", x: 10, y: 10 },
    { id: "b", x: 11, y: 12 },
  ];
  assert.equal(clusterPoints(points, null).length, 1);
  assert.equal(clusterPoints(points, "a").length, 2);
});
test("marker grouping follows overlap instead of grid cells or chains",()=>{
  assert.equal(clusterPoints([{id:'a',x:10,y:2},{id:'b',x:12,y:60}],null).length,2);
  assert.equal(clusterPoints([{id:'a',x:63,y:10},{id:'b',x:65,y:10}],null).length,1);
  const chained=clusterPoints([{id:'a',x:0,y:0},{id:'b',x:60,y:0},{id:'c',x:120,y:0}],null);
  assert.equal(chained.length,2);
});
test("close zoom preserves every station and separates overlapping labels with the selection anchored",()=>{
  const points=[{id:'a',x:120,y:100},{id:'b',x:120,y:100},{id:'c',x:121,y:103}];
  const layout=separateMarkerGroups(clusterPoints(points,'b',0),'b');
  assert.equal(layout.length,3);
  assert.equal(layout.find(group=>group.stationIds[0]==='b').offsetX,0);
  assert.equal(layout.find(group=>group.stationIds[0]==='b').offsetY,0);
  for(let i=0;i<layout.length;i++)for(let j=i+1;j<layout.length;j++) {
    const a=layout[i],b=layout[j];
    assert(Math.abs(a.x+a.offsetX-b.x-b.offsetX)>=76 || Math.abs(a.y+a.offsetY-b.y-b.offsetY)>=52);
  }
});
