import { queryOptions } from '@tanstack/react-query';
import { apiClient, unwrapApiData, type ApiEnvelope } from '../../../shared/api/client.ts';
import { buildRouteSearchBody, toDrawableRoutePlan, type RouteStationChoice } from '../../../shared/rider-contract/routeSearch.ts';
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
  // Rejects an undrawable recommended route and drops undrawable alternatives.
  const plan = toDrawableRoutePlan(unwrapApiData(data), data.meta, origin, destination);
  const [route, ...alternatives] = plan.routes;
  return { route, alternatives, partial: plan.partial, receivedAt: Date.now() };
}
