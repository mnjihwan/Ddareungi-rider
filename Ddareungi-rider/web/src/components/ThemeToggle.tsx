import { useRef, useState, type CSSProperties } from 'react';
import type { Theme } from '../lib/theme';
import { Icon } from './Icon';

export function ThemeToggle({theme, onChange}: {theme: Theme; onChange: (theme: Theme) => void}) {
  const drag = useRef<{id: number; x: number; start: number; travel: number; moved: boolean} | null>(null);
  const skipClick = useRef(false);
  const [progress, setProgress] = useState<number | null>(null);
  const dark = theme === 'dark';
  function endDrag(cancelled: boolean) {
    const current = drag.current;
    if (current?.moved && !cancelled && progress !== null) onChange(progress >= .5 ? 'dark' : 'light');
    drag.current = null;
    setProgress(null);
  }
  return <button type="button" className="theme-toggle" role="switch" aria-label="다크 모드" aria-checked={dark}
    title={dark ? '라이트 모드로 전환' : '다크 모드로 전환'}
    data-dragging={progress !== null || undefined}
    style={{'--toggle-progress': progress ?? (dark ? 1 : 0)} as CSSProperties}
    onPointerDown={event=>{
      skipClick.current = false;
      if (event.button !== 0) return;
      const track = event.currentTarget.querySelector('.theme-toggle-track')!;
      if (getComputedStyle(track).display === 'none') return;
      const thumb = event.currentTarget.querySelector('.theme-toggle-thumb')!;
      drag.current = {id:event.pointerId,x:event.clientX,start:dark ? 1 : 0,
        travel:track.clientWidth-thumb.clientWidth-2*parseFloat(getComputedStyle(track).getPropertyValue('--thumb-inset')),moved:false};
      event.currentTarget.setPointerCapture(event.pointerId);
    }}
    onPointerMove={event=>{
      const current = drag.current;
      if (!current || current.id !== event.pointerId) return;
      const delta = event.clientX-current.x;
      if (!current.moved && Math.abs(delta)<5) return;
      current.moved=true;skipClick.current=true;
      setProgress(Math.max(0,Math.min(1,current.start+delta/current.travel)));
    }}
    onPointerUp={()=>endDrag(false)}
    onPointerCancel={()=>endDrag(true)}
    onLostPointerCapture={()=>{if(drag.current)endDrag(true);}}
    onClick={event=>{
      if (event.detail !== 0 && skipClick.current) {skipClick.current=false;return;}
      onChange(dark ? 'light' : 'dark');
    }}
    onKeyDown={event=>{
      if(event.key==='ArrowLeft'||event.key==='ArrowRight') {
        event.preventDefault();onChange(event.key==='ArrowRight'?'dark':'light');
      }
    }}>
    <span className="theme-toggle-compact" aria-hidden="true"><Icon name={dark ? 'sun' : 'moon'} size={21}/></span>
    <span className="theme-toggle-track" aria-hidden="true">
      <span className="theme-toggle-thumb" />
      <span className="theme-toggle-sun"><Icon name="sun" size={18}/></span>
      <span className="theme-toggle-moon"><Icon name="moon" size={18}/></span>
    </span>
  </button>;
}
