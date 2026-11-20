import * as Location from 'expo-location';
import { useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { createLatestRequest } from '../../../shared/api/latestRequest';
import type { Coordinates } from '../../../shared/rider-contract/types';
import { locationLossOnResume, LOCATION_MAX_AGE_MS, LOCATION_MESSAGES, readLocation, type LocationFix, type LocationLoss } from './location';

const source = {
  requestPermission: async () => (await Location.requestForegroundPermissionsAsync()).granted,
  hasServices: Location.hasServicesEnabledAsync,
  getFix: async () => {
    const { coords: { latitude, longitude, accuracy }, timestamp } = await Location.getCurrentPositionAsync({
      accuracy: Location.Accuracy.Balanced, mayShowUserSettingsDialog: false,
    });
    return { coordinate: { latitude, longitude }, accuracy, timestamp };
  },
};

export function useCurrentLocation() {
  const request = useMemo(createLatestRequest, []);
  const [fix, setFix] = useState<LocationFix>();
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string>();
  const [locationLoss, setLocationLoss] = useState<LocationLoss>();
  const resume = useRef(0);
  useEffect(() => request.cancel, [request]);

  useEffect(() => {
    if (!fix || pending) return;
    let expiry: ReturnType<typeof setTimeout> | undefined;
    function forgetLocation(loss: LocationLoss) {
      clearTimeout(expiry);
      resume.current += 1;
      setFix(undefined);
      setLocationLoss(loss);
      setMessage(loss.message);
    }
    const scheduleExpiry = (version: number) => {
      const delay = Math.max(0, fix.timestamp + LOCATION_MAX_AGE_MS + 1 - Date.now());
      expiry = setTimeout(() => {
        if (resume.current === version) forgetLocation({ reason: 'expired', message: LOCATION_MESSAGES.stale });
      }, delay);
    };
    if (AppState.currentState == null || AppState.currentState === 'active') scheduleExpiry(resume.current);
    const subscription = AppState.addEventListener('change', (state) => {
      clearTimeout(expiry);
      const version = ++resume.current;
      if (state !== 'active') return;
      void Location.getForegroundPermissionsAsync().then(async ({ granted }) => {
        if (resume.current !== version) return;
        const services = granted ? await Location.hasServicesEnabledAsync() : true;
        if (resume.current !== version) return;
        const loss = locationLossOnResume(fix, granted, services);
        if (loss) forgetLocation(loss);
        else scheduleExpiry(version);
      }).catch(() => {
        if (resume.current === version) scheduleExpiry(version);
      });
    });
    return () => {
      clearTimeout(expiry);
      resume.current += 1;
      subscription.remove();
    };
  }, [fix, pending]);

  function cancel() {
    request.cancel();
    setPending(false);
    setMessage(undefined);
  }

  async function locate(onFound: (point: Coordinates) => void) {
    resume.current += 1;
    const signal = request.start();
    setPending(true);
    setMessage(undefined);
    try {
      const nextFix = await readLocation(source, signal);
      if (signal.aborted) return;
      setFix(nextFix);
      onFound(nextFix.coordinate);
    } catch (error) {
      if (signal.aborted) return;
      const knownMessage = Object.values(LOCATION_MESSAGES).find((text) => error instanceof Error && error.message === text);
      if (knownMessage === LOCATION_MESSAGES.permission || knownMessage === LOCATION_MESSAGES.services) {
        setFix(undefined);
        setLocationLoss({ reason: 'access', message: knownMessage });
      }
      setMessage(knownMessage ?? LOCATION_MESSAGES.unavailable);
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }

  return { point: fix?.coordinate, accuracy: fix?.accuracy ?? undefined, pending, message, locationLoss, locate, cancel };
}
