import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { ApiConfigError } from '../../../shared/api/client';
import { createLatestRequest } from '../../../shared/api/latestRequest';
import { hasChosenStation, routeErrorMessage } from '../../../shared/rider-contract/routeSearch';
import type { Coordinates, RouteEndpointRole } from '../../../shared/rider-contract/types';
import { fetchRecommendedRoute, routingStatusQuery } from '../api/routeApi';
import { asFixedLocation, type Endpoint } from './endpoints';
export type { Endpoint } from './endpoints';

export function useRoutePlanner() {
  const [endpoints, setEndpoints] = useState<Partial<Record<RouteEndpointRole, Endpoint>>>({});
  const [route, setRoute] = useState<Awaited<ReturnType<typeof fetchRecommendedRoute>>>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  const request = useMemo(createLatestRequest, []);
  const status = useQuery(routingStatusQuery);
  useEffect(() => request.cancel, [request]);

  function clearRoute() {
    request.cancel();
    setRoute(undefined);
    setPending(false);
    setError(undefined);
  }

  function setEndpoint(role: RouteEndpointRole, endpoint?: Endpoint) {
    clearRoute();
    setEndpoints((previous) => ({ ...previous, [role]: endpoint }));
  }

  function clearDeviceEndpoints() {
    if (!endpoints.origin?.fromDevice && !endpoints.destination?.fromDevice) return;
    clearRoute();
    setEndpoints((previous) => ({
      origin: previous.origin?.fromDevice ? undefined : previous.origin,
      destination: previous.destination?.fromDevice ? undefined : previous.destination,
    }));
  }

  function freezeDeviceEndpoints() {
    setEndpoints((previous) => ({
      origin: asFixedLocation(previous.origin),
      destination: asFixedLocation(previous.destination),
    }));
  }

  function labelEndpoint(role: RouteEndpointRole, coordinate: Coordinates, name: string, address: string) {
    setEndpoints((previous) => {
      const endpoint = previous[role];
      if (endpoint?.coordinate !== coordinate) return previous;
      return { ...previous, [role]: { ...endpoint, name, address } };
    });
  }

  async function search() {
    const { origin, destination } = endpoints;
    if (!origin || !destination) return;
    clearRoute();
    const signal = request.start();
    setPending(true);
    const stations = { departureStationId: origin.stationId, arrivalStationId: destination.stationId };
    try {
      const { data: routingStatus } = await status.refetch({ throwOnError: true });
      if (signal.aborted) return;
      if (!routingStatus?.configured) {
        setError('현재 서버에서 길찾기를 이용할 수 없어요.');
        return;
      }
      const result = await fetchRecommendedRoute(origin.coordinate, destination.coordinate, stations, routingStatus, signal);
      if (!signal.aborted) setRoute(result);
    } catch (error) {
      if (!signal.aborted) {
        setError(error instanceof ApiConfigError ? error.message
          : routeErrorMessage(error, { stationChosen: hasChosenStation(stations) }));
      }
    } finally {
      if (!signal.aborted) setPending(false);
    }
  }

  const configured = status.isSuccess && !status.isFetching ? status.data.configured : undefined;
  return { endpoints, setEndpoint, labelEndpoint, clearDeviceEndpoints, freezeDeviceEndpoints, clearRoute, route, search, pending, error, configured };
}
