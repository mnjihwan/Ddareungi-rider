import { useEffect, useState } from 'react';
import { comparisonRows, type InventoryRecord } from '../lib/inventoryComparison';
import type { Station } from '../lib/types';
import { ComparisonTimePicker } from './ComparisonTimePicker';
import { useQuery } from '@tanstack/react-query';
import { loadInventoryHistory } from '../lib/api';

export function InventoryComparison({ station, sample = false, records }: {
  station: Station; sample?: boolean; records?: InventoryRecord[];
}) {
  const [selection, setSelection] = useState('now');
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 15_000);
    return () => window.clearInterval(timer);
  }, []);
  const baseRow = comparisonRows(station, selection, now).find(row => row.minutes === 0)!;
  const startTime = `${baseRow.cells[0].date}T${baseRow.time}:00+09:00`;
  const history = useQuery({
    queryKey: ['inventory-history', station.stationId, startTime],
    queryFn: ({ signal }) => loadInventoryHistory(station.stationId, startTime, signal),
    enabled: !sample && records === undefined,
    staleTime: 15_000,
    refetchInterval: 30_000,
  });
  const rows = comparisonRows(station, selection, now, records ?? history.data ?? [], sample);
  const comparisonWeekday = new Intl.DateTimeFormat('ko-KR', {
    weekday: 'long', timeZone: 'Asia/Seoul',
  }).format(new Date(`${baseRow.cells[2].date}T00:00:00+09:00`));
  return (
    <section className="inventory-comparison" aria-label="시간대별 자전거 수량 비교">
      <div className="comparison-heading">
        <div><h3>시간대별 수량</h3></div>
        <ComparisonTimePicker value={selection} currentTime={baseRow.time} onChange={value => {
            setSelection(value);
            setNow(Date.now());
          }} />
      </div>
      <div className="comparison-table-wrap">
        <table className="comparison-table" aria-label="오늘, 어제, 지난주 시간대별 자전거 수량">
          <thead><tr><th scope="col">시간</th><th scope="col">오늘</th><th scope="col">어제</th><th scope="col">지난주<small>{comparisonWeekday}</small></th></tr></thead>
          <tbody>{rows.map(row => <tr key={row.minutes} className={row.minutes === 0 ? 'comparison-reference' : undefined}>
            <th scope="row">{row.time}<small>
              {row.previousDay ? '이전 날 · ' : row.nextDay ? '다음 날 · ' : ''}
              {row.minutes === 0 ? selection === 'now' ? '갱신 기준' : '선택 기준' : `${row.minutes > 0 ? '+' : ''}${row.minutes}분`}
            </small></th>
            {row.cells.map((cell, index) => <td key={index} title={`${cell.date} ${row.time}`} className={cell.count === null ? 'comparison-missing' : cell.isLive ? 'comparison-live' : ''}>
              {cell.isFuture ? '-' : cell.count === null ? (!sample && !cell.isLive && records === undefined && history.isPending ? '조회 중' : !sample && !cell.isLive && history.isError ? '조회 실패' : '데이터 없음') : <><strong>{cell.count}</strong><span>대</span></>}
            </td>)}
          </tr>)}</tbody>
        </table>
      </div>
      {sample && <p className="comparison-note">샘플 모드에서는 실제 수량 비교를 제공하지 않아요.</p>}
    </section>
  );
}
