import assert from 'node:assert/strict';
import { test } from 'node:test';
import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { isCurrentFix, locationLossOnResume, LOCATION_MAX_AGE_MS, LOCATION_MESSAGES, readLocation } from '../src/features/current-location/model/location.ts';
import { asFixedLocation } from '../src/features/route-planning/model/endpoints.ts';
import { routeStationObservation } from '../src/features/route-planning/model/routeStationObservation.ts';
import { createRiderStorage, parseSavedItems } from '../src/features/saved/model/riderStorage.ts';
import { mergeStationDetail } from '../src/features/station-search/model/mergeStationDetail.ts';
import { stationCountLabel } from '../src/features/station-search/model/stationCountLabel.ts';
import { createLatestRequest } from '../src/shared/api/latestRequest.ts';
import { buildSearchSuggestions, matchSearchResults } from '../src/shared/rider-contract/searchSuggestions.ts';
import { comparisonRows } from '../src/shared/rider-contract/inventoryComparison.ts';
import { endpointActions } from '../src/shared/rider-contract/status.ts';
import { HEADER_HEIGHT, PEEK_SHEET_HEIGHT, sheetHeights } from '../src/screens/home/ui/geometry.ts';

globalThis.__DEV__ = true;
const { apiClient } = await import('../src/shared/api/client.ts');
const { fetchRecommendedRoute, routingStatusQuery } = await import('../src/features/route-planning/api/routeApi.ts');
const { fetchBatchStations, fetchInventoryHistory, fetchNearbyStations, fetchServiceMode, fetchViewportStations, searchPlaces, searchStations } =
  await import('../src/features/station-search/api/stationApi.ts');

const now = Date.parse('2026-11-20T03:00:00Z');
const center = { latitude: 37.4979, longitude: 127.0276 };
const station = {
  stationId: 'ST-12', stationName: '강남역 대여소', address: null,
  stationLatitude: center.latitude, stationLongitude: center.longitude,
  rackTotCnt: 15, parkingBikeTotCnt: 0, shared: 0, distanceMeters: 100,
  realtimeStatus: 'FRESH', realtimeUpdatedAt: new Date(now).toISOString(),
};

test('mobile web sheet geometry keeps the map fixed across list, detail and route', () => {
  const screen = 616;
  const workspace = screen - HEADER_HEIGHT;
  assert.equal(workspace - PEEK_SHEET_HEIGHT, 508);
  assert.equal(Math.round(screen - sheetHeights(workspace, false).mid), 362);
  assert.equal(screen - sheetHeights(workspace, true).mid, 235);
  assert.equal(screen - sheetHeights(workspace, false).full, 141);
});

function success(config, data, meta = {}) {
  return { config, status: 200, headers: {}, data: { success: true, data, meta } };
}

function useAdapter(t, adapter) {
  const original = apiClient.defaults.adapter;
  apiClient.defaults.adapter = adapter;
  t.after(() => { apiClient.defaults.adapter = original; });
}

test('detail mode and history use the backend contract and the selected Korean time', async (t) => {
  const signal = new AbortController().signal;
  const selected = '11:55';
  const slot = '2026-11-20T11:55:00+09:00';
  const records = [{ stationId: station.stationId, observedSlot: slot, availableBikes: 0 }];
  useAdapter(t, async (config) => {
    assert.equal(config.signal, signal);
    if (config.url === '/api/v1/status') return success(config, { mode: 'infra' });
    assert.equal(config.url, '/api/v1/stations/ST-12/inventory-history');
    assert.deepEqual(config.params, { startTime: slot, includePrevious: true });
    return success(config, records);
  });
  assert.equal(await fetchServiceMode(signal), 'infra');
  const history = await fetchInventoryHistory(station.stationId, slot, signal);
  const actual = comparisonRows(station, selected, now, history);
  assert.deepEqual(actual[2].cells.map(({ count }) => count), [0, null, null]);
  assert.equal(actual[4].cells[0].isFuture, true);
  assert.equal(comparisonRows(station, selected, now, history, true)[2].cells[0].count, null);
});

