import { createApiClient, unwrapApiData, type ApiEnvelope } from './client.ts';
import { maintenanceFromConfig, type MaintenanceState } from './maintenanceState.ts';

export type ClientConfigParams =
  | { platform: 'web' }
  | { platform: 'android' | 'ios'; appVersion: string; buildNumber: number | string };

// Client config is available during maintenance and fails open if it cannot be loaded.
export const clientConfigApi = createApiClient(5_000);

export async function loadMaintenanceState(
  params: ClientConfigParams,
  signal?: AbortSignal,
): Promise<MaintenanceState> {
  try {
    const { data } = await clientConfigApi.get<ApiEnvelope<unknown>>('/api/client-config', { params, signal });
    return maintenanceFromConfig(unwrapApiData(data));
  } catch {
    return { active: false };
  }
}
