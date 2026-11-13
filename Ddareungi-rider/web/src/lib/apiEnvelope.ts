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
  return message ? message : fallback;
}
