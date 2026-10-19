import { useEffect, useState } from "react";
import {
  FAVORITES_KEY,
  parseFavorites,
  type Favorite,
} from "./favoriteStorage";

export function useFavorites() {
  const [message, setMessage] = useState("");
  const [items, setItems] = useState<Favorite[]>(() => {
    try {
      return parseFavorites(localStorage.getItem(FAVORITES_KEY));
    } catch {
      return [];
    }
  });
  useEffect(() => {
    try {
      parseFavorites(localStorage.getItem(FAVORITES_KEY));
    } catch {
      setMessage(
        "즐겨찾기를 읽지 못했어요. 이 화면에서는 계속 저장할 수 있습니다.",
      );
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key !== FAVORITES_KEY && event.key !== null) return;
      try {
        setItems(parseFavorites(event.key === null ? null : event.newValue));
      } catch {
        setMessage("즐겨찾기 저장 정보를 확인하지 못했어요.");
      }
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  function toggle(stationId: string) {
    const exists = items.some((item) => item.stationId === stationId);
    if (!exists && items.length >= 50) {
      setMessage("즐겨찾기는 최대 50개까지 저장할 수 있어요.");
      return;
    }
    const next = exists
      ? items.filter((item) => item.stationId !== stationId)
      : [...items, { stationId, addedAt: new Date().toISOString() }];
    setItems(next);
    try {
      localStorage.setItem(
        FAVORITES_KEY,
        JSON.stringify({ schemaVersion: 1, items: next }),
      );
      setMessage("");
    } catch {
      setMessage(
        "브라우저에 저장하지 못했어요. 이 화면을 열어둔 동안만 유지됩니다.",
      );
    }
  }
  return { items, ids: items.map((item) => item.stationId), toggle, message };
}
