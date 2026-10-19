// The Web SDK has no bundled TypeScript declarations. These signatures cover
// the API surface used here, following https://apis.map.kakao.com/web/documentation/.
declare namespace kakao.maps {
  function load(callback: () => void): void;
  class LatLng {
    constructor(latitude: number, longitude: number);
    getLat(): number;
    getLng(): number;
  }
  class LatLngBounds {
    constructor(southwest?: LatLng, northeast?: LatLng);
    extend(point: LatLng): void;
    getSouthWest(): LatLng;
    getNorthEast(): LatLng;
  }
  class Map {
    constructor(container: HTMLElement, options: { center: LatLng; level: number; minLevel?: number; maxLevel?: number });
    relayout(): void;
    getCenter(): LatLng;
    setCenter(point: LatLng): void;
    getLevel(): number;
    setLevel(level: number, options?: { animate?: boolean }): void;
    getBounds(): LatLngBounds;
    setBounds(bounds: LatLngBounds, top?: number, right?: number, bottom?: number, left?: number): void;
    panBy(dx: number, dy: number): void;
    getProjection(): { containerPointFromCoords(point: LatLng): { x: number; y: number } };
  }
  class CustomOverlay {
    constructor(options: { map: Map; position: LatLng; content: HTMLElement; clickable?: boolean; xAnchor?: number; yAnchor?: number; zIndex?: number });
    setMap(map: Map | null): void;
    setPosition(point: LatLng): void;
    setZIndex(zIndex: number): void;
  }
  class Polyline {
    constructor(options: { map: Map; path: LatLng[]; strokeColor: string; strokeWeight: number; strokeStyle: string; strokeOpacity: number; zIndex: number });
    setMap(map: Map | null): void;
  }
  namespace event {
    function addListener(target: Map, type: string, callback: () => void): void;
    function removeListener(target: Map, type: string, callback: () => void): void;
  }
}
