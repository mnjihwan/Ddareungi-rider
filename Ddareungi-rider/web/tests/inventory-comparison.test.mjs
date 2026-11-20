import test from 'node:test';
import assert from 'node:assert/strict';
import { comparisonRows, TIME_OPTIONS } from '../src/lib/inventoryComparison.ts';

const now = Date.parse('2026-10-28T14:02:00+09:00');
const station = { stationId: 'ST-1', parkingBikeTotCnt: 0, realtimeUpdatedAt: new Date(now).toISOString() };

test('live zero is a real count; missing and future values stay null', () => {
  const rows = comparisonRows(station, 'now', now);
  assert.deepEqual(rows.map(row => row.minutes), [-10, -5, 0, 5, 10, 15]);
  assert.equal(rows[2].time, '14:00');
  assert.equal(rows[2].cells[0].count, 0);
  assert.equal(rows[2].cells[0].isLive, true);
  assert.ok(rows.filter(row => row.minutes !== 0).every(row => row.cells.every(cell => cell.count === null)));
  assert.equal(rows[2].cells[1].date, '2026-10-27');
  assert.equal(rows[2].cells[2].date, '2026-10-21');
});
test('selected historical slots require matching station, exact time and valid count', () => {
  const rows = comparisonRows(station, '09:00', now, [
    {stationId: 'ST-1', observedSlot: '2026-10-27T09:00:00+09:00', availableBikes: 3},
    {stationId: 'ST-2', observedSlot: '2026-10-28T09:00:00+09:00', availableBikes: 10},
    {stationId: 'ST-1', observedSlot: '2026-10-21T09:00:00+09:00', availableBikes: -1},
  ]);
  assert.deepEqual(rows[2].cells.map(cell => cell.count), [null, 3, null]);
});
test('Korea date rollover works even when browser uses another timezone', () => {
  const late = Date.parse('2026-12-31T23:56:00+09:00');
  const rows = comparisonRows(station, '23:55', late);
  assert.equal(TIME_OPTIONS.length, 288);
  assert.equal(rows[3].time, '00:00');
  assert.equal(rows[3].nextDay, true);
  assert.deepEqual(rows[3].cells.map(cell => cell.date), ['2027-01-01', '2026-12-31', '2026-12-25']);
});
test('the two preceding slots use actual records across the Korea date boundary', () => {
  const early = Date.parse('2027-01-01T00:06:00+09:00');
  const rows = comparisonRows(station, 'now', early, [
    { stationId: 'ST-1', observedSlot: '2026-12-31T23:55:00+09:00', availableBikes: 7 },
    { stationId: 'ST-1', observedSlot: '2027-01-01T00:00:00+09:00', availableBikes: 0 },
    { stationId: 'ST-1', observedSlot: '2026-12-24T23:55:00+09:00', availableBikes: 2 },
  ]);
  assert.equal(rows[0].time, '23:55');
  assert.equal(rows[0].previousDay, true);
  assert.deepEqual(rows[0].cells.map(cell => cell.date), ['2026-12-31', '2026-12-30', '2026-12-24']);
  assert.deepEqual(rows[0].cells.map(cell => cell.count), [7, null, 2]);
  assert.equal(rows[1].time, '00:00');
  assert.equal(rows[1].previousDay, false);
  assert.equal(rows[1].cells[0].count, 0);
  assert.equal(rows[1].cells[0].isLive, false);
});
test('sample and stale live stock are never presented as current observations', () => {
  assert.equal(comparisonRows(station, 'now', now, [], true)[2].cells[0].count, null);
  assert.equal(comparisonRows(station, 'now', now + 360_000)[2].cells[0].count, null);
});
