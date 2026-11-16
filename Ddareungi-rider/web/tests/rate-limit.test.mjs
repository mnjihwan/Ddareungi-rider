import test from "node:test";
import assert from "node:assert/strict";
import {
  apiErrorMessage, isRateLimited, retryAfterMs, shouldRetryQuery, queryRetryDelay, RATE_LIMIT_MESSAGE, MAX_RETRY_AFTER_MS,
} from "../src/lib/apiEnvelope.ts";

const now = Date.parse("2026-11-16T10:00:00Z");
const envelope = { success: false, error: { code: "RATE_LIMIT_EXCEEDED", message: "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", details: [] },
  meta: { requestId: "r" }, timestamp: "t" };
const http = (status, data, headers = {}) => ({ isAxiosError: true, response: { status, data, headers } });

test("429 and RATE_LIMIT_EXCEEDED are recognised; other errors are not", () => {
  assert.equal(isRateLimited(http(429, "")), true);
  assert.equal(isRateLimited(http(400, envelope)), true);
  assert.equal(isRateLimited(http(500, "")), false);
  assert.equal(isRateLimited(new Error("Network Error")), false);
  assert.equal(isRateLimited(null), false);
});

test("Retry-After parsing: delta-seconds, HTTP date, case-insensitive and axios-style headers", () => {
  assert.equal(retryAfterMs(http(429, envelope, { "retry-after": "7" }), now), 7000);
  assert.equal(retryAfterMs(http(429, envelope, { "Retry-After": " 0 " }), now), 0);
  assert.equal(retryAfterMs(http(429, envelope, { "retry-after": 3 }), now), 3000);
  assert.equal(retryAfterMs(http(429, envelope, { "retry-after": new Date(now + 5000).toUTCString() }), now), 5000);
  assert.equal(retryAfterMs(http(429, envelope, { "retry-after": new Date(now - 5000).toUTCString() }), now), 0);
  const axiosHeaders = { get: (name) => (name.toLowerCase() === "retry-after" ? "2" : undefined) };
  assert.equal(retryAfterMs(http(429, envelope, axiosHeaders), now), 2000);
  // Missing, invalid, negative or on a non-429 response.
  assert.equal(retryAfterMs(http(429, envelope, {}), now), null);
  assert.equal(retryAfterMs(http(429, envelope, { "retry-after": "soon" }), now), null);
  assert.equal(retryAfterMs(http(429, envelope, { "retry-after": "-1" }), now), null);
  assert.equal(retryAfterMs(http(503, "", { "retry-after": "5" }), now), null);
});

test("retry policy: non-429 keeps retry:1; 429 never retries without Retry-After", () => {
  const other = http(500, "");
  assert.equal(shouldRetryQuery(0, other), true);
  assert.equal(shouldRetryQuery(1, other), false);
  assert.equal(shouldRetryQuery(0, new Error("Network Error")), true);
  assert.equal(shouldRetryQuery(0, http(429, envelope)), false);
  assert.equal(shouldRetryQuery(0, http(429, envelope, { "retry-after": "5" })), true);
  assert.equal(shouldRetryQuery(1, http(429, envelope, { "retry-after": "5" })), false);
  assert.equal(shouldRetryQuery(0, http(429, envelope, { "retry-after": String(MAX_RETRY_AFTER_MS / 1000 + 1) })), false);
});

test("retry delay waits for Retry-After on 429, default backoff otherwise", () => {
  assert.equal(queryRetryDelay(0, http(429, envelope, { "retry-after": "5" })), 5000);
  assert.equal(queryRetryDelay(0, http(500, "")), 1000);
  assert.equal(queryRetryDelay(1, http(500, "")), 2000);
  assert.equal(queryRetryDelay(10, http(500, "")), 30_000);
});

test("429 message: envelope message first, rate-limit message instead of the generic fallback otherwise", () => {
  assert.equal(apiErrorMessage(http(429, envelope), "fallback"), envelope.error.message);
  assert.equal(apiErrorMessage(http(429, "<html>Too Many Requests</html>"), "fallback"), RATE_LIMIT_MESSAGE);
  assert.match(RATE_LIMIT_MESSAGE, /요청이 많아.*잠시 후 다시 시도/);
  assert.equal(apiErrorMessage(http(500, ""), "fallback"), "fallback");
});
