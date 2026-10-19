export type Theme = 'light' | 'dark';
export const THEME_KEY = 'ddareungrider:theme:v1';
export function parseTheme(value: string | null): Theme | null {
  return value === 'light' || value === 'dark' ? value : null;
}
/** Automatic mode follows the device's local time: 07:00–18:59 is light. */
export function resolveTheme(preference: Theme | null, date = new Date()): Theme {
  return preference ?? (date.getHours() >= 7 && date.getHours() < 19 ? 'light' : 'dark');
}
export function readThemePreference(storage: Pick<Storage, 'getItem'>): Theme | null {
  try { return parseTheme(storage.getItem(THEME_KEY)); } catch { return null; }
}
