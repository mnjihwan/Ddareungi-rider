import assert from 'node:assert/strict';
import { test } from 'node:test';
import app from '../app.json' with { type: 'json' };
import { shouldRetryQuery } from '../src/shared/rider-contract/apiEnvelope.ts';

globalThis.__DEV__ = true;

const { apiClient } = await import('../src/shared/api/client.ts');
const { clientConfigApi, loadMaintenanceState } = await import('../src/shared/api/clientConfig.ts');
const { onMaintenance } = await import('../src/shared/api/maintenanceState.ts');

const android = { platform: 'android', appVersion: app.expo.version, buildNumber: app.expo.android.versionCode };
const ios = { platform: 'ios', appVersion: app.expo.version, buildNumber: app.expo.ios.buildNumber };
const web = { platform: 'web' };
const fallback = '서비스 점검 중이에요. 잠시 후 다시 시도해 주세요.';

function mockAdapter(t, client, adapter) {
  const original = client.defaults.adapter;
  client.defaults.adapter = adapter;
  t.after(() => { client.defaults.adapter = original; });
}

function configBody(data) {
  return { status: 200, headers: {}, data: { success: true, data } };
}

function failure(status, code, message) {
  return { response: { status, data: { success: false, error: { code, message } } } };
}

test('startup and retry query the same host outside v1 with native version metadata and a five-second timeout', async (t) => {
  const seen = [];
  const { signal } = new AbortController();
  mockAdapter(t, clientConfigApi, async (config) => {
    seen.push(config);
    return configBody({
      maintenance: seen.length === 1,
      maintenanceMessage: '  오늘 02:00까지 점검합니다.  ',
      forceUpdate: true,
    });
  });

  assert.deepEqual(await loadMaintenanceState(android, signal), { active: true, message: '오늘 02:00까지 점검합니다.' });
  assert.deepEqual(await loadMaintenanceState(android, signal), { active: false });
  assert.deepEqual(await loadMaintenanceState(ios, signal), { active: false });
  assert.deepEqual(await loadMaintenanceState(web, signal), { active: false });
  assert.deepEqual(seen.map((config) => config.params), [android, android, ios, web]);
  for (const config of seen) {
    assert.equal(config.baseURL, apiClient.defaults.baseURL);
    assert.equal(config.url, '/api/client-config');
    assert.equal(config.method, 'get');
    assert.equal(config.timeout, 5_000);
    assert.equal(config.signal, signal);
  }
});

test('only the explicit maintenance flag blocks startup, using the fallback for blank text', async (t) => {
  const responses = [
    configBody({ maintenance: true, maintenanceMessage: ' ' }),
    configBody({ maintenance: true, maintenanceMessage: null }),
    configBody({ maintenance: 'true' }),
    configBody(null),
    { status: 200, headers: {}, data: '<html>' },
  ];
  mockAdapter(t, clientConfigApi, async () => responses.shift());
  assert.deepEqual(await loadMaintenanceState(android), { active: true, message: fallback });
  assert.deepEqual(await loadMaintenanceState(android), { active: true, message: fallback });
  assert.deepEqual(await loadMaintenanceState(android), { active: false });
  assert.deepEqual(await loadMaintenanceState(android), { active: false });
  assert.deepEqual(await loadMaintenanceState(android), { active: false });
});

test('config network, timeout and HTTP failures allow the home screen without emitting maintenance', async (t) => {
  const errors = [new Error('Network Error'), { code: 'ECONNABORTED' }, failure(503, 'MAINTENANCE_MODE', '점검')];
  const seen = [];
  t.after(onMaintenance((state) => seen.push(state)));
  mockAdapter(t, clientConfigApi, async () => { throw errors.shift(); });
  for (let attempt = 0; attempt < 3; attempt += 1) {
    assert.deepEqual(await loadMaintenanceState(ios), { active: false });
  }
  assert.deepEqual(seen, []);
});

test('the shared rider API reports 503 maintenance from GET and POST while preserving the original rejection', async (t) => {
  const seen = [];
  const unsubscribe = onMaintenance((state) => seen.push(state));
  t.after(unsubscribe);
  const maintenance = failure(503, 'MAINTENANCE_MODE', '서비스 재시작 중');
  const errors = [
    failure(503, 'SERVICE_UNAVAILABLE', '길찾기를 사용할 수 없어요.'),
    failure(500, 'MAINTENANCE_MODE', '점검'),
    failure(503, 'MAINTENANCE_MODE', ''),
    maintenance,
    maintenance,
  ];
  mockAdapter(t, apiClient, async () => { throw errors.shift(); });
  await assert.rejects(apiClient.get('/api/v1/stations/nearby'));
  await assert.rejects(apiClient.get('/api/v1/stations/search'));
  assert.deepEqual(seen, []);
  await assert.rejects(apiClient.get('/api/v1/places/search'));
  await assert.rejects(apiClient.post('/api/v1/routes/search', {}), (error) => error === maintenance);
  unsubscribe();
  await assert.rejects(apiClient.get('/api/v1/stations/nearby'));
  assert.deepEqual(seen, [{ active: true, message: fallback }, { active: true, message: '서비스 재시작 중' }]);
  assert.equal(shouldRetryQuery(0, maintenance), false);
});
