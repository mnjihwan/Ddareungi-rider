import { apiError } from '../rider-contract/apiEnvelope.ts';

type ActiveMaintenance = { active: true; message: string };
export type MaintenanceState = { active: false } | ActiveMaintenance;

const fallbackMessage = '서비스 점검 중이에요. 잠시 후 다시 시도해 주세요.';
const listeners = new Set<(state: ActiveMaintenance) => void>();

function activeMaintenance(message: unknown): ActiveMaintenance {
  return {
    active: true,
    message: (typeof message === 'string' && message.trim()) || fallbackMessage,
  };
}

export function maintenanceFromConfig(config: unknown): MaintenanceState {
  if (config && typeof config === 'object') {
    const { maintenance, maintenanceMessage } = config as {
      maintenance?: unknown;
      maintenanceMessage?: unknown;
    };
    if (maintenance === true) return activeMaintenance(maintenanceMessage);
  }
  return { active: false };
}

export function onMaintenance(listener: (state: ActiveMaintenance) => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function reportMaintenanceError(error: unknown): void {
  if ((error as { response?: { status?: unknown } } | null)?.response?.status !== 503) return;
  const detail = apiError(error);
  if (detail?.code !== 'MAINTENANCE_MODE') return;
  const state = activeMaintenance(detail.message);
  listeners.forEach((listener) => listener(state));
}
