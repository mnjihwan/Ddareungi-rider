import { useRef, type ReactNode } from 'react';

/** Native touch scrolling, with mouse dragging and drag-safe button clicks. */
export function SwipeRow({labelId, children}: {labelId: string; children: ReactNode}) {
  const drag = useRef<{id: number; x: number; scroll: number; moved: boolean} | null>(null);
  const suppressClick = useRef(false);
  return <div className="route-favorite-chips" role="group" aria-labelledby={labelId} tabIndex={0}
    onPointerDown={event=>{
      suppressClick.current = false;
      if (event.pointerType !== 'mouse' || event.button !== 0) return;
      drag.current = {id:event.pointerId, x:event.clientX, scroll:event.currentTarget.scrollLeft, moved:false};
    }}
    onPointerMove={event=>{
      const current = drag.current;
      if (!current || current.id !== event.pointerId) return;
      const delta = event.clientX - current.x;
      if (!current.moved && Math.abs(delta) < 6) return;
      current.moved = true;
      suppressClick.current = true;
      event.currentTarget.setPointerCapture(event.pointerId);
      event.currentTarget.dataset.dragging = 'true';
      event.currentTarget.scrollLeft = current.scroll - delta;
      event.preventDefault();
    }}
    onPointerUp={event=>{
      drag.current = null;
      delete event.currentTarget.dataset.dragging;
      if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    }}
    onPointerCancel={event=>{drag.current=null;delete event.currentTarget.dataset.dragging;}}
    onLostPointerCapture={event=>{drag.current=null;delete event.currentTarget.dataset.dragging;}}
    onPointerLeave={()=>{if (!drag.current?.moved) drag.current=null;}}
    onClickCapture={event=>{if(suppressClick.current){event.preventDefault();event.stopPropagation();suppressClick.current=false;}}}
  >{children}</div>;
}
