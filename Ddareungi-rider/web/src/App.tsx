import {loadMapLocation, saveMapLocation} from "./lib/mapLocationStorage";
import {requestLocation, MAX_LOCATION_ACCURACY} from "./lib/location";
import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  api,
  nearby,
  favoriteStations,
  searchPlaces,
  searchStations,
  viewportStations,
  nearbySubways,
  forecastPath, loadForecasts,
} from "./lib/api";
import { INITIAL_LOCATION, type Coordinates, type Station, type MapBounds, type BikeRoute, type Place } from "./lib/types";
import {useRecentPlaces} from './lib/useRecentPlaces';
import { useFavorites } from "./lib/useFavorites";
import { availability, compareOutlooks, stationOutlook } from "./lib/status";
import { StationMap } from "./components/StationMap";
import { StationCard } from "./components/StationCard";
import { StationDetail } from "./components/StationDetail";
import { Icon } from "./components/Icon";
import { RoutePlanner } from "./components/RoutePlanner";
import { LocationSearchBox } from "./components/LocationSearchBox";
import {bestSearchScore, searchMatchLabel} from './lib/koreanSearch';

import { useTheme } from "./lib/useTheme";
import { ThemeToggle } from "./components/ThemeToggle";

export default function App() {
  const {theme, changeTheme} = useTheme();
  const [initialMap] = useState(()=>loadMapLocation() ?? {location: INITIAL_LOCATION, label: "강남역 주변"});
  const [center, setCenter] = useState(initialMap.location);
  const [viewport, setViewport] = useState<MapBounds | null>(null);
  const [nearbyLimit, setNearbyLimit] = useState<number | null>(5);
  const [focus, setFocus] = useState({
    location: initialMap.location,
    revision: 0,
  });
  const [userLocation, setUserLocation] = useState<Coordinates | null>(null),
    [mapReady, setMapReady] = useState(false);
  const [label, setLabel] = useState(initialMap.label),
    [radius, setRadius] = useState(500);
  const [view, setView] = useState<"nearby" | "favorites" | "route">("nearby"),
    [filter, setFilter] = useState(false);
  const [text, setText] = useState(""),
    [submitted, setSubmitted] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null),
    [sheet, setSheet] = useState<"peek" | "mid" | "full">("mid");
  const [message, setMessage] = useState(""),
    [locating, setLocating] = useState(false),
    [, tick] = useState(0);
  const drag = useRef<number | null>(null);
  const initialLocationRequested = useRef(false);
  const allowInitialLocation = useRef(true);
  const locationRequest = useRef(0);
  useEffect(() => {
    if (initialLocationRequested.current) return;
    initialLocationRequested.current = true;
    locate(true);
  }, []);
  const [forecastMinutes, setForecastMinutes] = useState(0);
  const [route, setRoute] = useState<BikeRoute | null>(null);
  const [planningRoute, setPlanningRoute] = useState(false);
  const hideStationMarkers = view === "route" && (planningRoute || route !== null);
  const [endpointSelection, setEndpointSelection] = useState<{role:"origin"|"destination";place:Place;station?:Station;revision:number} | null>(null);
  const [searchPlace, setSearchPlace] = useState<Place | null>(null);
  const [userLocationAccuracy, setUserLocationAccuracy] = useState<number | null>(null);
  const [pickingOrigin, setPickingOrigin] = useState(false);
  const [pickedOrigin, setPickedOrigin] = useState<{location:Coordinates;revision:number} | null>(null);
  const [focusedPlace, setFocusedPlace] = useState<Place | null>(null);
  const recentPlaces = useRecentPlaces();
  const reliableLocation = userLocation && userLocationAccuracy !== null && userLocationAccuracy <= 500;
  const suggestionCenter = reliableLocation ? userLocation : center;
  const subwaySuggestions = useQuery({queryKey:['nearby-subways',suggestionCenter],
    queryFn:({signal})=>nearbySubways(suggestionCenter,signal),retry:false,gcTime:0,
    enabled:view!=="route",refetchOnWindowFocus:false});
  const sidebar = useRef<HTMLDivElement>(null);
  useEffect(() => { sidebar.current?.scrollTo({ top: 0 }); }, [selectedId, submitted, view]);
  const favorites = useFavorites();
  useEffect(() => {
    const timer = setInterval(() => tick((n) => n + 1), 30000);
    return () => clearInterval(timer);
  }, []);
  const mode = useQuery({
    queryKey: ["mode"],
    queryFn: async ({ signal }) =>
      (await api.get("/status", { signal })).data.data.mode,
    staleTime: Infinity,
  });
  const stations = useQuery({
    queryKey: ["nearby", center, radius],
    queryFn: ({ signal }) => nearby(center, radius, signal),
    staleTime: 30000,
    refetchInterval: 60000,
    retry: 1,
  });
  const saved = useQuery({
    queryKey: ["favorites", favorites.ids, center],
    queryFn: ({ signal }) => favoriteStations(favorites.ids, center, signal),
    enabled: favorites.ids.length > 0,
    refetchInterval: 60000,
    retry: 1,
  });
  const mapStations = useQuery({
    queryKey: ["viewport", viewport],
    queryFn: ({signal}) => viewportStations(viewport!, signal),
    enabled: !!viewport && mapReady,
    placeholderData: previous => previous,
    staleTime: 30000,
    refetchInterval: 60000,
    retry: 1,
  });
  const selectedQuery = useQuery({
    queryKey: ["selected", selectedId, center],
    queryFn: ({ signal }) => favoriteStations([selectedId!], center, signal),
    enabled: !!selectedId,
    refetchInterval: 60000,
    retry: 1,
  });
  const search = useQuery({
    queryKey: ["search", submitted, center],
    gcTime: 0,
    enabled: submitted.length >= 2,
    retry: false,
    queryFn: async ({ signal }) => {
      const [sr, pr] = await Promise.allSettled([
        searchStations(submitted, center, signal),
        searchPlaces(submitted, signal, center),
      ]);
      const sourcePlaces =
        pr.status === "fulfilled"
          ? pr.value
          : [];
      if (
        sr.status === "rejected" &&
        pr.status === "rejected" &&
        !sourcePlaces.length
      )
        throw new Error("검색 연결 실패");
      const matchingStations = sr.status === 'fulfilled' ? sr.value : [];
      const score = (place: Place) => bestSearchScore(place.name, place.address, submitted);
      const strong = sourcePlaces.some(place => score(place) >= 0 && score(place) <= 2) ||
        matchingStations.some(station => {
          const rank = bestSearchScore(station.stationName, station.address, submitted);
          return rank >= 0 && rank <= 2;
        });
      const places = sourcePlaces.filter(place => score(place) >= 0 && (!strong || score(place) <= 2))
        .sort((a, b) => score(a) - score(b));
      return {
        stations: matchingStations.filter(station => !strong || bestSearchScore(station.stationName, station.address, submitted) <= 2),
        places,
        placesUnavailable: pr.status === "rejected" && !places.length,
      };
    },
  });
  const merged = new Map<string, Station>();
  [
    ...(route ? [route.departureStation, route.arrivalStation] : []),
    ...(stations.data ?? []),
    ...(mapStations.data ?? []),
    ...(saved.data ?? []),
    ...(search.data?.stations ?? []),
    ...(selectedQuery.data ?? []),
  ].forEach((s) => merged.set(s.stationId, s));
  const selected = selectedId ? merged.get(selectedId) : undefined,
    active = view === "favorites" ? saved : stations;
  const baseList = submitted
    ? (search.data?.stations ?? [])
    : view === "favorites"
      ? favorites.ids.length
        ? (saved.data ?? [])
        : []
      : (stations.data ?? []);
  const forecastIds = [...new Set(baseList.map(station=>station.stationId))].sort();
  const forecasts = useQuery({
    queryKey: ["list-forecasts", forecastIds],
    queryFn: ({signal})=>loadForecasts(forecastIds, signal),
    enabled: !!forecastPath && forecastMinutes > 0 && forecastIds.length > 0 && view !== "route",
    staleTime: 60_000, refetchInterval: 60_000, retry: false,
  });
  const forecastMap = new Map((forecasts.data ?? []).map(value=>[value.stationId,value]));
  const forecastNow = Date.now();
  const usePredictions = forecastMinutes > 0 && !forecasts.isError && baseList.some(station=>
    stationOutlook(station,forecastMap.get(station.stationId),forecastMinutes,forecastNow).count !== null);
  const effectiveMinutes = usePredictions ? forecastMinutes : 0;
  const filteredList = baseList.filter(s=>!filter || ["available","low"].includes(
    stationOutlook(s,forecastMap.get(s.stationId),effectiveMinutes,forecastNow).state))
    .sort((a,b)=>compareOutlooks(a,b,forecastMap,effectiveMinutes,forecastNow));
  const list = !submitted && view === "nearby" && nearbyLimit !== null ? filteredList.slice(0, nearbyLimit) : filteredList;
  function go(location: Coordinates, title: string, fromLocation = false) {
    allowInitialLocation.current = false;
    setFocusedPlace(null);
    setSearchPlace(null);
    setCenter(location);
    setRadius(500);
    setNearbyLimit(fromLocation ? 5 : null);
    setFocus((f) => ({ location, revision: f.revision + 1 }));
    setLabel(title);
    saveMapLocation(location, fromLocation ? "마지막으로 확인한 위치 주변" : title);
    setSubmitted("");
    setText("");
    setSelectedId(null);
    setView("nearby");
    setSheet("mid");
    setMessage("");
  }
  function focusPlace(place:Place, query?:string) {
    allowInitialLocation.current = false;
    setCenter(place.location);
    setFocus(previous=>({location:place.location,revision:previous.revision+1}));
    setFocusedPlace(place);
    setLabel(`${place.name} 주변`);
    saveMapLocation(place.location, `${place.name} 주변`);
    setSelectedId(null);
    setPickingOrigin(false);
    setSheet("mid");
    if(query)recentPlaces.remember(query);
  }
  function openPlace(place:Place, query?:string) {
    go(place.location,`${place.name} 주변`);
    focusPlace(place,query);
    setSearchPlace(place);
  }
  function chooseEndpoint(role:"origin"|"destination", place:Place, station?:Station) {
    allowInitialLocation.current = false;
    setEndpointSelection(previous=>({role,place,station,revision:(previous?.revision??0)+1}));
    setRoute(null); setPickingOrigin(false); setView("route"); setSelectedId(null); setSheet("full");
    setFocusedPlace(place);
  }
  function select(station: Station) {
    saveMapLocation(station.location, `${station.stationName} 주변`);
    allowInitialLocation.current = false;
    setFocusedPlace(null);
    if (submitted && view !== "route") recentPlaces.remember(submitted);
    setSelectedId(station.stationId);
    setFocus((f) => ({ location: station.location, revision: f.revision + 1 }));
    setSheet("mid");
  }
  function locate(initial = false) {
    const requestId = ++locationRequest.current;
    if (!window.isSecureContext || !navigator.geolocation) {
      setMessage(
        "현재 위치를 사용할 수 없어요. 장소 검색이나 지도 이동으로 찾아주세요. 모바일 위치 기능은 HTTPS 연결이 필요해요.",
      );
      return;
    }
    setLocating(true);
    requestLocation(navigator.geolocation,
      ({ coords }) => {
        if (requestId !== locationRequest.current) return;
        setLocating(false);
        // A delayed permission response must not replace a place the user already chose.
        if (initial && !allowInitialLocation.current) return;
        if (!Number.isFinite(coords.accuracy) || coords.accuracy > MAX_LOCATION_ACCURACY) {
          setMessage(`현재 위치 오차가 ${Number.isFinite(coords.accuracy) ? `약 ${Math.round(coords.accuracy)}m로 커서` : '확인되지 않아'} 지도를 이동하지 않았어요. 장소 검색으로 실제 위치를 지정해 주세요.`);
          return;
        }
        const location = {
          latitude: coords.latitude,
          longitude: coords.longitude,
        };
        setUserLocation(location);
        setUserLocationAccuracy(coords.accuracy);
        go(location, "내 위치 주변", true);
        if (coords.accuracy > 100) setMessage(`브라우저 위치 오차가 약 ${Math.round(coords.accuracy)}m예요. 실제 위치와 다르면 장소 검색이나 지도에서 출발 위치를 지정해 주세요.`);
        setLocating(false);
      },
      (error) => {
        if (requestId !== locationRequest.current) return;
        setLocating(false);
        if (initial && !allowInitialLocation.current) return;
        const reason = error.code === 1
          ? "위치 접근이 차단됐어요. 브라우저의 사이트 위치 권한과 운영체제 위치 설정을 확인해 주세요."
          : error.code === 3
            ? "위치 확인 시간이 초과됐어요. 현재 위치 버튼으로 다시 시도해 주세요."
            : "브라우저가 현재 위치를 제공하지 못했어요. 운영체제 위치 서비스와 네트워크 연결을 확인해 주세요.";
        setMessage(reason + (initial ? ` 현재는 ${initialMap.label}을 보여드려요.` : " 장소 검색은 계속 사용할 수 있어요."));
        setLocating(false);
      },
    );
  }
  const searchShortcuts = (
<div className="search-shortcuts">
            {recentPlaces.items.length>0 && <div className="recommendation-row recent-places"><span>최근 장소</span>
              <div className="recent-place-items">{recentPlaces.items.slice(0,2).map(item=><button key={item.query} onClick={()=>{allowInitialLocation.current=false;setFocusedPlace(null);setText(item.query);setSubmitted(item.query);setSelectedId(null);setSheet("full");}}>{item.query}</button>)}</div>
              <button className="clear-recent" aria-label="최근 장소 지우기" title="최근 장소 전체 지우기" onClick={recentPlaces.clear}><Icon name="close" size={12}/></button>
            </div>}
            <div className="recommendation-row subway-suggestions"><span>{reliableLocation?"내 주변역":"지도 주변역"}</span>
              {subwaySuggestions.data?.map(place=><button key={place.name} title={place.address} onClick={()=>openPlace(place)}>{place.name.replace(/역(?:\s|[0-9]+호선).*$/,'역')}</button>)}
              {subwaySuggestions.isFetching && !subwaySuggestions.data && <span>찾는 중…</span>}
              {subwaySuggestions.isError && <span>주변역 조회 불가</span>}
              {subwaySuggestions.data?.length===0 && <span>2km 안에 지하철역이 없어요</span>}
            </div>
          </div>
  );
  const detail = selected && (
    <StationDetail
      key={selected.stationId}
      station={selected}
      sample={mode.data === 'sample'}
      saved={favorites.ids.includes(selected.stationId)}
      onFavorite={() => favorites.toggle(selected.stationId)}
      onClose={() => setSelectedId(null)}
      onRoute={() => chooseEndpoint("origin", {name:selected.stationName,address:selected.address,location:selected.location}, selected)}
      onArrival={() => chooseEndpoint("destination", {name:selected.stationName,address:selected.address,location:selected.location}, selected)}
    />
  );
  return (
    <main className="app-shell">
      <header className="app-header">
        <a className="brand" href="/" aria-label="따릉라이더 홈">
          <img className="brand-logo" src="/branding/ddareung-rider-logo.png" alt="따릉라이더" width={2172} height={724} />
        </a>
        <nav aria-label="대여소 보기">
          <button
            className={view === "nearby" ? "active" : ""}
            onClick={() => {
              setView("nearby");
              setSubmitted("");
              setSelectedId(null);
            }}
          >
            <Icon name="pin" size={17} />
            주변 대여소
          </button>
          <button className={view === "route" ? "active" : ""}
            onClick={() => {allowInitialLocation.current=false;setView("route");setSelectedId(null);setSheet(route ? "mid" : "full");}}>
            <Icon name="arrow" size={17} />길찾기
          </button>
          <button
            className={view === "favorites" ? "active" : ""}
            onClick={() => {
              allowInitialLocation.current=false;
              setView("favorites");
              setSubmitted("");
              setSelectedId(null);
              setSheet("mid");
            }}
          >
            <Icon name="star" size={17} />
            즐겨찾기<span className="nav-count">{favorites.ids.length}</span>
          </button>
        </nav>
        <ThemeToggle theme={theme} onChange={changeTheme}/>
      </header>
      <div className={`workspace map-sheet-${sheet} ${recentPlaces.items.length ? "has-recent-places" : ""} ${view === "route" ? `route-mode route-sheet-${sheet}` : ""}`}>
        <section className="search-area" aria-label="장소 검색">
          <div className="search-intro">
            <span className="eyebrow">어디로 떠나볼까요?</span>
            <h1>가까운 따릉이를 찾아보세요</h1>
          </div>
          <LocationSearchBox id="main-place-search" label="장소, 주소 또는 대여소 검색" text={text} center={center}
            onTextChange={value => {allowInitialLocation.current=false;setText(value);setSubmitted('');setFocusedPlace(null);}}
            onSubmit={() => {
              setFocusedPlace(null);
              if (text.trim().length < 2) {
                setMessage("장소나 대여소 이름을 두 글자 이상 입력해 주세요.");
                return;
              }
              setSubmitted(text.trim());
              setSelectedId(null);
              setSheet("full");
              setMessage("");
            }}
            onChoose={(suggestion,query) => {
              if (suggestion.kind === 'place') openPlace(suggestion.place, query);
              else {recentPlaces.remember(query);go(suggestion.station.location, `${suggestion.station.stationName} 주변`);select(suggestion.station);}
            }}/>
          <div className="desktop-shortcuts">{searchShortcuts}</div>
        </section>
        <aside
          className={`station-sidebar sheet-${sheet}`}
          aria-label="대여소 목록과 상세"
        >
          <div
            className="sheet-handle"
            role="slider" tabIndex={0} aria-label="목록 높이"
            aria-orientation="vertical" aria-valuemin={0} aria-valuemax={2}
            aria-valuenow={sheet === "peek" ? 0 : sheet === "mid" ? 1 : 2}
            aria-valuetext={sheet === "peek" ? "접힘" : sheet === "mid" ? "중간" : "펼쳐짐"}
            onKeyDown={e=>{
              if (!["ArrowUp","ArrowDown","Home","End"].includes(e.key)) return;
              e.preventDefault();
              setSheet(current=>e.key === "Home" ? "peek" : e.key === "End" ? "full" :
                e.key === "ArrowUp" ? (current === "peek" ? "mid" : "full") : (current === "full" ? "mid" : "peek"));
            }}
            onPointerDown={e=>{
              if (!e.isPrimary || e.button !== 0) return;
              drag.current=e.clientY;
              e.currentTarget.setPointerCapture(e.pointerId);
            }}
            onPointerUp={e=>{
              if (drag.current === null) return;
              const delta=e.clientY-drag.current;
              drag.current=null;
              if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
              if (Math.abs(delta)>30) setSheet(current=>delta<0 ? (current === "peek" ? "mid" : "full") : (current === "full" ? "mid" : "peek"));
            }}
            onPointerCancel={()=>{drag.current=null;}}
            onLostPointerCapture={()=>{drag.current=null;}}
          >
            <span aria-hidden="true" />
          </div>
          <div className="sidebar-content" ref={sidebar}>
            {selected && <div className="mobile-detail">{detail}</div>}
            <div hidden={view !== "route"} className={selected ? "route-planner-container mobile-hidden" : "route-planner-container"}>
              <RoutePlanner onPlanningChange={setPlanningRoute} active={view === "route"} favoriteStations={(saved.data ?? []).filter(station=>favorites.ids.includes(station.stationId))}
                favoritesLoading={saved.isFetching} favoritesError={saved.isError}
                userLocation={userLocation} endpointSelection={endpointSelection} plan={route}
                onPlaceSelected={focusPlace}
                searchCenter={center} onSearchStart={()=>setFocusedPlace(null)}
                userLocationAccuracy={userLocationAccuracy} pickedOrigin={pickedOrigin}
                onPickOrigin={()=>{setRoute(null);setSelectedId(null);setPickingOrigin(true);setSheet("peek");}}
                onPlan={plan => {setRoute(plan);if(plan)setSheet("mid");}} onLocation={(location,accuracy)=>{setUserLocation(location);setUserLocationAccuracy(accuracy);}} onSelectStation={select} />
            </div>
            <div
              hidden={view === "route"}
              className={
                selected ? "list-content mobile-hidden" : "list-content"
              }
            >
              {!submitted && view === "nearby" && searchPlace && <section className="searched-place" aria-label="검색한 장소">
                <span className="eyebrow">선택한 장소</span>
                <b>{searchPlace.name}</b><p>{searchPlace.address}</p>
                <div className="endpoint-actions">
                  <button className="primary-button" onClick={()=>chooseEndpoint("origin",searchPlace)}>출발지로</button>
                  <button className="primary-button" onClick={()=>chooseEndpoint("destination",searchPlace)}>도착지로</button>
                </div>
              </section>}
              <div className="list-heading">
                <div>
                  <span className="eyebrow">
                    {submitted
                      ? "검색 결과"
                      : view === "favorites"
                        ? "나만의 자전거 정류장"
                        : "NEARBY STATIONS"}
                  </span>
                  <h2>
                    {submitted
                      ? `“${submitted}”`
                      : view === "favorites"
                        ? "저장한 대여소"
                        : label}
                    <span>{list.length}</span>
                  </h2>
                </div>
                <button
                  className="icon-button"
                  aria-label="대여소 새로고침"
                  onClick={() =>
                    void (submitted ? search.refetch() : active.refetch())
                  }
                >
                  <Icon name="refresh" size={18} />
                </button>
              </div>
              {!submitted && view === "nearby" && (
                <div className="radius-tabs" aria-label="검색 반경">
                  {[500, 1000, 1500, 3000].map((r) => (
                    <button
                      key={r}
                      aria-pressed={radius === r}
                      className={radius === r ? "active" : ""}
                      onClick={() => { setRadius(r); setNearbyLimit(null); }}
                    >
                      {r < 1000 ? `${r}m` : `${r / 1000}km`}
                    </button>
                  ))}
                </div>
              )}
              <div className="forecast-list-controls">
                <label>대여 예정 시간 <select value={forecastMinutes} onChange={e=>setForecastMinutes(Number(e.target.value))}>
                  <option value={0}>지금</option>
                  {[5,10,15,30,60].map(minutes=><option key={minutes} value={minutes}>{minutes}분 후</option>)}
                </select></label>
                {forecastMinutes > 0 && <p role="status">{usePredictions
                  ? `${forecastMinutes}분 후 예상 기준 · 예측 없는 대여소는 마지막에 표시해요. 대여를 보장하지 않아요.`
                  : !forecastPath ? "예측 서비스 준비 중이에요. 현재 수량으로 표시해요." : forecasts.isFetching ? "예측을 확인하는 동안 현재 수량으로 표시해요." : "사용 가능한 예측이 없어 현재 수량으로 표시해요."}</p>}
              </div>
              <div className="mobile-map-info">
                {searchShortcuts}
                <details className="mobile-map-legend"><summary>지도 수량 표시 안내</summary>
                  <p>파랑 4대 이상 · 노랑 1–3대 · 빨강 0대 · 회색 정보 없음</p>
                </details>
              </div>
              <div className="list-filter">
                <span>
                  {view === "favorites" && !submitted
                    ? "이 브라우저에 저장돼요"
                    : nearbyLimit !== null && !submitted ? "대여 가능 우선 · 5곳 · 그룹 내 거리순" : "대여 가능 → 0대 → 정보 없음 · 그룹 내 거리순"}
                </span>
                <label>
                  <input
                    type="checkbox"
                    checked={filter}
                    onChange={(e) => setFilter(e.target.checked)}
                  />
                  {usePredictions ? "예상 대여 가능만" : "대여 가능만"}
                </label>
              </div>
              {mode.data === "sample" && (
                <p className="sample-notice">
                  <Icon name="info" size={15} />
                  샘플 모드 · 표시 수량은 실제 데이터가 아니에요.
                </p>
              )}
              {submitted && search.isPending && (
                <div className="loading-state" role="status">
                  검색하고 있어요…
                </div>
              )}
              {submitted && search.isError && (
                <div className="empty-state" role="alert">
                  <Icon name="search" size={30} />
                  <h3>검색에 연결하지 못했어요</h3>
                  <button onClick={() => void search.refetch()}>
                    다시 검색
                  </button>
                </div>
              )}
              {search.data && submitted && (
                <div className="place-results">
                  {search.data.places.map((place, i) => (
                    <div className="place-result" key={`${place.name}-${i}`}>
                      <button onClick={() => openPlace(place,submitted)}>
                        <span className="place-icon">
                          <Icon name="pin" size={18} />
                        </span>
                        <span>
                          <b>{place.name}</b>
                          <small>{[searchMatchLabel(place.name, place.address, submitted), place.address].filter(Boolean).join(' · ')}</small>
                        </span>
                        <Icon name="arrow" size={17} />
                      </button>
                    </div>
                  ))}
                  {search.data.placesUnavailable && (
                    <p className="subtle-notice">
                      장소 검색 연결을 확인하지 못했어요. 대여소 이름으로도
                      검색할 수 있어요.
                    </p>
                  )}
                </div>
              )}
              {!submitted &&
                active.isPending &&
                (view !== "favorites" || favorites.ids.length > 0) && (
                  <div className="skeletons" aria-label="대여소 불러오는 중">
                    {[1, 2, 3].map((i) => (
                      <div key={i} />
                    ))}
                  </div>
                )}
              {!submitted && active.isError && (
                <p className="error-notice" role="alert">
                  대여소 정보를 갱신하지 못했어요. 새로고침으로 다시 확인해
                  주세요.
                </p>
              )}
              {list.map((station) => (
                <StationCard
                  key={station.stationId}
                  station={station}
                  forecastMinutes={effectiveMinutes}
                  predictedCount={stationOutlook(station,forecastMap.get(station.stationId),effectiveMinutes,forecastNow).count}
                  selected={selectedId === station.stationId}
                  saved={favorites.ids.includes(station.stationId)}
                  onSelect={() => select(station)}
                  onFavorite={() => favorites.toggle(station.stationId)}
                />
              ))}
              {!submitted && view === "nearby" && nearbyLimit !== null && filteredList.length > list.length && (
                <button className="show-all-stations" onClick={() => setNearbyLimit(null)}>
                  반경 {radius < 1000 ? `${radius}m` : `${radius / 1000}km`} 안의 {filteredList.length}곳 모두 보기
                </button>
              )}
              {!list.length &&
                !(submitted
                  ? search.isPending || search.isError
                  : view === "favorites" && !favorites.ids.length
                    ? false
                    : active.isFetching || active.isError) && (
                  <div className="empty-state">
                    <Icon
                      name={view === "favorites" ? "star" : "bike"}
                      size={35}
                    />
                    <h3>
                      {view === "favorites" && !submitted
                        ? "자주 가는 대여소를 저장해 보세요"
                        : "조건에 맞는 대여소가 없어요"}
                    </h3>
                    <p>
                      {view === "favorites"
                        ? "대여소 옆 별을 누르면 여기에 모아볼 수 있어요."
                        : "검색 반경을 넓히거나 다른 장소를 찾아보세요."}
                    </p>
                  </div>
                )}
              {view === "favorites" &&
                saved.isSuccess &&
                !submitted &&
                favorites.ids
                  .filter((id) => !saved.data?.some((s) => s.stationId === id))
                  .map((id) => (
                    <div className="missing-favorite" key={id}>
                      <span>{id} · 대여소 정보 없음</span>
                      <button onClick={() => favorites.toggle(id)}>
                        저장 해제
                      </button>
                    </div>
                  ))}
              <div className="list-footnote">
                <Icon name="refresh" size={12} />
                화면을 보고 있는 동안 1분마다 수량을 확인해요
              </div>
            </div>
          </div>
        </aside>
        <section className={`map-region ${selected ? "has-detail" : ""}`} aria-label="지도에서 대여소 찾기">
          <StationMap
            theme={theme}
            showPlaces={view === "route" && !hideStationMarkers && !pickingOrigin}
            onSelectPlace={()=>{allowInitialLocation.current=false;setSelectedId(null);setSheet("peek");}}
            onChoosePlace={chooseEndpoint}
            stations={hideStationMarkers ? [] : Array.from(merged.values())}
            selectedId={selectedId}
            focus={focus}
            userLocation={userLocation}
            onSelect={select}
            onViewport={setViewport}
            onUserPan={location => {
              allowInitialLocation.current=false;
              setCenter(location);
              setLabel("지도 중심 주변");
              saveMapLocation(location, "마지막으로 본 지도 주변");
              setNearbyLimit(5);
              setSubmitted("");
              setText("");
            }}
            onReady={setMapReady}
            onDismissPlace={()=>setFocusedPlace(null)}
            route={view === "route" ? route : null}
            focusedPlace={route && view==="route" ? null : focusedPlace}
            pickingOrigin={view === "route" && pickingOrigin}
            onPickOrigin={location=>{setPickedOrigin(previous=>({location,revision:(previous?.revision??0)+1}));setPickingOrigin(false);setSheet("full");}}
            onCancelPick={()=>setPickingOrigin(false)}
          />
          <div className="map-top-label">
            <Icon name="pin" size={16} />
            {view === "route" ? "따릉이 길찾기" : label}
            {view !== "route" && <span>목록 {radius < 1000 ? `${radius}m` : `${radius / 1000}km`} · {mapStations.isFetching ? "지도 갱신 중" : `지도 ${mapStations.data?.length ?? 0}곳`}</span>}
          </div>
          {!hideStationMarkers && mapStations.isError && <button className="search-this-area" onClick={() => void mapStations.refetch()}>
            <Icon name="refresh" size={17} />지도 대여소 다시 불러오기
          </button>}
          <button
            className="location-button"
            aria-label="현재 위치에서 대여소 찾기"
            onClick={()=>locate()}
            disabled={locating}
          >
            <Icon name={locating ? "refresh" : "locate"} size={21} />
          </button>
          {!hideStationMarkers && <div className="map-legend" aria-label="대여 수량 범례">
            <span>
              <i className="available" />
              4대 이상
            </span>
            <span>
              <i className="low" />
              1–3대
            </span>
            <span>
              <i className="empty" />
              0대
            </span>
            <span>
              <i className="unknown" />
              정보 없음
            </span>
          </div>}
          {selected && <div className="desktop-detail">{detail}</div>}
        </section>
      </div>
      {(message || favorites.message) && (
        <div className="toast" role="status">
          <Icon name="info" size={19} />
          <span>{message || favorites.message}</span>
          {message && (
            <button aria-label="안내 닫기" onClick={() => setMessage("")}>
              <Icon name="close" size={17} />
            </button>
          )}
        </div>
      )}
    </main>
  );
}
