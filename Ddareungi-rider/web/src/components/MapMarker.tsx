import {useEffect, useRef, useState, type ReactNode} from "react";
import {createPortal} from "react-dom";
import type {Coordinates} from "../lib/types";

// Let the SDK move the geographic anchor during dragging and zoom animations.
export function MapMarker({map, sdk, location, selected = false, children}: {
  map: kakao.maps.Map; sdk: typeof kakao.maps; location: Coordinates; selected?: boolean; children: ReactNode;
}) {
  const [container] = useState(() => document.createElement("div"));
  const marker = useRef<kakao.maps.CustomOverlay | null>(null);
  useEffect(() => {
    container.className = "native-marker-content";
    marker.current = new sdk.CustomOverlay({map, position: new sdk.LatLng(location.latitude, location.longitude),
      content: container, clickable: true, xAnchor: 0, yAnchor: 0, zIndex: selected ? 100 : 20});
    return () => { marker.current?.setMap(null); marker.current = null; };
  }, [map, sdk, container]);
  useEffect(() => {
    marker.current?.setPosition(new sdk.LatLng(location.latitude, location.longitude));
    marker.current?.setZIndex(selected ? 100 : 20);
  }, [sdk, location.latitude, location.longitude, selected]);
  return createPortal(children, container);
}
