import { useEffect, useState, type ReactNode } from "react";
import { fetchClientConfig, loadMaintenanceState, onMaintenance, type MaintenanceState } from "../lib/clientConfig";
import { Icon } from "./Icon";

/**
 * Phase 3 A7: `GET /api/client-config` runs alongside the normal start (no added delay); maintenance=true, or any
 * API call answered with 503 MAINTENANCE_MODE, shows the maintenance screen instead of the children. A failed or
 * slow config request is ignored (spec 6), so the app starts normally. Moved out of App.tsx unchanged.
 */
export function MaintenanceGate({ children }: { children: ReactNode }) {
  const [maintenance, setMaintenance] = useState<MaintenanceState>({active: false});
  const [checking, setChecking] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    void loadMaintenanceState(fetchClientConfig, controller.signal).then(state => {
      if (!controller.signal.aborted && state.active) setMaintenance(state);
    });
    const unsubscribe = onMaintenance(setMaintenance);
    return () => { controller.abort(); unsubscribe(); };
  }, []);
  async function retry() {
    setChecking(true);
    // Failure on retry also lets the user in: if maintenance is still on, the next API call brings the screen back.
    const state = await loadMaintenanceState();
    setChecking(false);
    setMaintenance(state);
  }
  if (maintenance.active) return (
    <main className="maintenance-screen" aria-labelledby="maintenance-title">
      <img className="brand-logo" src="/branding/ddareung-rider-logo.png" alt="따릉라이더" width={2172} height={724} />
      <Icon name="info" size={34} />
      <h1 id="maintenance-title">서비스 점검 중</h1>
      <p role="status">{maintenance.message}</p>
      <button className="primary-button" onClick={() => void retry()} disabled={checking}>
        <Icon name="refresh" size={18} />{checking ? "확인하고 있어요…" : "다시 시도"}
      </button>
    </main>
  );
  return <>{children}</>;
}
