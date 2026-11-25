import {
  initializeKakaoMapSDK,
  KakaoMapView,
  type CameraOptions,
  type KakaoMapViewRef,
} from '@react-native-kakao/map';
import { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, type Ref } from 'react';
import { ActivityIndicator, Image, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import type { Coordinates } from '../../../shared/types/coordinates';
import type { MapBounds } from '../../../shared/rider-contract/types';
import { markerScene, routeScene, type MapMarker, type MapRouteSegment, type MarkerAssets, type MarkerStock } from '../model/scenes';

interface RiderMapProps {
  ref?: Ref<RiderMapHandle>;
  initialCamera: CameraOptions;
  markers: MapMarker[];
  segments?: MapRouteSegment[];
  fitCoordinates?: Coordinates[];
  onMarkerPress: (id: string) => void;
  onViewportChange?: (bounds: MapBounds) => void;
  onMapPress?: (point: Coordinates) => void;
  onCenterChange?: (point: Coordinates, reason?: 'gesture' | 'developer') => void;
  onZoomChange?: (zoom: number) => void;
  onCameraGesture?: () => void;
  focus?: Coordinates;
  zoomRequest?: { change: number } | { position: Coordinates; zoom: number };
  bottomInset?: number;
  focusVisibleInset?: number;
  fitRoute?: boolean;
  logoInset?: number;
  dark?: boolean;
}

export interface RiderMapHandle {
  readCenter: () => Promise<Coordinates | undefined>;
}

const nativeAppKey = process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY?.trim();
const MAP_OPTIONS = {
  gestures: { rotate: false, rotateZoom: false, tilt: false },
};
const EMPTY_SEGMENTS: MapRouteSegment[] = [];
const icons: Record<MarkerStock, [number, number]> = {
  ready: [require('../../../../assets/web-ui/marker-ready.png'), require('../../../../assets/web-ui/marker-ready-dark.png')],
  low: [require('../../../../assets/web-ui/marker-low.png'), require('../../../../assets/web-ui/marker-low-dark.png')],
  empty: [require('../../../../assets/web-ui/marker-empty.png'), require('../../../../assets/web-ui/marker-empty-dark.png')],
  unknown: [require('../../../../assets/web-ui/marker-unknown.png'), require('../../../../assets/web-ui/marker-unknown-dark.png')],
  stale: [require('../../../../assets/web-ui/marker-stale.png'), require('../../../../assets/web-ui/marker-stale-dark.png')],
};

function mapAsset(asset: number, width: number, height: number) {
  return { uri: Image.resolveAssetSource(asset).uri, width, height };
}

function mapAssets(dark: boolean): MarkerAssets {
  const theme = dark ? 1 : 0;
  return {
    ready: mapAsset(icons.ready[theme], 66, 50),
    low: mapAsset(icons.low[theme], 66, 50),
    empty: mapAsset(icons.empty[theme], 66, 50),
    unknown: mapAsset(icons.unknown[theme], 66, 50),
    stale: mapAsset(icons.stale[theme], 66, 50),
    selected: mapAsset(require('../../../../assets/web-ui/marker-selected.png'), 66, 50),
    location: mapAsset(require('../../../../assets/web-ui/user-position.png'), 36, 36),
    cluster: mapAsset(require('../../../../assets/web-ui/cluster-marker.png'), 58, 58),
    origin: mapAsset(require('../../../../assets/web-ui/endpoint-origin.png'), 46, 32),
    destination: mapAsset(require('../../../../assets/web-ui/endpoint-destination.png'), 46, 32),
    rental: mapAsset(require('../../../../assets/web-ui/endpoint-rental.png'), 46, 32),
    return: mapAsset(require('../../../../assets/web-ui/endpoint-return.png'), 46, 32),
  };
}

export function RiderMap({
  ref, initialCamera, markers, onMarkerPress, onViewportChange, onMapPress, onCenterChange, onZoomChange, onCameraGesture,
  segments = EMPTY_SEGMENTS, fitCoordinates, focus, zoomRequest, bottomInset = 0, focusVisibleInset,
  logoInset = 0, fitRoute = false, dark = false,
}: RiderMapProps) {
  const map = useRef<KakaoMapViewRef>(null);
  const [attempt, setAttempt] = useState(0);
  const [initialized, setInitialized] = useState(false);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string>();
  const moving = useRef(false);
  const projection = useRef(0);
  const assets = useMemo(() => mapAssets(dark), [dark]);
  const labels = useMemo(() => markerScene(markers, assets, dark), [markers, assets, dark]);
  const routes = useMemo(() => routeScene(segments, dark), [segments, dark]);
  const options = useMemo(() => ({
    ...MAP_OPTIONS,
    zoomRange: { min: 11, max: 18 },
    padding: { top: fitRoute ? 34 : 0, right: fitRoute ? 36 : 0, bottom: bottomInset, left: fitRoute ? 24 : 0 },
    logo: { gravity: 'bottomLeft' as const, x: 8, y: Math.max(0, logoInset - bottomInset) + 5 },
  }), [bottomInset, logoInset, fitRoute]);

  useImperativeHandle(ref, () => ({
    async readCenter() {
      const view = map.current;
      if (!view || !ready) return;
      const { viewport } = await view.getMapInfo();
      return view.screenToCoordinate({ x: viewport.width / 2, y: viewport.height / 2 });
    },
  }), [ready]);

  useEffect(() => {
    if (Platform.OS === 'web' || !nativeAppKey) return;
    let active = true;
    initializeKakaoMapSDK(nativeAppKey).then(
      () => { if (active) setInitialized(true); },
      () => { if (active) setError('카카오 지도 SDK를 시작하지 못했습니다.'); },
    );
    return () => {
      active = false;
      projection.current += 1;
    };
  }, [attempt]);

  const publishBounds = useCallback(async () => {
    const view = map.current;
    if (!onViewportChange || !view || moving.current) return;
    const version = ++projection.current;
    try {
      const { viewport } = await view.getMapInfo();
      const { width, height } = viewport;
      if (projection.current !== version || width < 3 || height < 3) return;
      // Native screen coordinates use logical pixels. Stay inside the viewport at each corner.
      const left = 1;
      const right = width - 1;
      const top = 1;
      const bottom = height - 1;
      const corners = await Promise.all([
        view.screenToCoordinate({ x: left, y: top }),
        view.screenToCoordinate({ x: right, y: top }),
        view.screenToCoordinate({ x: left, y: bottom }),
        view.screenToCoordinate({ x: right, y: bottom }),
      ]);
      if (projection.current !== version) return;
      const latitudes = corners.map((point) => point.latitude);
      const longitudes = corners.map((point) => point.longitude);
      onViewportChange({
        south: Math.min(...latitudes),
        north: Math.max(...latitudes),
        west: Math.min(...longitudes),
        east: Math.max(...longitudes),
      });
    } catch {
      // A native layout or camera change can invalidate a corner during projection.
      // The next viewport or idle event queries the new camera.
    }
  }, [onViewportChange]);

  useEffect(() => {
    // Fit again whenever the route overview returns, e.g. after closing a route station's detail.
    if (!ready || !fitRoute || !fitCoordinates?.length) return;
    map.current?.updateCamera({ type: 'fit', coordinates: fitCoordinates }).catch(() => {
      setError('경로에 맞춰 지도를 이동하지 못했습니다.');
    });
  }, [ready, fitRoute, fitCoordinates]);

  useEffect(() => {
    const view = ready ? map.current : null;
    if (!view || !focus) return;
    let active = true;
    const reposition = async () => {
      let position = focus;
      if (focusVisibleInset !== undefined) {
        const [{ viewport }, camera] = await Promise.all([view.getMapInfo(), view.getCamera()]);
        const visibleHeight = viewport.height - focusVisibleInset;
        if (visibleHeight > 24) {
          // Project a camera offset to keep the pin above the sheet.
          const target = await view.screenToCoordinate({
            x: viewport.width / 2,
            y: Math.max(12, Math.min(visibleHeight - 12, viewport.height / 2 - 130)),
          });
          position = {
            latitude: focus.latitude + camera.latitude - target.latitude,
            longitude: focus.longitude + camera.longitude - target.longitude,
          };
        }
      }
      if (active) await view.updateCamera({ type: 'position', position });
    };
    void reposition().catch(() => {
      if (active) setError('선택한 위치로 지도를 이동하지 못했습니다.');
    });
    return () => { active = false; };
  }, [ready, focus, focusVisibleInset]);

  useEffect(() => {
    const view = ready ? map.current : null;
    if (!view || !zoomRequest) return;
    const move = async () => {
      if ('position' in zoomRequest) {
        await view.updateCamera({ type: 'position', position: zoomRequest.position });
        await view.updateCamera({ type: 'zoom', zoomLevel: zoomRequest.zoom });
        return;
      }
      const camera = await view.getCamera();
      const zoom = Math.max(11, Math.min(18, camera.zoomLevel + zoomRequest.change));
      await view.updateCamera({ type: 'zoom', zoomLevel: zoom });
    };
    void move().catch(() => setError('지도 확대·축소를 완료하지 못했어요.'));
  }, [ready, zoomRequest]);

  function retryMap() {
    moving.current = false;
    setReady(false);
    setInitialized(false);
    setError(undefined);
    setAttempt((current) => current + 1);
  }

  if (Platform.OS === 'web' || !nativeAppKey) {
    const message = Platform.OS === 'web'
      ? '카카오 지도는 Android와 iOS 앱에서 이용할 수 있습니다.'
      : '지도 설정에 필요한 카카오 네이티브 앱 키를 확인해 주세요.';
    return <View style={styles.frame}><Text style={styles.message}>{message}</Text></View>;
  }

  return (
    <View style={styles.frame}>
      {initialized && (
        <KakaoMapView
          key={attempt}
          ref={map}
          style={StyleSheet.absoluteFill}
          initialCamera={initialCamera}
          options={options}
          labels={labels}
          routes={routes}
          onMapReady={() => {
            setReady(true);
            setError(undefined);
            onCenterChange?.({ latitude: initialCamera.latitude, longitude: initialCamera.longitude });
            void publishBounds();
          }}
          onError={({ code }) => setError(`카카오 지도에 오류가 발생했습니다. (${code})`)}
          onCameraMoveStart={({ reason }) => {
            moving.current = true;
            projection.current += 1;
            if (reason === 'gesture') onCameraGesture?.();
          }}
          onCameraIdle={({ reason, camera: { latitude, longitude, zoomLevel } }) => {
            moving.current = false;
            onCenterChange?.({ latitude, longitude }, reason);
            onZoomChange?.(zoomLevel);
            void publishBounds();
          }}
          onViewportChanged={() => void publishBounds()}
          onLabelClick={({ id }) => onMarkerPress(id.replace(/^(count|cluster-count|cluster-stock):/, ''))}
          onMapClick={({ latitude, longitude }) => onMapPress?.({ latitude, longitude })}
        />
      )}
      {ready && dark && <View pointerEvents="none" style={[StyleSheet.absoluteFill, styles.darkMap]} />}
      {!ready && !error && <ActivityIndicator color="#087A57" accessibilityLabel="지도 불러오는 중" />}
      {error && (
        <View style={styles.error}>
          <Text style={styles.message}>{error}</Text>
          <Pressable onPress={retryMap} accessibilityRole="button" style={styles.retry}>
            <Text style={styles.retryText}>지도 다시 열기</Text>
          </Pressable>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  frame: {
    flex: 1,
    minHeight: 160,
    overflow: 'hidden',
    backgroundColor: '#E8EDF3',
    alignItems: 'center',
    justifyContent: 'center',
  },
  darkMap: { backgroundColor: 'rgba(18, 24, 37, 0.47)' },
  message: { paddingHorizontal: 16, textAlign: 'center', color: '#263C30' },
  error: { paddingVertical: 12, alignItems: 'center', borderRadius: 12, backgroundColor: '#F4FAF7' },
  retry: { paddingHorizontal: 16, paddingVertical: 10 },
  retryText: { color: '#086847', fontWeight: '700' },
});
