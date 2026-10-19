export const RECENT_PLACES_KEY = 'ddareungrider:recent-place-queries:v1';
export interface RecentPlace { query:string; usedAt:number; }

export function parseRecentPlaces(raw:string|null):RecentPlace[] {
  try {
    const items:unknown=JSON.parse(raw??'[]');
    if (!Array.isArray(items)) return [];
    const seen=new Set<string>();
    return items.filter(item=>item && typeof item.query==='string' && item.query.trim().length>=2 &&
      item.query.trim().length<=100 && Number.isFinite(item.usedAt) && item.usedAt>0)
      .sort((a,b)=>b.usedAt-a.usedAt).flatMap(item=>{
        const query=item.query.trim(), normalized=query.toLocaleLowerCase();
        if (seen.has(normalized)) return [];
        seen.add(normalized);
        // Keep only the user's query and usage time, never cached provider data.
        return [{query,usedAt:item.usedAt}];
      }).slice(0,5);
  } catch {return [];}
}
