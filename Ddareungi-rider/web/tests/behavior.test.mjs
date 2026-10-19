import test from "node:test";
import assert from "node:assert/strict";
import { availability, forecastAt, compareStations, stationOutlook, compareOutlooks } from "../src/lib/status.ts";
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

test("station lists prioritize rentable stock, then zero stock, then unknown, by distance within each group", () => {
  const make = (id, count, distance, updatedAt = station.updatedAt) => ({
    stationId: id, availableBikeCount: count, distanceMeters: distance, updatedAt,
  });
  const items = [make('missing-near', null, 10), make('empty-far', 0, 80),
    make('available-far', 10, 500), make('low-near', 1, 100),
    make('stale', 20, 30, new Date(now - 301000).toISOString()),
    make('empty-near', 0, 20), make('missing-far', null, 200)];
  assert.deepEqual(items.sort((a,b)=>compareStations(a,b,now)).map(s=>s.stationId),
    ['low-near','available-far','empty-near','empty-far','missing-near','stale','missing-far']);
});

test("forecast ranking uses future stock and leaves missing or stale predictions last", () => {
  const make = (id, count, distance) => ({...station, stationId:id, availableBikeCount:count, distanceMeters:distance});
  const items=[make('empty-now',0,300),make('available-now',10,100),make('missing',5,10),make('stale',5,20)];
  const forecast=(id,count,time=now)=>({stationId:id,baseTime:new Date(time).toISOString(),dataStatus:'fresh',predictions:[{targetTime:new Date(now+15*60000).toISOString(),expectedBikes:count}]});
  const forecasts=new Map([['empty-now',forecast('empty-now',3)],['available-now',forecast('available-now',0)],['stale',forecast('stale',8,now-601000)]]);
  assert.deepEqual([...items].sort((a,b)=>compareOutlooks(a,b,forecasts,15,now)).map(s=>s.stationId),['empty-now','available-now','missing','stale']);
  assert.equal(stationOutlook(items[0],forecasts.get('empty-now'),0,now).count,0);
  assert.equal(stationOutlook(items[0],forecasts.get('empty-now'),15,now).count,3);
  assert.equal(stationOutlook(items[0],forecast('other',3),15,now).count,null);
  assert.equal(stationOutlook(items[0],forecast('empty-now',-1),15,now).count,null);
  assert.equal(stationOutlook(items[0],forecast('empty-now',3),60,now).count,null);
});
