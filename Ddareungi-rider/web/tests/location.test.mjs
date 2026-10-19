import test from 'node:test';
import assert from 'node:assert/strict';
import {requestLocation} from '../src/lib/location.ts';
for (const [name, accuracies, expected, calls] of [
 ['accurate first response',[20],20,1],
 ['improved retry',[1000,30],30,2],
 ['worse retry retains best fix',[1000,2000],1000,2],
 ['retry failure retains coarse fix',[1000,null],1000,2],
]) test(name,()=>{
 let count=0,result;
 requestLocation({getCurrentPosition(success,failure,options){
  assert.equal(options.enableHighAccuracy,true);assert.equal(options.maximumAge,0);
  const accuracy=accuracies[count++];
  accuracy===null?failure({code:3}):success({coords:{accuracy}});
 }},position=>{result=position.coords.accuracy;},()=>assert.fail('unexpected failure'));
 assert.equal(count,calls);assert.equal(result,expected);
});
test('permission denial is reported without retry',()=>{
 let calls=0,code;
 requestLocation({getCurrentPosition(success,failure){calls++;failure({code:1});}},()=>assert.fail(),error=>{code=error.code;});
 assert.equal(calls,1);assert.equal(code,1);
});