test('submitted search uses all matches, including stations beyond the autocomplete cap', () => {
  const places = Array.from({ length: 12 }, (_, index) => ({
    name: `강남역 장소 ${index}`, address: '서울시', location: { ...center, longitude: center.longitude + index / 100 },
  }));
  const stations = Array.from({ length: 5 }, (_, index) => ({
    ...station, stationId: `ST-${index}`, stationName: `강남역 대여소 ${index}`,
  }));
  const unrelated = { ...station, stationId: 'ST-unrelated', stationName: '건대입구' };
  const matches = matchSearchResults(places, [...stations, unrelated], '강남역');
  assert.deepEqual(matches.places, places);
  assert.deepEqual(matches.stations, stations);
  const suggestions = buildSearchSuggestions(places, stations, '강남역');
  assert.equal(suggestions.length, 10);
  assert.equal(suggestions.filter(({ kind }) => kind === 'station').length, 3);
});

test('nearby and viewport use the current envelope; 0 remains a valid return count', async (t) => {
  const bounds = { south: 37.49, north: 37.50, west: 127.02, east: 127.03 };
  useAdapter(t, async (config) => {
    if (config.url === '/api/v1/stations/nearby') {
      assert.deepEqual(config.params, { ...center, radius: 500, limit: 30 });
      return success(config, { items: [station] });
    }
    assert.equal(config.url, '/api/v1/stations/viewport');
    assert.deepEqual(config.params, bounds);
    return success(config, [station], { partial: true, warnings: [{ code: 'RESULT_TRUNCATED' }] });
  });
  const [nearby] = await fetchNearbyStations(center);
  assert.equal(stationCountLabel(nearby, now), '0대');
  assert.equal(endpointActions(nearby, false, now).departure.disabled, true);
  assert.equal(endpointActions(nearby, false, now).arrival.disabled, false);
  assert.equal(stationCountLabel({ ...nearby, parkingBikeTotCnt: null }, now), '수량 확인 불가');
  assert.equal(stationCountLabel(nearby, now + 121_000, true), '0대 · 지연');
  assert.equal(stationCountLabel(nearby, now + 601_000), '수량 확인 불가');
  assert.deepEqual(await fetchViewportStations(bounds), { stations: [station], truncated: true });
});

test('route search sends explicit IDs and provider options and decodes WALK, BIKE, WALK', async (t) => {
  const destination = { latitude: 37.5006, longitude: 127.0364 };
  const signal = new AbortController().signal;
  useAdapter(t, async (config) => {
    assert.equal(config.url, '/api/v1/routes/search');
    assert.equal(config.signal, signal);
    assert.deepEqual(JSON.parse(config.data), {
      origin: center, destination, departureStationId: station.stationId,
      options: { maxWalkingDistanceMeters: 10000, preferBikeRoad: false },
    });
    return success(config, {
      recommended: {
        routeId: 'route-fixture', originStation: station, destinationStation: { ...station, stationId: 'ST-44' },
        totalDistanceMeters: 922, estimatedDurationSeconds: 558, walkingDistanceMeters: 222, bikeDistanceMeters: 700,
        rentalAvailability: 'AVAILABLE_AT_FETCH', returnAvailability: 'UNKNOWN',
        segments: ['WALK', 'BIKE', 'WALK'].map((type) => ({
          type, distanceMeters: 10, durationSeconds: 5, encodedPolyline: '_p~iF~ps|U_ulLnnqC', steps: [],
        })),
      },
      alternatives: [], attribution: 'fixture',
    }, { partial: true, warnings: [{ code: 'PROVIDER_PARTIAL_FAILURE' }] });
  });
  const result = await fetchRecommendedRoute(center, destination, { departureStationId: station.stationId },
    { configured: true, bikeRoadPreferenceSupported: false }, signal);
  assert.equal(result.partial, true);
  assert.equal(result.route.distanceMeters, 922);
  assert.equal(result.route.durationSeconds, 558);
  assert.equal(result.route.arrivalStation.stationId, 'ST-44');
  assert.deepEqual(result.route.legs.map(({ mode }) => mode), ['walk', 'bicycle', 'walk']);
  for (const leg of result.route.legs) {
    assert.deepEqual(leg.path, [{ latitude: 38.5, longitude: -120.2 }, { latitude: 40.7, longitude: -120.95 }]);
  }
});

