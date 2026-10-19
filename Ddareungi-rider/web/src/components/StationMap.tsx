import { useEffect, useRef, useState, type ReactNode } from "react";
import { loadKakaoMaps, coordinatesOf } from "../lib/kakaoMaps";
import { clusterPoints, separateMarkerGroups, type MarkerGroup } from "../lib/clusters";
import type { Coordinates, Station, MapBounds, BikeRoute, Place, RouteEndpointRole } from "../lib/types";
import { availability, AVAILABILITY_LABEL } from "../lib/status";
import { Icon } from "./Icon";
import { MapMarker } from "./MapMarker";

interface Props {
  theme?: "light" | "dark";
  stations: Station[];
  routeStationsOnly?: boolean;
  selectedId: string | null;
  focus: { location: Coordinates; revision: number };
  userLocation: Coordinates | null;
  onSelect: (station: Station) => void;
  onViewport: (bounds: MapBounds) => void;
  onUserPan: (location: Coordinates) => void;
  onReady: (ready: boolean) => void;
  route?: BikeRoute | null;
  pickingEndpoint?: RouteEndpointRole | null;
  onPickEndpoint?: (location:Coordinates) => void;
  onCancelPick?: () => void;
  focusedPlace?: Place | null;
  onDismissPlace?: () => void;
}

