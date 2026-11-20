import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { createRiderStorage } from './riderStorage';

const storage = createRiderStorage(AsyncStorage);

export function useSavedItems() {
  const saved = useSyncExternalStore(storage.subscribe, storage.snapshot);
  const [error, setError] = useState<string>();

  useEffect(() => {
    let active = true;
    storage.load().catch(() => {
      if (active) setError('저장한 항목을 불러오지 못했어요.');
    });
    return () => { active = false; };
  }, []);

  async function apply(action: () => Promise<unknown>) {
    setError(undefined);
    try {
      await action();
    } catch {
      setError('기기에 저장하지 못했어요. 다시 시도해 주세요.');
    }
  }

  return {
    saved,
    error,
    reload: () => apply(storage.load),
    toggleFavorite: (id: string) => apply(() => storage.toggleFavorite(id)),
    addRecent: (query: string) => apply(() => storage.addRecent(query)),
    clearRecent: () => apply(storage.clearRecent),
  };
}
