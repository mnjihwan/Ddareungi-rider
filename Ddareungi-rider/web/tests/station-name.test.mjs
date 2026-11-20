import test from 'node:test';
import assert from 'node:assert/strict';
import { displaySavedMapLabel } from '../src/lib/mapLocationStorage.ts';
import { buildSearchSuggestions } from '../src/lib/searchSuggestions.ts';
import { displayStationName } from '../src/lib/stationName.ts';

const station = {
  stationId: 'ST-2231',
  stationName: '2231. 삼성타운(삼성생명) A동 맞은편',
  stationLatitude: 37.5,
  stationLongitude: 127,
  address: '서울',
};

test('station labels hide only the leading numbered prefix with a following name', () => {
  assert.equal(displayStationName(station.stationName), '삼성타운(삼성생명) A동 맞은편');
  assert.equal(displayStationName(' 12.강남역 2번 출구'), '강남역 2번 출구');
  assert.equal(displayStationName('42. 9호선 역'), '9호선 역');
  for (const name of ['9호선 역', '강남역 2번 출구', '2231', '2231. ', '1.2']) {
    assert.equal(displayStationName(name), name);
  }
});

test('searching by number still returns the station with its original name and ID', () => {
  const [suggestion] = buildSearchSuggestions([], [station], '2231');
  assert.equal(suggestion.kind, 'station');
  assert.equal(suggestion.name, station.stationName);
  assert.equal(suggestion.station.stationId, 'ST-2231');
});

test('restored map labels hide the prefix only when the saved name and coordinates match a station', () => {
  const saved = {
    location: { latitude: station.stationLatitude, longitude: station.stationLongitude },
    label: `${station.stationName} 주변`,
  };
  assert.equal(displaySavedMapLabel(saved, [station]), '삼성타운(삼성생명) A동 맞은편 주변');
  assert.equal(displaySavedMapLabel(saved, []), saved.label);
  assert.equal(displaySavedMapLabel(saved, [{ ...station, stationLatitude: 37.51 }]), saved.label);
  const place = { ...saved, label: '2231. 삼성카페 주변' };
  assert.equal(displaySavedMapLabel(place, [station]), place.label);
});
