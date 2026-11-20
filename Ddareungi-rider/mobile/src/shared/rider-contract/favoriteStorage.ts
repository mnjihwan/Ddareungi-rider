export const FAVORITES_KEY = "bike:favorites:v1";
export interface Favorite {
  stationId: string;
  addedAt: string;
}

export function parseFavorites(raw: string | null): Favorite[] {
  if (!raw) return [];
  const parsed: unknown = JSON.parse(raw);
  if (
    typeof parsed !== "object" ||
    parsed === null ||
    !("schemaVersion" in parsed) ||
    parsed.schemaVersion !== 1 ||
    !("items" in parsed) ||
    !Array.isArray(parsed.items)
  )
    throw new Error("Invalid favorites");
  const ids = new Set<string>();
  return parsed.items
    .filter((item): item is Favorite => {
      if (
        !item ||
        typeof item !== "object" ||
        typeof item.stationId !== "string" ||
        !item.stationId.trim() ||
        item.stationId.length > 30 ||
        typeof item.addedAt !== "string" ||
        !Number.isFinite(Date.parse(item.addedAt)) ||
        ids.has(item.stationId)
      )
        return false;
      ids.add(item.stationId);
      return true;
    })
    .slice(0, 50)
    .map(({ stationId, addedAt }) => ({ stationId, addedAt }));
}