export function StationMap({
  theme = "light",
  stations,
  routeStationsOnly = false,
  selectedId,
  focus,
  userLocation,
  onSelect,
  onViewport,
  onUserPan,
  onReady,
  route,
  pickingEndpoint,
  onPickEndpoint,
  onCancelPick,
  focusedPlace,
  onDismissPlace,
}: Props) {
  const host = useRef<HTMLDivElement>(null);
  const mapRef = useRef<kakao.maps.Map | null>(null);
  const routeRef = useRef(route);
  routeRef.current = route;
  const callbacks = useRef({ onViewport, onUserPan, onReady, onDismissPlace });
  callbacks.current = { onViewport, onUserPan, onReady, onDismissPlace };
  const [state, setState] = useState<"loading" | "ready" | "unavailable">(
    "loading",
  );
  const [version, setVersion] = useState(0);
  const [retry, setRetry] = useState(0);
  const [groups, setGroups] = useState<MarkerGroup[]>([]);

  useEffect(() => {
    const map = mapRef.current, sdk = window.kakao?.maps;
    if (!map || !sdk || state !== "ready" || !route) return;
    map.relayout();
    const lines = route.legs.map(leg => new sdk.Polyline({map,
      path:leg.path.map(point=>new sdk.LatLng(point.latitude,point.longitude)),
      strokeColor:leg.mode==='walk'?(theme==='dark'?'#92bfff':'#2563a6'):(theme==='dark'?'#5dd5e5':'#0087a8'),strokeWeight:leg.mode==='walk'?5:7,
      strokeStyle:leg.mode==='walk'?'shortdash':'solid',strokeOpacity:0.95,zIndex:5}));
    return () => lines.forEach(line=>line.setMap(null));
  }, [route,state,theme]);

  // A theme change redraws lines without changing the user's map position or zoom.
  useEffect(() => {
    if (state === 'ready' && route && mapRef.current && window.kakao?.maps) {
      fitRoute(mapRef.current, window.kakao.maps, route);
    }
  }, [route,state]);

  useEffect(() => {
    if (!host.current) return;
    const observer = new ResizeObserver(() => setVersion((value) => value + 1));
    observer.observe(host.current);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    let disposed = false;
    let observer: ResizeObserver | undefined;
    let idle: (() => void) | undefined;
    let dragEnd: (() => void) | undefined;
    let mapClick: (() => void) | undefined;
    let viewportTimer: number | undefined;
    let initializedSdk: typeof kakao.maps | undefined;
    let initializedMap: kakao.maps.Map | undefined;
    setState("loading");
    loadKakaoMaps()
      .then((sdk) => {
        if (disposed || !host.current) return;
        initializedSdk = sdk;
        const map = new sdk.Map(host.current, {
          center: new sdk.LatLng(
            focus.location.latitude,
            focus.location.longitude,
          ),
          level: 3,
          minLevel: 1,
          maxLevel: 8,
        });
        initializedMap = map;
        mapRef.current = map;
        const updateViewport = () => {
          window.clearTimeout(viewportTimer);
          viewportTimer = window.setTimeout(() => {
            if (disposed) return;
            const bounds = map.getBounds();
            callbacks.current.onViewport({south: bounds.getSouthWest().getLat(), north: bounds.getNorthEast().getLat(),
              west: bounds.getSouthWest().getLng(), east: bounds.getNorthEast().getLng()});
          }, 200);
        };
        idle = () => {
          updateViewport();
          setVersion((value) => value + 1);
        };
        dragEnd = () => {
          callbacks.current.onUserPan(coordinatesOf(map.getCenter()));
        };
        sdk.event.addListener(map, "idle", idle);
        sdk.event.addListener(map, "dragend", dragEnd);
        mapClick = () => callbacks.current.onDismissPlace?.();
        sdk.event.addListener(map, "click", mapClick);
        observer = new ResizeObserver(() => {
          const center = map.getCenter();
          map.relayout();
          map.setCenter(center);
          if (routeRef.current) fitRoute(map, sdk, routeRef.current);
          updateViewport();
          setVersion((value) => value + 1);
        });
        observer.observe(host.current);
        setState("ready");
        callbacks.current.onReady(true);
        setVersion((value) => value + 1);
        updateViewport();
      })
      .catch(() => {
        if (!disposed) {
          setState("unavailable");
          callbacks.current.onReady(false);
        }
      });
    return () => {
      disposed = true;
      observer?.disconnect();
      window.clearTimeout(viewportTimer);
      if (idle && initializedSdk && initializedMap) initializedSdk.event.removeListener(initializedMap, "idle", idle);
      if (dragEnd && initializedSdk && initializedMap) initializedSdk.event.removeListener(initializedMap, "dragend", dragEnd);
      if (mapClick && initializedSdk && initializedMap) initializedSdk.event.removeListener(initializedMap, "click", mapClick);
      host.current?.replaceChildren();
      mapRef.current = null;
    };
    // SDK initialization stays independent of fetched station updates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [retry]);

  useEffect(() => {
    const map = mapRef.current,
      sdk = window.kakao?.maps;
    if (!map || !sdk || state !== "ready") return;
    map.setCenter(
      new sdk.LatLng(focus.location.latitude, focus.location.longitude),
    );
    if (selectedId)
      map.panBy(
          window.innerWidth >= 768 ? -210 : 0,
          window.innerWidth < 768 ? 130 : 0,
      );
    setVersion((value) => value + 1);
    // Closing a panel or refreshing data must not reset a manually panned map.
  }, [focus, state]);

  useEffect(() => {
    const map = mapRef.current,
      sdk = window.kakao?.maps;
    if (!host.current) return;
    const width = host.current.clientWidth,
      height = host.current.clientHeight;
    const points = stations
      .map((station) => {
        if (state === "ready" && map && sdk) {
          const point = map
            .getProjection()
            .containerPointFromCoords(
              new sdk.LatLng(
                station.location.latitude,
                station.location.longitude,
              ),
            );
          return { id: station.stationId, x: point.x, y: point.y };
        }
        // An explicitly labeled schematic preview, not a basemap or a route.
        return {
          id: station.stationId,
          x:
            width * 0.45 +
            ((station.location.longitude - focus.location.longitude) / 0.025) *
              width,
          y:
            height * 0.46 -
            ((station.location.latitude - focus.location.latitude) / 0.025) *
              height,
        };
      })
      .filter(
        (point) => routeStationsOnly || (
          point.x >= -30 &&
          point.x <= width + 30 &&
          point.y >= 20 &&
          point.y <= height - 40),
      );
    const showIndividual=state==="ready" && map && (routeStationsOnly || map.getLevel()<=4);
    const grouped=clusterPoints(points, selectedId,showIndividual ? 0 : 72,48);
    setGroups(showIndividual ? separateMarkerGroups(grouped,selectedId) : grouped);
  }, [stations, selectedId, version, state, focus, routeStationsOnly]);

  function zoom(delta: number) {
    const map = mapRef.current;
    if (map && state === "ready") map.setLevel(map.getLevel() - delta, {animate: true});
  }

  function selectGroup(group: MarkerGroup) {
    if (group.stationIds.length === 1) {
      const station = stations.find(
        (value) => value.stationId === group.stationIds[0],
      );
      if (station) onSelect(station);
      return;
    }
    const map = mapRef.current,
      sdk = window.kakao?.maps;
    if (!map || !sdk) return;
    const members = stations.filter((station) =>
      group.stationIds.includes(station.stationId),
    );
    if (!members.length) return;
    const first = new sdk.LatLng(
      members[0].location.latitude,
      members[0].location.longitude,
    );
    const bounds = new sdk.LatLngBounds(first, first);
    members.forEach((station) =>
      bounds.extend(
        new sdk.LatLng(station.location.latitude, station.location.longitude),
      ),
    );
    map.setBounds(bounds, 100, 100, 200, 100);
  }

  return (
    <div className="map-surface" aria-label="주변 따릉이 대여소 지도">
      <div ref={host} className="kakao-map" onClickCapture={event => {
        if (!(event.target as Element).closest('.search-place-pin')) onDismissPlace?.();
      }}/>
      {state !== "ready" && (
        <div className="map-preview" aria-hidden="true">
          <svg viewBox="0 0 1200 900" preserveAspectRatio="xMidYMid slice">
            <defs>
              <pattern
                id="city-blocks"
                width="210"
                height="180"
                patternTransform="rotate(-22)"
                patternUnits="userSpaceOnUse"
              >
                <rect width="210" height="180" fill="#edf0e9" />
                <rect
                  x="12"
                  y="12"
                  width="84"
                  height="66"
                  rx="8"
                  fill="#e1e6dc"
                />
                <rect
                  x="108"
                  y="12"
                  width="90"
                  height="66"
                  rx="8"
                  fill="#e6e9e1"
                />
                <rect
                  x="12"
                  y="90"
                  width="84"
                  height="78"
                  rx="8"
                  fill="#e5e9e0"
                />
                <rect
                  x="108"
                  y="90"
                  width="90"
                  height="78"
                  rx="8"
                  fill="#e0e6dc"
                />
                <path d="M0 84h210M102 0v180" stroke="#fff" strokeWidth="9" />
              </pattern>
            </defs>
            <rect width="1200" height="900" fill="url(#city-blocks)" />
            <path
              d="M-100 510C240 500 330 650 800 360S1180 240 1300 310"
              fill="none"
              stroke="#d3e0ce"
              strokeWidth="105"
            />
            <path
              d="M-100 510C240 500 330 650 800 360S1180 240 1300 310"
              fill="none"
              stroke="#e2eddf"
              strokeWidth="82"
            />
            <path
              d="M250-100 670 1000M-50 760 1290 250"
              stroke="#d6d9d1"
              strokeWidth="37"
            />
            <path
              d="M250-100 670 1000M-50 760 1290 250"
              stroke="#fffdf6"
              strokeWidth="30"
            />
            <path
              d="M250-100 670 1000M-50 760 1290 250"
              stroke="#e7debc"
              strokeWidth="2"
              strokeDasharray="8 8"
            />
            <rect
              x="780"
              y="580"
              width="160"
              height="100"
              rx="30"
              transform="rotate(-22 780 580)"
              fill="#d7e4ce"
            />
          </svg>
        </div>
      )}
      <div className="marker-layer">
        {focusedPlace && state==="ready" && mapRef.current && window.kakao?.maps &&
          <MapMarker map={mapRef.current} sdk={window.kakao.maps} location={focusedPlace.location} selected>
            <span className="search-place-pin" aria-label={`선택한 장소 ${focusedPlace.name}`}><Icon name="pin" size={15}/>
              <span>{focusedPlace.name}</span><button aria-label="선택한 장소 표시 지우기" onClick={onDismissPlace}><Icon name="close" size={14}/></button></span>
          </MapMarker>}
        {route && state === "ready" && mapRef.current && window.kakao?.maps &&
          [{name:'출발',location:route.origin},{name:'대여',location:route.departureStation.location},
            {name:'반납',location:route.arrivalStation.location},{name:'도착',location:route.destination}].map(point=>
            <MapMarker key={point.name} map={mapRef.current!} sdk={window.kakao!.maps} location={point.location} selected>
              <span className={`route-endpoint ${point.name==='대여'||point.name==='반납'?'station':''}`}>{point.name}</span>
            </MapMarker>)}
        {groups.map((group) => {
          const members = stations.filter(station => group.stationIds.includes(station.stationId));
          const anchored = (button: ReactNode) => state === "ready" && mapRef.current && window.kakao?.maps
            ? <MapMarker key={group.id} map={mapRef.current} sdk={window.kakao.maps}
                location={{latitude: members.reduce((sum, station) => sum + station.location.latitude, 0) / members.length,
                  longitude: members.reduce((sum, station) => sum + station.location.longitude, 0) / members.length}}
                selected={group.stationIds.includes(selectedId ?? "")} zIndex={routeStationsOnly ? 110 : undefined}>{button}</MapMarker>
            : button;
          const position = state === "ready" ? {left:group.offsetX??0,top:group.offsetY??0} : {left: group.x, top: group.y};
          if (group.stationIds.length > 1)
            return anchored(
              <button
                key={group.id}
                className="cluster-marker"
                data-station-ids={group.stationIds.join(",")}
                style={position}
                onClick={() => selectGroup(group)}
                aria-label={`${group.stationIds.length}개 대여소 확대`}
                disabled={state !== "ready"}
              >
                {group.stationIds.length}
                <small>대여소</small>
              </button>
            );
          const station = stations.find(
            (value) => value.stationId === group.stationIds[0],
          );
          if (!station) return null;
          const status = availability(station);
          return anchored(
            <span key={group.id} className="individual-marker">
            {!!(group.offsetX || group.offsetY) && state==="ready" && <span className="marker-connector" aria-hidden="true"
              style={{width:Math.hypot(group.offsetX??0,group.offsetY??0),transform:`rotate(${Math.atan2(group.offsetY??0,group.offsetX??0)}rad)`}}/>}
            <button
              key={group.id}
              className={`station-marker ${status} ${station.stationId === selectedId ? "selected" : ""}`}
              style={position}
              data-station-id={station.stationId}
              data-route-station={routeStationsOnly || undefined}
              data-station-ids={group.stationIds.join(",")}
              onClick={() => onSelect(station)}
              aria-label={`${station.stationName}, ${AVAILABILITY_LABEL[status]}, ${status === "unknown" ? "수량 미확인" : `${station.availableBikeCount}대`}`}
              aria-pressed={station.stationId === selectedId}
            >
              <Icon name="bike" size={18} />
              <b>{status === "unknown" ? "—" : station.availableBikeCount}</b>
              <span className="marker-name">{station.stationName}</span>
            </button>
            </span>
          );
        })}
        {userLocation && state === "ready" && mapRef.current && window.kakao?.maps && (
          <MapMarker map={mapRef.current} sdk={window.kakao.maps} location={userLocation}>
          <div
            className="user-location-marker"
            aria-label="현재 위치"
          />
          </MapMarker>
        )}
      </div>
      {pickingEndpoint && <>
        <div className="origin-crosshair" aria-hidden="true">
          <span className="origin-crosshair-target" />
          <span className="origin-crosshair-label">{pickingEndpoint==='origin'?'출발 위치':'도착 위치'}</span>
          <svg className="origin-crosshair-pin" width="48" height="64" viewBox="0 0 48 64">
            <path d="M24 62C20 54 3 36 3 24a21 21 0 0 1 42 0c0 12-17 30-21 38Z" fill="currentColor" stroke="white" strokeWidth="3" strokeLinejoin="round" />
            <circle cx="24" cy="24" r="8" fill="white" />
          </svg>
        </div>
        <div className="origin-picker" role="status"><p>지도를 움직여 중앙 핀을 {pickingEndpoint==='origin'?'출발':'도착'} 위치에 맞춰 주세요.</p>
          <button className="primary-button" disabled={state!=="ready"} onClick={()=>{if(mapRef.current)onPickEndpoint?.(coordinatesOf(mapRef.current.getCenter()));}}>{pickingEndpoint==='origin'?'이 위치에서 출발':'이 위치로 도착'}</button>
          <button onClick={onCancelPick}>취소</button>
        </div>
      </>}
      <div className="zoom-controls">
        <button
          onClick={() => zoom(1)}
          aria-label="지도 확대"
          disabled={state !== "ready"}
        >
          <Icon name="plus" />
        </button>
        <button
          onClick={() => zoom(-1)}
          aria-label="지도 축소"
          disabled={state !== "ready"}
        >
          <Icon name="minus" />
        </button>
      </div>
      {state !== "ready" && (
        <div className="map-connection" role="status">
          <Icon name={state === "loading" ? "refresh" : "info"} size={17} />
          <div>
            <b>
              {state === "loading"
                ? "지도를 준비하고 있어요"
                : "지도 연결을 기다리고 있어요"}
            </b>
            <span>인터페이스 미리보기 · 실제 지도가 아닙니다</span>
          </div>
          {state === "unavailable" && (
            <button
              onClick={() => setRetry((value) => value + 1)}
              aria-label="지도 연결 다시 시도"
            >
              <Icon name="refresh" size={17} />
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function fitRoute(map: kakao.maps.Map, sdk: typeof kakao.maps, route: BikeRoute) {
  const bounds = new sdk.LatLngBounds();
  [route.origin, route.destination, ...route.legs.flatMap(leg => leg.path)]
    .forEach(point => bounds.extend(new sdk.LatLng(point.latitude, point.longitude)));
  const mobile = window.innerWidth < 768;
  map.setBounds(bounds, mobile ? 65 : 100, mobile ? 30 : 60, mobile ? 40 : 100, mobile ? 30 : 60);
}
