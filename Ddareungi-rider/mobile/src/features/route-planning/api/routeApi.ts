import { queryOptions } from '@tanstack/react-query';
import { apiClient, unwrapApiData, type ApiEnvelope } from '../../../shared/api/client.ts';
import { buildRouteSearchBody, toRoutePlan, type RouteStationChoice } from '../../../shared/rider-contract/routeSearch.ts';
import type { Coordinates, RouteSearchResult, RoutingStatus } from '../../../shared/rider-contract/types';

export const routingStatusQuery = queryOptions({
  queryKey: ['routing-status'],
  queryFn: async ({ signal }) => {
    const { data } = await apiClient.get<ApiEnvelope<RoutingStatus>>('/api/v1/routes/status', { signal });
    return unwrapApiData(data);
  },
  staleTime: 60_000,
  retry: false,
});

export async function fetchRecommendedRoute(
  origin: Coordinates, destination: Coordinates, stations: RouteStationChoice, status: RoutingStatus, signal: AbortSignal,
) {
  const { data } = await apiClient.post<ApiEnvelope<RouteSearchResult>>(
    '/api/v1/routes/search', buildRouteSearchBody(origin, destination, stations, status), { signal },
  );
  const result = unwrapApiData(data);
  const plan = toRoutePlan(result, data.meta, origin, destination);
  const sources = [result.recommended, ...result.alternatives];
  // The map and leg list draw exactly WALK, BIKE, WALK with at least two points per leg.
  const isDrawable = (index: number) =>
    sources[index].segments.map(({ type }) => type).join(',') === 'WALK,BIKE,WALK'
    && plan.routes[index].legs.every(({ path }) => path.length >= 2);
  if (!isDrawable(0)) throw new Error('Invalid route legs');
  const alternatives = plan.routes.slice(1).filter((_, index) => isDrawable(index + 1));
  return { route: plan.routes[0], alternatives, partial: plan.partial, receivedAt: Date.now() };
}
