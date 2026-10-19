import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  availability,
  AVAILABILITY_LABEL,
  distanceLabel,
  observationLabel,
  forecastAt,
} from "../lib/status";
import { forecastPath, loadForecast } from "../lib/api";
import type { Station } from "../lib/types";
import { Icon } from "./Icon";

export function StationDetail({
  station,
  saved,
  sample = false,
  onFavorite,
  onClose,
  onRoute,
}: {
  station: Station;
  saved: boolean;
  sample?: boolean;
  onFavorite: () => void;
  onClose: () => void;
  onRoute: () => void;
}) {
  const [minutes, setMinutes] = useState(15);
  const state = availability(station);
  const forecast = useQuery({
    queryKey: ["forecast", station.stationId],
    queryFn: ({ signal }) => loadForecast(station.stationId, signal),
    enabled: !!forecastPath,
    staleTime: 5 * 60_000,
    retry: 1,
  });
  const prediction = forecast.data ? forecastAt(forecast.data, minutes) : null;
  return (
    <section
      className="station-detail"
      aria-label={`${station.stationName} 상세`}
    >
      <div className="detail-top">
        <span className="eyebrow">선택한 대여소</span>
        <div>
          <button
            className={`icon-button favorite-button ${saved ? "saved" : ""}`}
            onClick={onFavorite}
            aria-label={saved ? "즐겨찾기 해제" : "즐겨찾기 추가"}
            aria-pressed={saved}
          >
            <Icon name="star" />
          </button>
          <button
            className="icon-button"
            onClick={onClose}
            aria-label="대여소 상세 닫기"
          >
            <Icon name="close" />
          </button>
        </div>
      </div>
      <h2>{station.stationName}</h2>
      <p className="detail-address">{station.address || "주소 정보 없음"}</p>
      <div className={`detail-inventory ${state}`}>
        <div>
          <span>{sample ? '샘플 대여 수량 · 실제 데이터 아님' : '지금 대여 가능한 자전거'}</span>
          <strong>
            {state === "unknown" ? "—" : station.availableBikeCount}
            <small>{state === "unknown" ? "정보 없음" : "대"}</small>
          </strong>
        </div>
        <span className="inventory-bike">
          <Icon name="bike" size={38} />
        </span>
      </div>
      <div className="detail-meta">
        <span className={`availability-tag ${state}`}>
          <i />
          {AVAILABILITY_LABEL[state]}
        </span>
        <span>
          <Icon name="clock" size={13} />
          {observationLabel(station)}
        </span>
      </div>
      <div className="detail-facts">
        <div>
          <Icon name="walk" />
          <span>
            검색 위치에서
            <strong>
              {distanceLabel(station.distanceMeters)} <small>직선거리</small>
            </strong>
          </span>
        </div>
        <div>
          <Icon name="bike" />
          <span>
            거치대<strong>{station.rackTotalCount}개</strong>
          </span>
        </div>
      </div>
      <div className="forecast-panel">
        <div className="forecast-title">
          <span>
            <Icon name="spark" size={18} />
            도착할 때는 어떨까요?
          </span>
          <b>AI 예측</b>
        </div>
        <div className="horizon-tabs" role="group" aria-label="도착 예상 시간">
          {[5, 10, 15, 30, 60].map((value) => (
            <button
              key={value}
              className={minutes === value ? "active" : ""}
              onClick={() => setMinutes(value)}
              aria-pressed={minutes === value}
            >
              {value}분
            </button>
          ))}
        </div>
        {prediction ? (
          <div className="forecast-result">
            <p>
              {new Date(prediction.targetTime).toLocaleTimeString("ko-KR", {
                hour: "2-digit",
                minute: "2-digit",
              })}{" "}
              예상 수량
            </p>
            <strong>
              약 {prediction.count}
              <small>대</small>
            </strong>
            <span>
              기준{" "}
              {new Date(forecast.data!.baseTime).toLocaleTimeString("ko-KR", {
                hour: "2-digit",
                minute: "2-digit",
              })}
            </span>
          </div>
        ) : (
          <div className="forecast-unavailable">
            <Icon name="spark" size={24} />
            <b>
              {forecast.isFetching
                ? "예상 수량을 확인하고 있어요"
                : "예측 정보를 준비하고 있어요"}
            </b>
            <p>
              {minutes}분 후 예상 수량은 준비되는 대로 보여드릴게요.
              <br />
              지금은 현재 대여 수를 확인해 주세요.
            </p>
          </div>
        )}
        {prediction && (
          <p className="forecast-footnote">
            예상 수량은 도착 시 대여를 보장하지 않아요.
          </p>
        )}
      </div>
      <button className="primary-button directions-button" onClick={onRoute}
        disabled={sample || state === "unknown" || state === "empty"}>
        <Icon name="arrow" size={18} />여기서 대여해 길찾기
      </button>
    </section>
  );
}
