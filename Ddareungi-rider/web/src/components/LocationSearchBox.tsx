import {useEffect, useRef, useState} from 'react';
import {useQuery} from '@tanstack/react-query';
import {searchPlaces, searchStations} from '../lib/api';
import {buildSearchSuggestions, matchingParts, type SearchSuggestion} from '../lib/searchSuggestions';
import type {Coordinates} from '../lib/types';
import {Icon} from './Icon';
import {searchMatchLabel} from '../lib/koreanSearch';

export function LocationSearchBox({id, label, text, center, variant = 'main', onTextChange, onSubmit, onChoose}: {
  id: string; label: string; text: string; center: Coordinates; variant?: 'main' | 'route';
  onTextChange: (text: string) => void; onSubmit?: () => void;
  onChoose: (suggestion: SearchSuggestion, query: string) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false), [composing, setComposing] = useState(false);
  const [debounced, setDebounced] = useState(''), [active, setActive] = useState(-1);
  const term = text.trim(), listId = `${id}-suggestions`;
  const accessibleLabel = variant === 'route' ? label.replace(' 장소 또는 주소 검색', '') : label;
  useEffect(() => {
    setActive(-1);
    if (!open || composing || term.length < 2) {setDebounced(''); return;}
    const timer = window.setTimeout(() => setDebounced(term), 350);
    return () => window.clearTimeout(timer);
  }, [term, open, composing]);
  useEffect(() => {
    const dismiss = (event: PointerEvent) => {if (!host.current?.contains(event.target as Node)) setOpen(false);};
    document.addEventListener('pointerdown', dismiss);
    return () => document.removeEventListener('pointerdown', dismiss);
  }, []);
  const results = useQuery({queryKey: ['search-suggestions', debounced, center],
    enabled: open && !composing && debounced === term && term.length >= 2,
    retry: false, gcTime: 0, refetchOnWindowFocus: false,
    queryFn: async ({signal}) => {
      const [places, stations] = await Promise.allSettled([searchPlaces(debounced, signal, center), searchStations(debounced, center, signal)]);
      if (places.status === 'rejected' && stations.status === 'rejected') throw new Error('Search unavailable');
      return {items: buildSearchSuggestions(places.status === 'fulfilled' ? places.value : [], stations.status === 'fulfilled' ? stations.value : [], debounced),
        placesUnavailable: places.status === 'rejected'};
    }});
  const visible = open && !composing && term.length >= 2;
  const items = debounced === term ? results.data?.items ?? [] : [];
  useEffect(() => {host.current?.querySelector(`#${id}-option-${active}`)?.scrollIntoView({block: 'nearest'});}, [active, id]);
  function choose(item: SearchSuggestion) {setOpen(false); setActive(-1); onChoose(item, term);}
  return <div className={`location-search-box ${variant}`} ref={host}
    onBlur={event => {if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false);}}>
    <form className={variant === 'main' ? 'search-form' : undefined} onSubmit={event => {
      event.preventDefault(); if (composing) return;
      setDebounced(term); setActive(-1);
      setOpen(variant === 'route' && term.length >= 2); onSubmit?.();
    }}>
      <Icon name={variant === 'main' ? 'search' : 'pin'} size={variant === 'main' ? 20 : 17}/>
      <input id={id} aria-label={accessibleLabel} placeholder={label} value={text} maxLength={100}
        role="combobox" aria-autocomplete="list" aria-expanded={visible} aria-controls={visible ? listId : undefined}
        aria-activedescendant={visible && active >= 0 && items[active] ? `${id}-option-${active}` : undefined}
        autoComplete="off" onFocus={() => setOpen(true)}
        onChange={event => {onTextChange(event.target.value);setOpen(true);}}
        onCompositionStart={() => setComposing(true)} onCompositionEnd={() => setComposing(false)}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || composing) return;
          if (event.key === 'Escape') {event.preventDefault();setOpen(false);setActive(-1);}
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();setOpen(true);
            if (items.length) setActive(previous => event.key === 'ArrowDown' ? (previous + 1) % items.length : (previous <= 0 ? items.length - 1 : previous - 1));
          }
          if (event.key === 'Enter' && visible && active >= 0 && items[active]) {event.preventDefault();choose(items[active]);}
        }}/>
      {text && <button type="button" aria-label={variant === 'main' ? '검색어 지우기' : `${accessibleLabel} 지우기`}
        onClick={() => {onTextChange('');setOpen(false);}}><Icon name="close" size={17}/></button>}
      <button type="submit" aria-label={variant === 'main' ? '검색' : `${accessibleLabel} 검색`}>
        <Icon name={variant === 'main' ? 'arrow' : 'search'} size={19}/>
      </button>
    </form>
    {visible && <div className={`search-suggestions ${variant === 'route' ? 'route-place-results' : ''}`}>
      {(debounced !== term || results.isFetching && !results.data) && <p role="status">장소를 찾고 있어요…</p>}
      {debounced === term && results.isError && <p role="alert">검색에 연결하지 못했어요. 다시 입력하거나 검색해 주세요.</p>}
      {debounced === term && results.data && !items.length && <p>일치하는 장소가 없어요. 다른 이름으로 검색해 주세요.</p>}
      <ul id={listId} role="listbox" aria-label="장소 검색 제안">
        {items.map((item, index) => <li key={item.key} id={`${id}-option-${index}`} role="option" aria-selected={active === index}>
          <button type="button" onMouseDown={event => event.preventDefault()} onClick={() => choose(item)}>
            <Icon name={item.kind === 'station' ? 'bike' : 'search'} size={17}/>
            <span className="suggestion-text"><b>{matchingParts(item.name, term).map((part, i) => part.matched ? <mark key={i}>{part.text}</mark> : part.text)}</b>
              <small>{[searchMatchLabel(item.name, item.address, term), item.address].filter(Boolean).join(' · ')}</small></span>
            <span className="suggestion-kind">{item.kind === 'station' ? '대여소' : item.place.categoryGroupCode === 'SW8' ? '지하철역' : '장소'}</span>
          </button>
        </li>)}
      </ul>
      {results.data?.placesUnavailable && <p className="subtle-notice">장소 연결을 확인하지 못해 대여소만 표시해요.</p>}
    </div>}
  </div>;
}
