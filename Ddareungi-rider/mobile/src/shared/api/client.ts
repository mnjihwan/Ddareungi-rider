import axios from 'axios';
import { apiErrorMessage } from '../rider-contract/apiEnvelope.ts';
import { reportMaintenanceError } from './maintenanceState.ts';

export { unwrap as unwrapApiData, type ApiEnvelope } from '../rider-contract/apiEnvelope.ts';

const apiBaseUrl = process.env.EXPO_PUBLIC_API_BASE_URL?.trim() || (__DEV__ ? 'http://localhost:8080' : '');

export const API_CONFIG_MESSAGE = '앱의 HTTPS API 주소를 확인해 주세요.';
const FALLBACK_MESSAGE = '요청을 처리하지 못했어요. 연결을 확인하고 다시 시도해 주세요.';

/** The app build itself is misconfigured; retrying cannot help, so screens show this message as is. */
export class ApiConfigError extends Error {
  constructor(message = API_CONFIG_MESSAGE) {
    super(message);
    this.name = 'ApiConfigError';
  }
}

/** Release builds may only call an HTTPS API; development builds may use a LAN address over HTTP. */
export function ensureSecureApiBase(baseUrl: string, development: boolean): void {
  if (!development && !/^https:\/\//i.test(baseUrl)) throw new ApiConfigError();
}

export function createApiClient(timeout: number) {
  const client = axios.create({ baseURL: apiBaseUrl, timeout });
  client.interceptors.request.use((config) => {
    ensureSecureApiBase(apiBaseUrl, __DEV__);
    return config;
  });
  return client;
}

export const apiClient = createApiClient(15_000);
apiClient.interceptors.response.use(undefined, (error: unknown) => {
  reportMaintenanceError(error);
  throw error;
});

export function describeApiError(error: unknown): string {
  if (error instanceof ApiConfigError) return error.message;
  return apiErrorMessage(error, FALLBACK_MESSAGE);
}
