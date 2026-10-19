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
} from "./lib/api";
import { INITIAL_LOCATION, type Coordinates, type Station, type MapBounds, type BikeRoute, type Place } from "./lib/types";
import {useRecentPlaces} from './lib/useRecentPlaces';
import { useFavorites } from "./lib/useFavorites";
import { availability } from "./lib/status";
import { StationMap } from "./components/StationMap";
import { StationCard } from "./components/StationCard";
import { StationDetail } from "./components/StationDetail";
import { Icon } from "./components/Icon";
import { RoutePlanner } from "./components/RoutePlanner";
import { LocationSearchBox } from "./components/LocationSearchBox";
import {bestSearchScore, searchMatchLabel} from './lib/koreanSearch';

export default function App() {
  const [center, setCenter] = useState(INITIAL_LOCATION);
  const [viewport, setViewport] = useState<MapBounds | null>(null);
  const [nearbyLimit, setNearbyLimit] = useState<number | null>(5);
  const [focus, setFocus] = useState({
    location: INITIAL_LOCATION,
    revision: 0,
  });
  const [userLocation, setUserLocation] = useState<Coordinates | null>(null),
    [mapReady, setMapReady] = useState(false);
  const [label, setLabel] = useState("강남역 주변"),
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
  const drag = useRef(0);
  const [route, setRoute] = useState<BikeRoute | null>(null);
  const [pinnedStation, setPinnedStation] = useState<Station | null>(null);
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
  const filteredList = baseList.filter(
    (s) => !filter || ["available", "low"].includes(availability(s)),
  ).sort((a, b) => (submitted ? bestSearchScore(a.stationName, a.address, submitted) - bestSearchScore(b.stationName, b.address, submitted) : 0)
    || a.distanceMeters - b.distanceMeters);
  const list = !submitted && view === "nearby" && nearbyLimit !== null ? filteredList.slice(0, nearbyLimit) : filteredList;
  function go(location: Coordinates, title: string, fromLocation = false) {
    setFocusedPlace(null);
    setCenter(location);
    setRadius(500);
    setNearbyLimit(fromLocation ? 5 : null);
    setFocus((f) => ({ location, revision: f.revision + 1 }));
    setLabel(title);
    setSubmitted("");
    setText("");
    setSelectedId(null);
    setView("nearby");
    setSheet("mid");
    setMessage("");
  }
  function focusPlace(place:Place, query?:string) {
    setCenter(place.location);
    setFocus(previous=>({location:place.location,revision:previous.revision+1}));
    setFocusedPlace(place);
    setLabel(`${place.name} 주변`);
    setSelectedId(null);
    setPickingOrigin(false);
    setSheet("mid");
    if(query)recentPlaces.remember(query);
  }
  function openPlace(place:Place, query?:string) {
    go(place.location,`${place.name} 주변`);
    focusPlace(place,query);
  }
  function select(station: Station) {
    setFocusedPlace(null);
    if (submitted && view !== "route") recentPlaces.remember(submitted);
    setSelectedId(station.stationId);
    setFocus((f) => ({ location: station.location, revision: f.revision + 1 }));
    setSheet("mid");
  }
  function locate() {
    if (!window.isSecureContext || !navigator.geolocation) {
      setMessage(
        "현재 위치를 사용할 수 없어요. 장소 검색이나 지도 이동으로 찾아주세요. 모바일 위치 기능은 HTTPS 연결이 필요해요.",
      );
      return;
    }
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
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
      () => {
        setMessage(
          "위치를 확인하지 못했어요. 장소 검색과 지도는 계속 사용할 수 있어요.",
        );
        setLocating(false);
      },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 },
    );
  }
  const detail = selected && (
    <StationDetail
      key={selected.stationId}
      station={selected}
      sample={mode.data === 'sample'}
      saved={favorites.ids.includes(selected.stationId)}
      onFavorite={() => favorites.toggle(selected.stationId)}
      onClose={() => setSelectedId(null)}
      onRoute={() => { setPinnedStation(selected); setView("route"); setSelectedId(null); setSheet("full"); }}
    />
  );
  return (
    <main className="app-shell">
      <header className="app-header">
        <a className="brand" href="/" aria-label="따릉라이더 홈">
          <span className="brand-icon">
            <Icon name="bike" size={25} />
          </span>
          <span>
            따릉라이더
            <small>ddareungrider</small>
          </span>
        </a>
        <nav aria-label="대여소 보기">
          <button className={view === "route" ? "active" : ""}
            onClick={() => {setView("route");setSelectedId(null);setSheet(route ? "mid" : "full");}}>
            <Icon name="arrow" size={17} />길찾기
          </button>
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
          <button
            className={view === "favorites" ? "active" : ""}
            onClick={() => {
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
        <span className="header-status">
          <i />
          {mode.data === "sample"
            ? "샘플 데이터"
            : mode.data === "infra"
              ? "실시간 대여소"
              : "대여소 탐색"}
        </span>
      </header>
      <div className={`workspace ${recentPlaces.items.length ? "has-recent-places" : ""} ${view === "route" ? `route-mode route-sheet-${sheet}` : ""}`}>
        <section className="search-area" aria-label="장소 검색">
          <div className="search-intro">
            <span className="eyebrow">어디로 떠나볼까요?</span>
            <h1>가까운 따릉이를 찾아보세요</h1>
          </div>
          <LocationSearchBox id="main-place-search" label="장소, 주소 또는 대여소 검색" text={text} center={center}
            onTextChange={value => {setText(value);setSubmitted('');setFocusedPlace(null);}}
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
          <div className="search-shortcuts">
            {recentPlaces.items.length>0 && <div className="recommendation-row recent-places"><span>최근 장소</span>
              {recentPlaces.items.slice(0,2).map(item=><button key={item.query} onClick={()=>{setFocusedPlace(null);setText(item.query);setSubmitted(item.query);setSelectedId(null);setSheet("full");}}>{item.query}</button>)}
              <button className="clear-recent" aria-label="최근 장소 지우기" onClick={recentPlaces.clear}><Icon name="close" size={12}/></button>
            </div>}
            <div className="recommendation-row subway-suggestions"><span>{reliableLocation?"내 주변역":"지도 주변역"}</span>
              {subwaySuggestions.data?.map(place=><button key={place.name} title={place.address} onClick={()=>openPlace(place)}>{place.name.replace(/역(?:\s|[0-9]+호선).*$/,'역')}</button>)}
              {subwaySuggestions.isFetching && !subwaySuggestions.data && <span>찾는 중…</span>}
              {subwaySuggestions.isError && <span>주변역 조회 불가</span>}
              {subwaySuggestions.data?.length===0 && <span>2km 안에 지하철역이 없어요</span>}
            </div>
          </div>
        </section>
        <aside
          className={`station-sidebar sheet-${sheet}`}
          aria-label="대여소 목록과 상세"
        >
          <div
            className="sheet-handle"
            onPointerDown={(e) => {
              if ((e.target as HTMLElement).closest('button')) return;
              drag.current = e.clientY;
              e.currentTarget.setPointerCapture(e.pointerId);
            }}
            onPointerUp={(e) => {
              if ((e.target as HTMLElement).closest('button')) return;
              const delta = e.clientY - drag.current;
              if (Math.abs(delta) > 30)
                setSheet(
                  delta < 0 ? "full" : sheet === "full" ? "mid" : "peek",
                );
            }}
          >
            <span />
            <div>
              {(["peek", "mid", "full"] as const).map((value, i) => (
                <button
                  key={value}
                  aria-label={
                    ["목록 접기", "목록 중간 높이", "목록 전체 펼치기"][i]
                  }
                  aria-pressed={sheet === value}
                  onClick={() => setSheet(value)}
                >
                  {["접기", "중간", "펼치기"][i]}
                </button>
              ))}
            </div>
          </div>
          <div className="sidebar-content" ref={sidebar}>
            {selected && <div className="mobile-detail">{detail}</div>}
            <div hidden={view !== "route"} className={selected ? "route-planner-container mobile-hidden" : "route-planner-container"}>
              <RoutePlanner userLocation={userLocation} pinnedStation={pinnedStation} plan={route}
                onPlaceSelected={focusPlace}
                searchCenter={center} onSearchStart={()=>setFocusedPlace(null)}
                userLocationAccuracy={userLocationAccuracy} pickedOrigin={pickedOrigin} onClearPinned={()=>setPinnedStation(null)}
                onPickOrigin={()=>{setRoute(null);setSelectedId(null);setPickingOrigin(true);setSheet("peek");}}
                onPlan={plan => {setRoute(plan);if(plan)setSheet("mid");}} onLocation={(location,accuracy)=>{setUserLocation(location);setUserLocationAccuracy(accuracy);}} onSelectStation={select} />
            </div>
            <div
              hidden={view === "route"}
              className={
                selected ? "list-content mobile-hidden" : "list-content"
              }
            >
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
              <div className="list-filter">
                <span>
                  {view === "favorites" && !submitted
                    ? "이 브라우저에 저장돼요"
                    : nearbyLimit !== null && !submitted ? "가까운 5곳 · 직선거리 기준" : "가까운 순 · 직선거리 기준"}
                </span>
                <label>
                  <input
                    type="checkbox"
                    checked={filter}
                    onChange={(e) => setFilter(e.target.checked)}
                  />
                  대여 가능만
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
                      {place.sourceUrl && (
                        <a className="place-source" href={place.sourceUrl} target="_blank" rel="noopener noreferrer">
                          카카오맵 장소 정보 ↗
                        </a>
                      )}
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
            stations={Array.from(merged.values())}
            selectedId={selectedId}
            focus={focus}
            userLocation={userLocation}
            onSelect={select}
            onViewport={setViewport}
            onUserPan={location => {
              setCenter(location);
              setLabel("지도 중심 주변");
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
            {view === "route" ? "도보 · 따릉이 · 도보 길찾기" : label}
            <span>목록 {radius < 1000 ? `${radius}m` : `${radius / 1000}km`} · {mapStations.isFetching ? "지도 갱신 중" : `지도 ${mapStations.data?.length ?? 0}곳`}</span>
          </div>
          {mapStations.isError && <button className="search-this-area" onClick={() => void mapStations.refetch()}>
            <Icon name="refresh" size={17} />지도 대여소 다시 불러오기
          </button>}
          <button
            className="location-button"
            aria-label="현재 위치에서 대여소 찾기"
            onClick={locate}
            disabled={locating}
          >
            <Icon name={locating ? "refresh" : "locate"} size={21} />
          </button>
          <div className="map-legend" aria-label="대여 수량 범례">
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
          </div>
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
