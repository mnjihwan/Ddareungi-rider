import {requestLocation, MAX_LOCATION_ACCURACY} from "../lib/location";
import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { useQuery } from '@tanstack/react-query';
import { api, planBikeRoute } from '../lib/api';
import type { BikeRoute, Coordinates, Place, Station } from '../lib/types';
import { distanceLabel, observationLabel } from '../lib/status';
import { Icon } from './Icon';
import { LocationSearchBox } from './LocationSearchBox';

const minutes = (seconds: number) => `${Math.max(0, Math.ceil(seconds / 60))}분`;
export function RoutePlanner({userLocation, userLocationAccuracy, endpointSelection, pickedOrigin, plan, onPlan, onLocation, onSelectStation, onPickOrigin, onPlaceSelected, searchCenter, onSearchStart}: {
  searchCenter: Coordinates;
  onSearchStart: () => void;
  userLocation: Coordinates | null;
  userLocationAccuracy: number | null;
  pickedOrigin: {location:Coordinates;revision:number} | null;
  endpointSelection: {role:"origin"|"destination";place:Place;station?:Station;revision:number} | null;
  plan: BikeRoute | null;
  onPlan: (plan: BikeRoute | null) => void;
  onLocation: (location: Coordinates, accuracy:number) => void;
  onSelectStation: (station: Station) => void;
  onPickOrigin: () => void;
  onPlaceSelected: (place:Place,query?:string) => void;
}) {
  const [origin, setOrigin] = useState<Place | null>(userLocation ? {name:'현재 위치', address:'', location:userLocation} : null);
  const [destination, setDestination] = useState<Place | null>(null);
  const [pending, setPending] = useState(false), [locating, setLocating] = useState(false), [error, setError] = useState('');
  const [pinned, setPinned] = useState<Station | null>(null);
  const [arrival, setArrival] = useState<Station | null>(null);
  const [positionAccuracy, setPositionAccuracy] = useState<number | null>(userLocationAccuracy);
  const previousLocation = useRef(userLocation);
  const request = useRef<AbortController | null>(null);
  const resultElement = useRef<HTMLDivElement>(null);
  const status = useQuery({queryKey:['routing-status'], queryFn: async ({signal}) =>
    (await api.get<{data:{configured:boolean}}>('/routes/status',{signal})).data.data,
    staleTime:30_000, retry:1});
  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => { if(plan)resultElement.current?.scrollIntoView({block:'start'}); }, [plan]);
  useEffect(() => {
    if (!endpointSelection) return;
    invalidate();
    if (endpointSelection.role === 'origin') {
      setOrigin(endpointSelection.place); setPinned(endpointSelection.station ?? null); setPositionAccuracy(null);
    } else {
      setDestination(endpointSelection.place); setArrival(endpointSelection.station ?? null);
    }
  }, [endpointSelection]);
  useEffect(() => {
    if (userLocation && userLocation !== previousLocation.current) { invalidate(); clearPinned(); setPositionAccuracy(userLocationAccuracy); setOrigin({name:'현재 위치',address:'',location:userLocation}); }
    previousLocation.current = userLocation;
  }, [userLocation]);
  useEffect(() => {
    if (pickedOrigin) { invalidate(); clearPinned(); setPositionAccuracy(null); setOrigin({name:'지도에서 지정한 출발지',address:'',location:pickedOrigin.location}); }
  }, [pickedOrigin]);
  function clearPinned() { setPinned(null); }
  function invalidate() { request.current?.abort(); request.current = null; setPending(false); setError(''); onPlan(null); }
  function locate() {
    invalidate();
    if (!navigator.geolocation || !window.isSecureContext) { setError('현재 위치를 사용할 수 없어요. 출발 장소를 검색해 주세요.'); return; }
    setLocating(true);
    requestLocation(navigator.geolocation, ({coords}) => {
      if (!Number.isFinite(coords.accuracy) || coords.accuracy > MAX_LOCATION_ACCURACY) {
        setError('현재 위치 오차가 커서 출발지를 변경하지 않았어요. 출발 장소를 검색하거나 지도에서 지정해 주세요.');
        setLocating(false); return;
      }
      const location = {latitude:coords.latitude,longitude:coords.longitude};
      clearPinned(); setPositionAccuracy(coords.accuracy);
      setOrigin({name:'현재 위치',address:'',location}); onLocation(location,coords.accuracy); setLocating(false);
    }, () => {setError('위치를 확인하지 못했어요. 출발 장소를 검색해 주세요.');setLocating(false);});
  }
  async function submit() {
    if (!origin || !destination || pending) return;
    const controller = new AbortController(); request.current?.abort(); request.current = controller;
    setPending(true); setError(''); onPlan(null);
    try {
      const result = await planBikeRoute(origin.location, destination.location, pinned?.stationId, controller.signal, arrival?.stationId);
      if (request.current === controller && !controller.signal.aborted) onPlan(result);
    } catch (failure) {
      if (!controller.signal.aborted) setError(axios.isAxiosError(failure)
        ? failure.response?.data?.message || '경로를 가져오지 못했어요. 잠시 후 다시 검색해 주세요.'
        : '길찾기 연결을 확인해 주세요.');
    } finally { if (request.current === controller) {setPending(false);request.current=null;} }
  }
  return <section className="route-planner" aria-label="따릉이 길찾기">
    <span className="eyebrow">도보 → 따릉이 → 도보</span>
    <h1>목적지까지 따릉이로</h1>
    <p className="route-intro">걸어서 대여하고, 목적지 근처에서 반납하세요.</p>
    <PlaceInput label="출발지" center={searchCenter} selected={origin} onSearchStart={onSearchStart} onSelect={(place,query)=>{invalidate();clearPinned();setPositionAccuracy(null);setOrigin(place);if(place)onPlaceSelected(place,query);}} />
    <button className="route-locate" onClick={locate} disabled={locating}>
      <Icon name="locate" size={15}/>{locating?'위치 확인 중…':'현재 위치를 출발지로'}
    </button>
    <button className="route-locate" onClick={()=>{invalidate();onPickOrigin();}}><Icon name="pin" size={15}/>지도에서 출발지 선택</button>
    {origin?.name==='현재 위치' && positionAccuracy!==null && <p className="subtle-notice" role="status">브라우저 위치 정확도 약 {distanceLabel(positionAccuracy)}{positionAccuracy>100?' · 실제 위치와 다르면 출발 장소를 검색해 주세요.':''}</p>}
    <PlaceInput label="목적지" center={searchCenter} selected={destination} onSearchStart={onSearchStart} onSelect={(place,query)=>{invalidate();setArrival(null);setDestination(place);if(place)onPlaceSelected(place,query);}} />
    {pinned && <div className="pinned-station"><span>출발 대여소 <b>{pinned.stationName}</b></span>
      <button onClick={()=>{invalidate();clearPinned();}}>자동 선택</button></div>}
    {arrival && <div className="pinned-station"><span>도착 대여소 <b>{arrival.stationName}</b></span>
      <button onClick={()=>{invalidate();setArrival(null);}}>자동 선택</button></div>}
    <button className="primary-button route-submit" onClick={()=>void submit()} disabled={!origin||!destination||pending||locating}>
      <Icon name={pending?'refresh':'arrow'} size={18}/>{pending?'이동 경로를 비교하고 있어요…':plan?'수량 확인하고 다시 검색':'따릉이 경로 찾기'}
    </button>
    {status.data?.configured === false && <p className="subtle-notice" role="status">길찾기 연결을 준비하고 있어요. 장소와 대여소 조회는 계속 이용할 수 있어요.</p>}
    {error && <p className="error-notice" role="alert">{error}</p>}
    {plan && <div className="route-result" aria-live="polite" ref={resultElement}>
      <div className="route-summary"><span>예상 이동 시간<strong>{minutes(plan.durationSeconds)}</strong></span><span>총 이동 거리<strong>{distanceLabel(plan.distanceMeters)}</strong></span></div>
      <p className="route-note">대여·반납 시간은 별도예요. 가까운 대여소 후보 중 이동 시간이 짧은 경로를 골랐어요.</p>
      <ol className="route-legs">
        {plan.legs.map((leg,index)=><li key={index} className={leg.mode}>
          <div className="route-leg-heading"><Icon name={leg.mode==='walk'?'walk':'bike'} size={21}/><b>{leg.mode==='walk'?'도보':'자전거'} {minutes(leg.durationSeconds)}</b><span>{distanceLabel(leg.distanceMeters)}</span></div>
          <p>{index===0?origin?.name:index===1?plan.departureStation.stationName:plan.arrivalStation.stationName}<span> → </span>{index===0?plan.departureStation.stationName:index===1?plan.arrivalStation.stationName:destination?.name}</p>
          {index<2 && <button className="route-station" onClick={()=>onSelectStation(index===0?plan.departureStation:plan.arrivalStation)}>
            <b>{index===0?'대여':'반납'} · {index===0?plan.departureStation.stationName:plan.arrivalStation.stationName}</b>
            <small>{index===0?`조회 시 ${plan.departureStation.availableBikeCount}대 · ${observationLabel(plan.departureStation)}`:'현장에서 반납 가능 여부를 확인해 주세요.'}</small>
          </button>}
          {leg.steps.length>0 && <details className="route-steps"><summary>구간별 안내</summary><ol>{leg.steps.map((step,i)=><li key={i}>{step.instruction} <small>{distanceLabel(step.distanceMeters)}</small></li>)}</ol></details>}
        </li>)}
      </ol>
      <p className="route-note">수량은 이동 중 바뀔 수 있어요. 출발 전에 다시 확인해 주세요. 지도 경로의 예상 시간이며 현장 통행 표지를 우선해 주세요.</p>
      <p className="route-attribution">{plan.attribution}</p>
    </div>}
  </section>;
}

function PlaceInput({label,selected,onSelect,center,onSearchStart}:{label:string;selected:Place|null;center:Coordinates;onSearchStart:()=>void;onSelect:(place:Place|null,query?:string)=>void}) {
  const [text,setText]=useState(selected?.name??'');
  useEffect(()=>{if(selected)setText(selected.name);},[selected]);
  return <div className="route-place-input">
    <label htmlFor={`route-${label}`}>{label}</label>
    <LocationSearchBox id={`route-${label}`} label={`${label} 장소 또는 주소 검색`} variant="route" text={text} center={center}
      onTextChange={value=>{setText(value);onSearchStart();onSelect(null);}} onSubmit={onSearchStart}
      onChoose={(suggestion,query)=>onSelect(suggestion.kind==='place'?suggestion.place:
        {name:suggestion.station.stationName,address:suggestion.station.address,location:suggestion.station.location},query)}/>
    {selected && <span className="route-place-selected">선택됨 {selected.address && `· ${selected.address}`}</span>}
  </div>;
}