test('explicit status refetch replaces the displayed cache and uses the current provider capability', async (t) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: 3 } } });
  t.after(() => client.clear());
  const observer = new QueryObserver(client, routingStatusQuery);
  const updates = [
    { configured: false },
    { configured: true, bikeRoadPreferenceSupported: true },
    { configured: true, bikeRoadPreferenceSupported: false },
  ];
  let calls = 0;
  useAdapter(t, async (config) => {
    assert.equal(config.url, '/api/v1/routes/status');
    calls += 1;
    const update = updates.shift();
    if (!update) throw new Error('status unavailable');
    return success(config, update);
  });
  assert.equal((await observer.refetch({ throwOnError: true })).data.configured, false);
  assert.equal((await observer.refetch({ throwOnError: true })).data.configured, true);
  assert.deepEqual((await observer.refetch({ throwOnError: true })).data,
    { configured: true, bikeRoadPreferenceSupported: false });
  assert.deepEqual(client.getQueryData(routingStatusQuery.queryKey), { configured: true, bikeRoadPreferenceSupported: false });
  await assert.rejects(observer.refetch({ throwOnError: true }), /status unavailable/);
  assert.equal(calls, 4);
});

test('route station refresh changes stock and keeps the route points for omitted stations', async (t) => {
  const second = { ...station, stationId: 'ST-44', parkingBikeTotCnt: 9 };
  useAdapter(t, async (config) => {
    assert.equal(config.url, '/api/v1/stations/batch');
    assert.deepEqual(JSON.parse(config.data), { stationIds: [station.stationId, second.stationId], ...center });
    return success(config, [{ ...station, stationLongitude: 127.034, distanceMeters: 300, parkingBikeTotCnt: 5 }]);
  });
  const batch = await fetchBatchStations([station.stationId, second.stationId], center);
  assert.equal(routeStationObservation(station, undefined), station);
  assert.deepEqual(routeStationObservation(station, batch), { ...station, parkingBikeTotCnt: 5 });
  const unavailable = routeStationObservation(second, batch);
  assert.equal(unavailable.stationId, 'ST-44');
  assert.equal(unavailable.stationLongitude, second.stationLongitude);
  assert.equal(stationCountLabel(unavailable, now), '수량 확인 불가');
});

test('one failed search source does not discard the successful source', async (t) => {
  useAdapter(t, async (config) => {
    assert.deepEqual(config.params, { query: '강남역', ...center });
    if (config.url === '/api/v1/places/search') throw new Error('provider unavailable');
    assert.equal(config.url, '/api/v1/stations/search');
    return success(config, [station]);
  });
  const [places, stations] = await Promise.allSettled([searchPlaces('강남역', center), searchStations('강남역', center)]);
  assert.equal(places.status, 'rejected');
  assert.equal(stations.status, 'fulfilled');
  assert.deepEqual(buildSearchSuggestions([], stations.value, '강남역').map(({ key }) => key), ['station:ST-12']);
});

test('latest requests invalidate both earlier A and B when input returns to A', async () => {
  const requests = createLatestRequest();
  const accepted = [];
  const results = ['A', 'B', 'new A'].map((value) => {
    const signal = requests.start();
    return Promise.resolve(value).then((result) => { if (!signal.aborted) accepted.push(result); });
  });
  await Promise.all(results);
  assert.deepEqual(accepted, ['new A']);
  const current = requests.start();
  requests.cancel();
  assert.equal(current.aborted, true);
});

