import { parseFavorites, type Favorite } from '../../../shared/rider-contract/favoriteStorage.ts';
import { parseRecentPlaces, type RecentPlace } from '../../../shared/rider-contract/recentPlaceStorage.ts';

const STORAGE_KEY = 'ddareungrider:mobile-saved:v1';

interface StorageAdapter {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
}

interface SavedItems {
  favorites: Favorite[];
  recent: RecentPlace[];
}

function parseSavedItems(raw: string | null): SavedItems {
  try {
    const parsed = JSON.parse(raw ?? '{}');
    return {
      favorites: parseFavorites(JSON.stringify({ schemaVersion: 1, items: parsed.favorites ?? [] })),
      recent: parseRecentPlaces(JSON.stringify(parsed.recent)),
    };
  } catch {
    return { favorites: [], recent: [] };
  }
}

export function createRiderStorage(adapter: StorageAdapter) {
  let state: SavedItems | undefined;
  let pendingRead: Promise<SavedItems> | undefined;
  let queue = Promise.resolve();
  const listeners = new Set<() => void>();

  function publish(next: SavedItems) {
    state = next;
    listeners.forEach((listener) => listener());
    return next;
  }

  function load(): Promise<SavedItems> {
    if (state) return Promise.resolve(state);
    pendingRead ??= adapter.getItem(STORAGE_KEY).then((value) => publish(parseSavedItems(value)))
      .finally(() => { pendingRead = undefined; });
    return pendingRead;
  }

  function update(reduce: (previous: SavedItems) => SavedItems) {
    const write = queue.then(async () => {
      const next = reduce(await load());
      await adapter.setItem(STORAGE_KEY, JSON.stringify(next));
      publish(next);
    });
    queue = write.catch(() => {});
    return write;
  }

  return {
    load,
    snapshot: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
    toggleFavorite(stationId: string) {
      return update((previous) => {
        const exists = previous.favorites.some((favorite) => favorite.stationId === stationId);
        const favorites = exists
          ? previous.favorites.filter((favorite) => favorite.stationId !== stationId)
          : [{ stationId, addedAt: new Date().toISOString() }, ...previous.favorites].slice(0, 50);
        return { ...previous, favorites };
      });
    },
    addRecent(query: string) {
      return update((previous) => ({
        ...previous,
        recent: parseRecentPlaces(JSON.stringify([{ query, usedAt: Date.now() }, ...previous.recent])),
      }));
    },
    clearRecent() {
      return update((previous) => ({ ...previous, recent: [] }));
    },
  };
}
