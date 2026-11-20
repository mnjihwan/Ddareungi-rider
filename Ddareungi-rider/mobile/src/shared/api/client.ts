import axios from 'axios';
import { apiErrorMessage } from '../rider-contract/apiEnvelope.ts';
import { reportMaintenanceError } from './maintenanceState.ts';

export { unwrap as unwrapApiData, type ApiEnvelope } from '../rider-contract/apiEnvelope.ts';

const apiBaseUrl = process.env.EXPO_PUBLIC_API_BASE_URL?.trim() || (__DEV__ ? 'http://localhost:8080' : '');

export function createApiClient(timeout: number) {
  const client = axios.create({ baseURL: apiBaseUrl, timeout });
  client.interceptors.request.use((config) => {
    if (!__DEV__ && !/^https:\/\//i.test(apiBaseUrl)) {
      throw new Error('앱의 HTTPS API 주소를 확인해 주세요.');
    }
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
  return apiErrorMessage(error, '요청을 처리하지 못했어요. 연결을 확인하고 다시 시도해 주세요.');
}