test('denied, disabled, inaccurate, stale and timed-out GPS permit fallback selection', async () => {
  const signal = new AbortController().signal;
  const fix = { coordinate: center, timestamp: Date.now(), accuracy: 50 };
  let reads = 0;
  const source = {
    requestPermission: async () => true,
    hasServices: async () => true,
    getFix: async () => { reads += 1; return fix; },
  };
  await assert.rejects(readLocation({ ...source, requestPermission: async () => false }, signal),
    { message: LOCATION_MESSAGES.permission });
  await assert.rejects(readLocation({ ...source, hasServices: async () => false }, signal),
    { message: LOCATION_MESSAGES.services });
  assert.equal(reads, 0);
  await assert.rejects(readLocation({ ...source, getFix: () => new Promise(() => {}) }, signal, 1),
    { message: LOCATION_MESSAGES.unavailable });
  await assert.rejects(readLocation({ ...source, getFix: async () => ({ ...fix, accuracy: 501 }) }, signal),
    { message: LOCATION_MESSAGES.coarse });
  await assert.rejects(readLocation({ ...source, getFix: async () => ({ ...fix, timestamp: fix.timestamp - 121_000 }) }, signal),
    { message: LOCATION_MESSAGES.stale });
  assert.equal(isCurrentFix(fix, fix.timestamp + LOCATION_MAX_AGE_MS), true);
  assert.equal(isCurrentFix(fix, fix.timestamp + LOCATION_MAX_AGE_MS + 1), false);
  assert.deepEqual(await readLocation(source, signal), fix);
});

test('expired device points retain the coordinates chosen for the route', () => {
  const location = { name: '현재 위치', coordinate: center, fromDevice: true };
  const destination = { name: '역삼역', coordinate: { latitude: 37.5006, longitude: 127.0364 } };
  assert.deepEqual(asFixedLocation(location), { name: '마지막으로 확인한 위치', coordinate: center, fromDevice: false });
  assert.equal(asFixedLocation(destination), destination);
});

test('resumed fixes honor permission and services loss before considering age', () => {
  const fix = { coordinate: center, timestamp: now, accuracy: 50 };
  const expiredAt = now + LOCATION_MAX_AGE_MS + 1;
  assert.deepEqual(locationLossOnResume(fix, false, true, expiredAt), { reason: 'access', message: LOCATION_MESSAGES.permission });
  assert.deepEqual(locationLossOnResume(fix, true, false, expiredAt), { reason: 'access', message: LOCATION_MESSAGES.services });
  assert.deepEqual(locationLossOnResume(fix, true, true, expiredAt), { reason: 'expired', message: LOCATION_MESSAGES.stale });
  assert.equal(locationLossOnResume(fix, true, true, now), undefined);
});

test('storage hydrates before writes, serializes edits and reopens with IDs and queries only', async () => {
  let persisted = JSON.stringify({ favorites: [{ stationId: 'stored', addedAt: '2026-11-18T10:00:00Z' }], recent: [] });
  let simultaneousWrites = 0;
  const adapter = {
    getItem: async () => persisted,
    setItem: async (_key, value) => {
      simultaneousWrites += 1;
      assert.equal(simultaneousWrites, 1);
      await Promise.resolve();
      persisted = value;
      simultaneousWrites -= 1;
    },
  };
  const store = createRiderStorage(adapter);
  await Promise.all([store.toggleFavorite('new'), store.addRecent(' 강남역 '), store.addRecent('강남역')]);
  const reopened = await createRiderStorage(adapter).load();
  assert.deepEqual(reopened.favorites.map(({ stationId }) => stationId), ['new', 'stored']);
  assert.deepEqual(reopened.recent.map(({ query }) => query), ['강남역']);
  assert.deepEqual(Object.keys(reopened.recent[0]).sort(), ['query', 'usedAt']);
  assert.deepEqual(Object.keys(reopened.favorites[0]).sort(), ['addedAt', 'stationId']);
  await Promise.all([store.toggleFavorite('stored'), store.clearRecent()]);
  assert.deepEqual((await createRiderStorage(adapter).load()).favorites.map(({ stationId }) => stationId), ['new']);
  assert.deepEqual((await createRiderStorage(adapter).load()).recent, []);
});

