import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {searchScore, searchMatchLabel, bestSearchScore} from '../src/lib/koreanSearch.ts';
import {buildSearchSuggestions} from '../src/lib/searchSuggestions.ts';

test('frontend and backend share Korean matching examples including negative cases', () => {
  const fixture = readFileSync(new URL('../../backend/src/test/resources/korean-search.tsv', import.meta.url), 'utf8');
  for (const line of fixture.trim().split(/\r?\n/)) {
    const [name, query, score] = line.split('\t');
    assert.equal(searchScore(name, query), Number(score), line);
  }
});

const place = name => ({name, address: '서울', categoryGroupCode: 'SW8', location: {latitude: 37.5, longitude: 127}});
const station = name => ({stationId: name, stationName: name, address: '서울', distanceMeters: 1});

test('corrected results survive autocomplete filtering and explain why they matched', () => {
  for (const [query, label] of [['rkdska역', '한/영 키 일치'], ['ㄱㄴㅇ', '초성 일치'], ['강남얙', '유사 이름']]) {
    const results = buildSearchSuggestions([place('강남역')], [station('강남역 2번 출구')], query);
    assert.deepEqual(results.map(result => result.kind), ['place', 'station']);
    assert.equal(searchMatchLabel(results[0].name, results[0].address, query), label);
  }
});

test('exact names win and unrelated results or speculative typos do not crowd suggestions', () => {
  const results = buildSearchSuggestions([place('강남얙'), place('강남역'), place('명중수학특목대입학원')], [], '강남역');
  assert.deepEqual(results.map(result => result.name), ['강남역']);
  assert.deepEqual(buildSearchSuggestions([place('명중수학특목대입학원')], [], '서울대입'), []);
});

test('multiword queries match across name and address without losing or adding words', () => {
  const fixture = readFileSync(new URL('../../backend/src/test/resources/korean-search-fields.tsv', import.meta.url), 'utf8');
  for (const line of fixture.trim().split(/\r?\n/)) {
    const [name, address, query, expected] = line.split('\t');
    assert.equal(bestSearchScore(name, address, query), Number(expected), line);
    const candidate = {...place(name), address};
    assert.equal(buildSearchSuggestions([candidate], [], query).length, Number(expected) >= 0 ? 1 : 0, line);
  }
});
