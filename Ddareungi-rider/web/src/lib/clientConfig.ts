import axios from "axios";
import { apiError, unwrap, type ApiEnvelope } from "./apiEnvelope.ts";

/**
 * `GET /api/client-config` (spec 6, phase 3 A7). It lives outside `/api/v1`, so it has its own client with base URL
 * `/api` (the shared `api` instance is `/api/v1`). The web always asks with `platform=web`; for web the version
 * fields are null and both update flags false, so only the maintenance fields matter here.
 */
export interface ClientConfig {
  platform: string;
  minimumVersion: string | null;
  latestVersion: string | null;
  forceUpdate: boolean;
  updateAvailable: boolean;
  maintenance: boolean;
  maintenanceMessage: string | null;
  supportedApiVersions: string[];
  storeUrl: string | null;
}

/** Inactive, or active with the text shown on the maintenance screen. */
export type MaintenanceState = { active: false } | { active: true; message: string };

export const MAINTENANCE_FALLBACK_MESSAGE = "서비스 점검 중이에요. 잠시 후 다시 시도해 주세요.";
/** The start-up check must never hold the app back for long: a slow answer is treated like a failure. */
export const CLIENT_CONFIG_TIMEOUT_MS = 5_000;
const INACTIVE: MaintenanceState = { active: false };

export const configApi = axios.create({ baseURL: "/api", timeout: CLIENT_CONFIG_TIMEOUT_MS });

export async function fetchClientConfig(signal?: AbortSignal): Promise<ClientConfig> {
  return unwrap((await configApi.get<ApiEnvelope<ClientConfig>>("/client-config",
    { params: { platform: "web" }, signal })).data);
}

function message(text: unknown): string {
  return typeof text === "string" && text.trim() ? text.trim() : MAINTENANCE_FALLBACK_MESSAGE;
}

/** Only an explicit `maintenance: true` blocks the app; anything else (missing, malformed) lets the user in. */
export function maintenanceFromConfig(config: unknown): MaintenanceState {
  if (!config || typeof config !== "object") return INACTIVE;
  const value = config as Partial<ClientConfig>;
  return value.maintenance === true ? { active: true, message: message(value.maintenanceMessage) } : INACTIVE;
}

/**
 * Start-up check. Spec 6: a failed config request is not a reason to block the app (nor a forced update), so
 * network errors, timeouts, error envelopes and invalid bodies all resolve to "inactive".
 */
export async function loadMaintenanceState(
  fetcher: (signal?: AbortSignal) => Promise<unknown> = fetchClientConfig,
  signal?: AbortSignal,
): Promise<MaintenanceState> {
  try {
    return maintenanceFromConfig(await fetcher(signal));
  } catch {
    return INACTIVE;
  }
}

/** A `/api/v1/**` call blocked by maintenance (decision 21): 503 with the `MAINTENANCE_MODE` envelope. */
export function maintenanceFromError(error: unknown): MaintenanceState | null {
  const body = apiError(error);
  return body?.code === "MAINTENANCE_MODE" ? { active: true, message: message(body.message) } : null;
}

// Ordinary API failures are reported here (react-query cache callbacks and the route planner), so the app can switch
// to the maintenance screen however the 503 arrived. A tiny listener set keeps this module free of React.
type Listener = (state: MaintenanceState & { active: true }) => void;
const listeners = new Set<Listener>();

export function onMaintenance(listener: Listener): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** Notifies listeners when `error` is a maintenance response; returns whether it was one. */
export function reportApiError(error: unknown): boolean {
  const state = maintenanceFromError(error);
  if (!state?.active) return false;
  listeners.forEach(listener => listener(state));
  return true;
}
