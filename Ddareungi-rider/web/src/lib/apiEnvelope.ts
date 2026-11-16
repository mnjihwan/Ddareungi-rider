// Common API envelope (backend spec 8). Kept free of axios/Vite imports so node tests can load it directly.

export interface ApiMeta {
  requestId: string | null;
  partial?: boolean;
  warnings?: { code: string }[];
}

export interface ApiEnvelope<T> {
  success: true;
  data: T;
  meta: ApiMeta;
  timestamp: string;
}

export interface ApiErrorDetail {
  field: string;
  reason: string;
}

export interface ApiErrorEnvelope {
  success: false;
  error: { code: string; message: string; details: ApiErrorDetail[] };
  meta: ApiMeta;
  timestamp: string;
}

/** Returns `data` of a success envelope; anything else is treated as an invalid response. */
export function unwrap<T>(body: ApiEnvelope<T>): T {
  if (!body || typeof body !== "object" || body.success !== true || !("data" in body)) {
    throw new Error("Invalid API response");
  }
  return body.data;
}

/** The failure envelope carried by an HTTP client error (axios-shaped), if any. */
export function apiError(error: unknown): ApiErrorEnvelope["error"] | null {
  const body = (error as { response?: { data?: unknown } } | null)?.response?.data as
    | Partial<ApiErrorEnvelope>
    | undefined;
  if (!body || typeof body !== "object" || body.success !== false || !body.error) return null;
  const { code, message } = body.error;
  return typeof code === "string" && typeof message === "string" ? body.error : null;
}

/** User-facing message from a failure envelope, or the fallback for network errors and unexpected bodies. */
export function apiErrorMessage(error: unknown, fallback: string): string {
  const message = apiError(error)?.message.trim();
  if (message) return message;
  // A 429 without a usable envelope (e.g. from a proxy) still tells the user to wait instead of the generic fallback.
  return isRateLimited(error) ? RATE_LIMIT_MESSAGE : fallback;
}

// ---- 429 RATE_LIMIT_EXCEEDED (backend spec 24) ----

export const RATE_LIMIT_MESSAGE = "요청이 많아요. 잠시 후 다시 시도해 주세요.";
/** A Retry-After longer than this is not waited for in-place; the query fails and the user retries later. */
export const MAX_RETRY_AFTER_MS = 60_000;

type HttpErrorShape = { response?: { status?: unknown; headers?: unknown } } | null | undefined;

/** True for HTTP 429 or a RATE_LIMIT_EXCEEDED failure envelope. */
export function isRateLimited(error: unknown): boolean {
  return (error as HttpErrorShape)?.response?.status === 429 || apiError(error)?.code === "RATE_LIMIT_EXCEEDED";
}

function headerValue(headers: unknown, name: string): unknown {
  if (!headers || typeof headers !== "object") return undefined;
  const getter = (headers as { get?: unknown }).get;
  if (typeof getter === "function") {
    const value = (getter as (key: string) => unknown).call(headers, name);
    if (value !== undefined && value !== null) return value;
  }
  const key = Object.keys(headers).find((k) => k.toLowerCase() === name.toLowerCase());
  return key === undefined ? undefined : (headers as Record<string, unknown>)[key];
}

/**
 * `Retry-After` of a 429 response in milliseconds (delta-seconds or an HTTP date, RFC 9110 10.2.3), or null when
 * absent/invalid or the error is not a rate limit. Past dates give 0.
 */
export function retryAfterMs(error: unknown, now = Date.now()): number | null {
  if (!isRateLimited(error)) return null;
  const raw = headerValue((error as HttpErrorShape)?.response?.headers, "retry-after");
  const text = typeof raw === "number" ? String(raw) : typeof raw === "string" ? raw.trim() : "";
  if (!text) return null;
  if (/^\d+$/.test(text)) return Number(text) * 1000;
  // Only HTTP-date shaped values (they contain a weekday/month name); Date.parse would accept "-1" as a year.
  const date = /[a-z]/i.test(text) ? Date.parse(text) : NaN;
  return Number.isFinite(date) ? Math.max(0, date - now) : null;
}

/**
 * react-query `retry`: non-429 errors keep the previous `retry: 1` behaviour. A 429 is never retried immediately:
 * only once, and only when the server sent a Retry-After within MAX_RETRY_AFTER_MS (waited by `queryRetryDelay`).
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= 1) return false;
  if (!isRateLimited(error)) return true;
  const wait = retryAfterMs(error);
  return wait !== null && wait <= MAX_RETRY_AFTER_MS;
}

/** react-query `retryDelay`: Retry-After for a 429, otherwise react-query's default exponential backoff. */
export function queryRetryDelay(failureCount: number, error: unknown): number {
  const wait = retryAfterMs(error);
  if (wait !== null) return Math.min(wait, MAX_RETRY_AFTER_MS);
  return Math.min(1000 * 2 ** failureCount, 30_000);
}
