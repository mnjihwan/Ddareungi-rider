import { useEffect, useId, useRef, useState } from 'react';

const hours = Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, '0'));
const minutes = Array.from({ length: 12 }, (_, index) => String(index * 5).padStart(2, '0'));

export function ComparisonTimePicker({ value, currentTime, onChange }: {
  value: string; currentTime: string; onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [hour, setHour] = useState('00');
  const [minute, setMinute] = useState('00');
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const hourInput = useRef<HTMLSelectElement>(null);
  const id = useId();
  const displayValue = value === 'now' ? '지금' : value;

  useEffect(() => {
    if (!open) return;
    hourInput.current?.focus({ preventScroll: true });
    const dismiss = (event: PointerEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', dismiss);
    return () => document.removeEventListener('pointerdown', dismiss);
  }, [open]);

  function apply(next: string) {
    onChange(next);
    setOpen(false);
    trigger.current?.focus({ preventScroll: true });
  }

  return <div className="comparison-time" ref={root} onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
  }} onKeyDown={event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      setOpen(false);
      trigger.current?.focus({ preventScroll: true });
    }
  }}>
    <button ref={trigger} type="button" className="comparison-time-trigger"
      aria-label={`시간대 선택 ${displayValue}`} aria-haspopup="dialog" aria-expanded={open}
      aria-controls={open ? id : undefined} onClick={() => {
        if (!open) {
          const [nextHour, nextMinute] = (value === 'now' ? currentTime : value).split(':');
          setHour(nextHour);
          setMinute(nextMinute);
        }
        setOpen(!open);
      }}><span>{displayValue}</span><svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true" focusable="false"><path d="m3 4.5 3 3 3-3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg></button>
    {open && <div id={id} className="comparison-time-popover" role="dialog" aria-label="시간대 선택">
      <div className="comparison-time-columns">
        <label>시<select ref={hourInput} size={6} value={hour} onChange={event => setHour(event.target.value)}>
          {hours.map(item => <option key={item} value={item}>{item}시</option>)}
        </select></label>
        <label>분<select size={6} value={minute} onChange={event => setMinute(event.target.value)}>
          {minutes.map(item => <option key={item} value={item}>{item}분</option>)}
        </select></label>
      </div>
      <div className="comparison-time-actions">
        <button type="button" onClick={() => apply('now')}>지금</button>
        <button type="button" className="comparison-time-apply" onClick={() => apply(`${hour}:${minute}`)}>적용</button>
      </div>
    </div>}
  </div>;
}
