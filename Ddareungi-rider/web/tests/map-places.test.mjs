import test from 'node:test';
import assert from 'node:assert/strict';
import {placeKey,selectVisiblePlaces} from '../src/lib/mapPlaces.ts';
const place=(name,latitude=37.5)=>({name,address:'서울 테스트로 10',location:{latitude,longitude:127}});
test('map places preserve real identities while hiding duplicate and overlapping labels',()=>{
 const a=place('카페'),b=place('식당',37.501),c=place('편의점',37.502);
 const result=selectVisiblePlaces([{place:a,x:100,y:100},{place:{...a},x:200,y:200},{place:b,x:110,y:105},{place:c,x:300,y:200}],400,400);
 assert.deepEqual(result,[a,c]);assert.equal(placeKey(a),placeKey({...a}));
});
test('map labels exclude missing names or addresses and clipped screen edges',()=>{
 const a=place('가게');
 assert.deepEqual(selectVisiblePlaces([{place:{...a,name:''},x:100,y:100},{place:{...a,address:''},x:200,y:200},{place:a,x:0,y:100},{place:a,x:100,y:390}],400,400),[]);
});
