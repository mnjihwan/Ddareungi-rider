import test from 'node:test';
import assert from 'node:assert/strict';
import {buildSearchSuggestions, matchingParts} from '../src/lib/searchSuggestions.ts';

const place = (name, categoryGroupCode = '', id = name) => ({name, categoryGroupCode, address: '서울',
  sourceUrl: `https://place.map.kakao.com/${id}`, location: {latitude: 37.5, longitude: 127}});
const station = (id) => ({stationId: String(id), stationName: `대여소 ${id}`, address: '서울', location: {latitude: 37.5, longitude: 127}});

test('subway priority uses provider categories, deduplicates places, and reserves rental station suggestions', () => {
  const subway = place('서울대입구역 2호선', 'SW8', '1');
  const items = buildSearchSuggestions([place('서울대입구역 카페', 'CE7', '2'), subway, subway,
    ...Array.from({length: 14}, (_, i) => place(`장소 ${i}`, '', String(i + 3)))], [station(1), station(1), station(2)]);
  assert.equal(items[0].name, subway.name);
  assert.equal(items.filter(item => item.name === subway.name).length, 1);
  assert.deepEqual(items.filter(item => item.kind === 'station').map(item => item.station.stationId), ['1', '2']);
  assert.equal(items.length, 10);
});

test('suggestions retain the surviving source when places or rental stations are unavailable', () => {
  assert.equal(buildSearchSuggestions([], [station(1)])[0].kind, 'station');
  assert.equal(buildSearchSuggestions([place('봉천역', 'SW8')], [])[0].name, '봉천역');
  assert.deepEqual(buildSearchSuggestions([], []), []);
});

test('address-only subway matches do not take priority over the station name being typed', () => {
  const items = buildSearchSuggestions([place('서울대입구역 2호선', 'SW8', '1'), place('봉천역 2호선', 'SW8', '2')], [], '봉천');
  assert.equal(items[0].name, '봉천역 2호선');
});

test('unrelated fuzzy provider matches are excluded from typed location suggestions', () => {
  const items = buildSearchSuggestions([place('명중수학특목대입학원', 'AC5', '1'),
    place('롯데시네마 서울대입구', 'CT1', '2'), place('서울대입구역 2호선', 'SW8', '3')], [], '서울대입');
  assert.deepEqual(items.map(item => item.name), ['서울대입구역 2호선', '롯데시네마 서울대입구']);
});

test('missing rental names cannot break suggestions and the nearest named rentals appear first', () => {
  const items = buildSearchSuggestions([place('봉천역 2호선', 'SW8')], [
    {...station(1), stationName: null, address: '봉천동', distanceMeters: 10},
    {...station(2), stationName: '봉천역 먼 출구', distanceMeters: 300},
    {...station(3), stationName: '봉천역 가까운 출구', distanceMeters: 20},
  ], '봉천');
  assert.deepEqual(items.map(item => item.name), ['봉천역 2호선', '봉천역 가까운 출구', '봉천역 먼 출구']);
});

test('matching text is split literally without interpreting regex or HTML in a query', () => {
  const name = '서울대입구역 [2호선] <출구>';
  const parts = matchingParts(name, '[2호선]');
  assert.equal(parts.map(part => part.text).join(''), name);
  assert.deepEqual(parts.filter(part => part.matched), [{text: '[2호선]', matched: true}]);
  assert.deepEqual(matchingParts(name, '  '), [{text: name, matched: false}]);
});
