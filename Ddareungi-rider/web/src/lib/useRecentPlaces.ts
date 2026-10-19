import {useEffect,useState} from 'react';
import {parseRecentPlaces,RECENT_PLACES_KEY} from './recentPlaceStorage';

export function useRecentPlaces() {
  const [items,setItems]=useState(()=>{
    try {return parseRecentPlaces(localStorage.getItem(RECENT_PLACES_KEY));} catch {return [];}
  });
  useEffect(()=>{
    const sync=(event:StorageEvent)=>{if(event.key===RECENT_PLACES_KEY || event.key===null)setItems(parseRecentPlaces(event.newValue));};
    window.addEventListener('storage',sync);
    return ()=>window.removeEventListener('storage',sync);
  },[]);
  function remember(query:string) {
    setItems(previous=>{
      const next=parseRecentPlaces(JSON.stringify([{query,usedAt:Date.now()},...previous.filter(item=>item.query!==query)]));
      try {localStorage.setItem(RECENT_PLACES_KEY,JSON.stringify(next));} catch { /* Keep session history when storage is unavailable. */ }
      return next;
    });
  }
  function clear() {setItems([]);try {localStorage.removeItem(RECENT_PLACES_KEY);} catch {}}
  return {items,remember,clear};
}
