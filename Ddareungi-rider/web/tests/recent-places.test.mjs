import test from 'node:test';
import assert from 'node:assert/strict';
import {parseRecentPlaces} from '../src/lib/recentPlaceStorage.ts';

test('recent places retain only user queries and usage times, deduplicate and cap history',()=>{
  const result=parseRecentPlaces(JSON.stringify([
    {query:' 서울대입구역 ',usedAt:20,location:{latitude:37,longitude:127},address:'cached address'},
    {query:'서울대입구역',usedAt:10},
    ...Array.from({length:8},(_,i)=>({query:`위치${i}`,usedAt:i+1})),
  ]));
  assert.equal(result.length,5);
  assert.deepEqual(result[0],{query:'서울대입구역',usedAt:20});
  assert(result.every(item=>Object.keys(item).length===2));
});

test('invalid recent history cannot break recommendations',()=>{
  for(const raw of [null,'broken','{}','[null,{}, {"query":"a","usedAt":1}, {"query":"역삼역","usedAt":-1}]'])
    assert.deepEqual(parseRecentPlaces(raw),[]);
});
