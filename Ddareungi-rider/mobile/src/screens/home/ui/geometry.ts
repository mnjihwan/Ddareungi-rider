export type SheetLevel = 'peek' | 'mid' | 'full';

export const HEADER_HEIGHT = 64;
export const PEEK_SHEET_HEIGHT = 44;

export function sheetHeights(workspace: number, detail: boolean): Record<SheetLevel, number> {
  const full = Math.max(PEEK_SHEET_HEIGHT, workspace - 77);
  const mid = Math.min(full, Math.max(PEEK_SHEET_HEIGHT, workspace * 0.46, detail ? 381 : 0));
  return { peek: PEEK_SHEET_HEIGHT, mid, full };
}
