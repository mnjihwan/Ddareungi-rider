import type { Station } from "../lib/types";
import {
  availability,
  AVAILABILITY_LABEL,
  distanceLabel,
  observationLabel,
} from "../lib/status";
import { Icon } from "./Icon";

export function StationCard({
  station,
  forecastMinutes = 0,
  predictedCount = null,
  selected,
  saved,
  onSelect,
  onFavorite,
}: {
  station: Station;
  forecastMinutes?: number;
  predictedCount?: number | null;
  selected: boolean;
  saved: boolean;
  onSelect: () => void;
  onFavorite: () => void;
}) {
  const state = availability(station);
  return (
    <article className={`station-card ${selected ? "selected" : ""}`}>
      <button
        className="station-card-select"
        onClick={onSelect}
        aria-label={`${station.stationName} 상세 보기`}
        aria-pressed={selected}
      >
        <div className="station-card-header">
          <span className={`availability-tag ${state}`}>
            <i />
            {AVAILABILITY_LABEL[state]}
          </span>
          <span className="station-distance">
            {distanceLabel(station.distanceMeters)}
          </span>
        </div>
        <div className="station-card-title">
          <h3>{station.stationName}</h3>
          <span className={`station-count ${state}`}>
            {state === "unknown" ? "—" : station.availableBikeCount}
            <small>대</small>
          </span>
        </div>
        {forecastMinutes > 0 && <p className="station-prediction">{forecastMinutes}분 후 예상 · {predictedCount === null ? "예측 정보 없음" : `약 ${predictedCount}대`} <small>(위 수량은 현재 기준)</small></p>}
        <p>{station.address || "주소 정보 없음"}</p>
        <div className="station-card-footer">
          <span>
            <Icon name="clock" size={12} />
            {observationLabel(station)}
          </span>
          <span>
            거치대 {station.rackTotalCount}개<Icon name="chevron" size={14} />
          </span>
        </div>
      </button>
      <button
        className={`icon-button favorite-button card-star ${saved ? "saved" : ""}`}
        onClick={onFavorite}
        aria-label={`${station.stationName} ${saved ? "즐겨찾기 해제" : "즐겨찾기 추가"}`}
        aria-pressed={saved}
      >
        <Icon name="star" size={19} />
      </button>
    </article>
  );
}
