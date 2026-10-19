import { useEffect, useLayoutEffect, useState } from 'react';
import { parseTheme, readThemePreference, resolveTheme, THEME_KEY, type Theme } from './theme';

export function useTheme() {
  const [preference, setPreference] = useState<Theme | null>(() => {
    try { return readThemePreference(localStorage); } catch { return null; }
  });
  const [now, setNow] = useState(() => new Date());
  const theme = resolveTheme(preference, now);
  useEffect(() => {
    const update = () => setNow(new Date());
    const timer = window.setInterval(update, 30_000);
    const storage = (event: StorageEvent) => {
      if (event.key === THEME_KEY || event.key === null) {
        setPreference(event.key === null ? null : parseTheme(event.newValue));
        update();
      }
    };
    window.addEventListener('storage', storage);
    window.addEventListener('focus', update);
    document.addEventListener('visibilitychange', update);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('storage', storage);
      window.removeEventListener('focus', update);
      document.removeEventListener('visibilitychange', update);
    };
  }, []);
  useLayoutEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#141d2a' : '#2563a6');
  }, [theme]);
  function toggle() {
    const next = theme === 'dark' ? 'light' : 'dark';
    setPreference(next);
    try { localStorage.setItem(THEME_KEY, next); } catch { /* Keep the choice for this session. */ }
  }
  return { theme, toggle };
}
