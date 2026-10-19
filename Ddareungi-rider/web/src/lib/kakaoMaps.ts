import type { Coordinates } from './types';

declare global {
  interface Window { kakao?: typeof kakao; }
}

let sdkPromise: Promise<typeof kakao.maps> | undefined;
export function loadKakaoMaps(): Promise<typeof kakao.maps> {
  if (window.kakao?.maps?.Map) return Promise.resolve(window.kakao.maps);
  if (sdkPromise) return sdkPromise;
  const key = import.meta.env.VITE_KAKAO_MAPS_JAVASCRIPT_KEY;
  if (!key || !/^[0-9a-f]{32}$/i.test(key)) return Promise.reject(new Error('Map configuration unavailable'));
  sdkPromise = new Promise<typeof kakao.maps>((resolve, reject) => {
    const script = document.createElement('script');
    script.id = 'ddareungrider-map-sdk';
    script.async = true;
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?autoload=false&appkey=${encodeURIComponent(key)}`;
    const fail = () => {
      window.clearTimeout(timer);
      script.remove();
      reject(new Error('Map loading failed'));
    };
    const timer = window.setTimeout(fail, 15000);
    script.onerror = fail;
    script.onload = () => {
      if (!window.kakao?.maps?.load) { fail(); return; }
      window.kakao.maps.load(() => {
        window.clearTimeout(timer);
        if (window.kakao?.maps?.Map) resolve(window.kakao.maps);
        else fail();
      });
    };
    document.head.appendChild(script);
  }).catch(error => { sdkPromise = undefined; throw error; });
  return sdkPromise;
}

export function coordinatesOf(point: kakao.maps.LatLng): Coordinates {
  return { latitude: point.getLat(), longitude: point.getLng() };
}