test('a malformed favorites field keeps the stored recent searches', () => {
  const recent = [{ query: '강남역', usedAt: 1 }];
  assert.deepEqual(parseSavedItems(JSON.stringify({ favorites: {}, recent })), { favorites: [], recent });
  assert.deepEqual(parseSavedItems(JSON.stringify({ favorites: [{ stationId: 'ST-1', addedAt: '2026-11-18T10:00:00Z' }], recent: 'x' })),
    { favorites: [{ stationId: 'ST-1', addedAt: '2026-11-18T10:00:00Z' }], recent: [] });
  assert.deepEqual(parseSavedItems('not json'), { favorites: [], recent: [] });
  assert.deepEqual(parseSavedItems(null), { favorites: [], recent: [] });
});

test('route search keeps only drawable WALK, BIKE, WALK alternatives', async (t) => {
  const destination = { latitude: 37.5006, longitude: 127.0364 };
  const route = (routeId, types, encodedPolyline = '_p~iF~ps|U_ulLnnqC') => ({
    routeId, originStation: station, destinationStation: { ...station, stationId: 'ST-44' },
    totalDistanceMeters: 922, estimatedDurationSeconds: 558, walkingDistanceMeters: 222, bikeDistanceMeters: 700,
    rentalAvailability: 'AVAILABLE_AT_FETCH', returnAvailability: 'UNKNOWN',
    segments: types.map((type) => ({ type, distanceMeters: 10, durationSeconds: 5, encodedPolyline, steps: [] })),
  });
  const walkBikeWalk = ['WALK', 'BIKE', 'WALK'];
  let alternatives = [];
  let recommended = route('recommended', walkBikeWalk);
  useAdapter(t, async (config) => success(config, { recommended, alternatives, attribution: 'fixture' }));
  const signal = new AbortController().signal;
  alternatives = [route('two-legs', ['WALK', 'BIKE']), route('valid', walkBikeWalk), route('single-point', walkBikeWalk, '_p~iF~ps|U')];
  const result = await fetchRecommendedRoute(center, destination, {}, { configured: true }, signal);
  assert.deepEqual(result.alternatives.map(({ routeId }) => routeId), ['valid']);
  recommended = route('broken', ['WALK', 'BIKE']);
  await assert.rejects(fetchRecommendedRoute(center, destination, {}, { configured: true }, signal), /Invalid route legs/);
});

test('station detail never replaces a newer list observation', () => {
  const listed = { ...station, parkingBikeTotCnt: 5, realtimeUpdatedAt: '2026-11-20T03:01:00Z' };
  const older = { ...station, address: '서울 강남구', parkingBikeTotCnt: 2, realtimeUpdatedAt: '2026-11-20T03:00:00Z' };
  const kept = mergeStationDetail(listed, older);
  assert.equal(kept.parkingBikeTotCnt, 5);
  assert.equal(kept.realtimeUpdatedAt, listed.realtimeUpdatedAt);
  assert.equal(kept.address, '서울 강남구');
  const newer = { ...older, parkingBikeTotCnt: 7, realtimeUpdatedAt: '2026-11-20T03:02:00Z' };
  assert.equal(mergeStationDetail(listed, newer).parkingBikeTotCnt, 7);
  assert.equal(mergeStationDetail({ ...listed, realtimeUpdatedAt: null }, older).parkingBikeTotCnt, 2);
  assert.equal(mergeStationDetail(listed, { ...newer, stationId: 'other' }), listed);
  assert.equal(mergeStationDetail(listed, undefined), listed);
});
