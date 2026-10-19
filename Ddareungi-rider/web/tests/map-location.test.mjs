import test from 'node:test';
import assert from 'node:assert/strict';
import {parseMapLocation, loadMapLocation, saveMapLocation} from '../src/lib/mapLocationStorage.ts';
test('last location retains coordinates and label but no extra fields',()=>{
  assert.deepEqual(parseMapLocation(JSON.stringify({location:{latitude:37.5,longitude:127},label:'선택한 장소 주변',extra:'ignored'})),{location:{latitude:37.5,longitude:127},label:'선택한 장소 주변'});
});
test('missing, corrupt, invalid coordinates and empty labels are rejected',()=>{
  for(const value of [null,'bad','null',JSON.stringify({location:{latitude:91,longitude:127},label:'x'}),JSON.stringify({location:{latitude:'37',longitude:127},label:'x'}),JSON.stringify({location:{latitude:37,longitude:127},label:''})])assert.equal(parseMapLocation(value),null);
});
test('unavailable storage does not break map startup or selection',()=>{
  const descriptor=Object.getOwnPropertyDescriptor(globalThis,'localStorage');
  Object.defineProperty(globalThis,'localStorage',{configurable:true,get(){throw Error('Blocked');}});
  try {assert.equal(loadMapLocation(),null);assert.doesNotThrow(()=>saveMapLocation({latitude:37.5,longitude:127},'장소'));}
  finally {if(descriptor)Object.defineProperty(globalThis,'localStorage',descriptor);else delete globalThis.localStorage;}
});
