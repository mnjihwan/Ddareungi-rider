import { skipToken, useQuery } from '@tanstack/react-query';
import type { Station } from '../../../entities/station/model/types';
import { stationOutlook } from '../../../shared/rider-contract/status';
import { forecastPath, forecastStationIds, loadForecast, loadForecasts } from '../api/forecastApi';

const LIST_REFRESH_MS = 60_000;

export function useStationListForecasts(stations: readonly Station[], minutes: number, { enabled, now }: { enabled: boolean; now: number }) {
  const requested = Boolean(forecastPath && enabled && minutes > 0);
  const stationIds = requested ? forecastStationIds(stations.map(({ stationId }) => stationId)) : [];
  const query = useQuery({
    queryKey: ['list-forecasts', stationIds],
    queryFn: stationIds.length ? ({ signal }) => loadForecasts(stationIds, signal) : skipToken,
    staleTime: LIST_REFRESH_MS,
    refetchInterval: stationIds.length ? LIST_REFRESH_MS : false,
    retry: false,
  });
  const forecastMap = new Map(query.data?.map((forecast) => [forecast.stationId, forecast]));
  const usePredictions = requested && !query.isError && stations.some((station) => (
    stationOutlook(station, forecastMap.get(station.stationId), minutes, now).count !== null
  ));
  const notice = minutes <= 0 ? undefined
    : usePredictions ? `${minutes}분 후 예상 기준 · 예측 없는 대여소는 마지막에 표시해요. 대여를 보장하지 않아요.`
      : !forecastPath ? '예측 서비스 준비 중이에요. 현재 수량으로 표시해요.'
        : query.isFetching ? '예측을 확인하는 동안 현재 수량으로 표시해요.'
          : '사용 가능한 예측이 없어 현재 수량으로 표시해요.';

  return { effectiveMinutes: usePredictions ? minutes : 0, forecastMap, notice };
}

export function useStationForecast(stationId: string) {
  return useQuery({
    queryKey: ['forecast', stationId],
    queryFn: forecastPath ? ({ signal }) => loadForecast(stationId, signal) : skipToken,
    staleTime: 5 * LIST_REFRESH_MS,
  });
}
