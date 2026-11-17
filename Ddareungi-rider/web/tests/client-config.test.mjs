import test from "node:test";
import assert from "node:assert/strict";
import {
  configApi, fetchClientConfig, loadMaintenanceState, maintenanceFromConfig, maintenanceFromError, onMaintenance,
  reportApiError, MAINTENANCE_FALLBACK_MESSAGE,
} from "../src/lib/clientConfig.ts";
import { api } from "../src/lib/api.ts";
import { shouldRetryQuery } from "../src/lib/apiEnvelope.ts";

const webConfig = (overrides = {}) => ({ platform: "web", minimumVersion: null, latestVersion: null, forceUpdate: false,
  updateAvailable: false, maintenance: false, maintenanceMessage: null, supportedApiVersions: ["v1"], storeUrl: null,
  ...overrides });
const envelope = (data) => ({ success: true, data, meta: { requestId: "r" }, timestamp: "t" });
const maintenance503 = (message = "오늘 02:00까지 점검합니다.") => ({ isAxiosError: true, response: { status: 503, headers: {},
  data: { success: false, error: { code: "MAINTENANCE_MODE", message, details: [] }, meta: { requestId: "r" }, timestamp: "t" } } });

test("client-config uses its own /api client (not /api/v1) and asks with platform=web", async (t) => {
  const calls = [];
  const original = configApi.get;
  configApi.get = async (url, config) => { calls.push({ url, config }); return { data: envelope(webConfig()) }; };
  t.after(() => { configApi.get = original; });
  assert.deepEqual(await fetchClientConfig(), webConfig());
  assert.equal(configApi.defaults.baseURL, "/api");
  assert.equal(api.defaults.baseURL, "/api/v1");
  assert.equal(calls[0].url, "/client-config");
  assert.deepEqual(calls[0].config.params, { platform: "web" });
});

test("maintenance=true blocks with the server message (fallback when blank); anything else lets the user in", () => {
  assert.deepEqual(maintenanceFromConfig(webConfig({ maintenance: true, maintenanceMessage: " 점검 중 " })),
    { active: true, message: "점검 중" });
  assert.deepEqual(maintenanceFromConfig(webConfig({ maintenance: true, maintenanceMessage: null })),
    { active: true, message: MAINTENANCE_FALLBACK_MESSAGE });
  assert.deepEqual(maintenanceFromConfig(webConfig()), { active: false });
  assert.deepEqual(maintenanceFromConfig(webConfig({ maintenance: "true" })), { active: false });
  assert.deepEqual(maintenanceFromConfig(null), { active: false });
});

test("start-up check: failures, timeouts and error envelopes are ignored (spec 6), maintenance is reported", async () => {
  assert.deepEqual(await loadMaintenanceState(async () => webConfig({ maintenance: true, maintenanceMessage: "점검" })),
    { active: true, message: "점검" });
  assert.deepEqual(await loadMaintenanceState(async () => { throw new Error("Network Error"); }), { active: false });
  assert.deepEqual(await loadMaintenanceState(async () => { throw Object.assign(new Error("timeout"), { code: "ECONNABORTED" }); }),
    { active: false });
  assert.deepEqual(await loadMaintenanceState(async () => { throw { response: { status: 500, data: "" } }; }), { active: false });
  // A body that is not an envelope makes fetchClientConfig throw (unwrap) and is ignored the same way.
  const original = configApi.get;
  configApi.get = async () => ({ data: "<html>" });
  try { assert.deepEqual(await loadMaintenanceState(), { active: false }); } finally { configApi.get = original; }
});

test("503 MAINTENANCE_MODE from any API call reaches the maintenance listeners; other errors do not", () => {
  const seen = [];
  const off = onMaintenance((state) => seen.push(state));
  try {
    assert.equal(reportApiError(maintenance503()), true);
    assert.equal(reportApiError({ response: { status: 503, data: { success: false,
      error: { code: "SERVICE_UNAVAILABLE", message: "x", details: [] }, meta: {}, timestamp: "t" } } }), false);
    assert.equal(reportApiError({ response: { status: 503, data: "" } }), false);
    assert.equal(reportApiError(new Error("Network Error")), false);
  } finally { off(); }
  assert.deepEqual(seen, [{ active: true, message: "오늘 02:00까지 점검합니다." }]);
  assert.equal(reportApiError(maintenance503()), true);   // unsubscribed: no further calls
  assert.equal(seen.length, 1);
  assert.deepEqual(maintenanceFromError(maintenance503("  ")), { active: true, message: MAINTENANCE_FALLBACK_MESSAGE });
});

test("a maintenance error is not retried by react-query; ordinary errors keep their single retry", () => {
  assert.equal(shouldRetryQuery(0, maintenance503()), false);
  assert.equal(shouldRetryQuery(0, new Error("Network Error")), true);
});
