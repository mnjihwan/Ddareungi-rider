import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium}=createRequire(new URL('../.local/browser-check/package.json',import.meta.url))('playwright');
const browser=await chromium.launch({channel:'msedge',headless:true});
try {
for(const width of [1440,390]) {
  const page=await browser.newPage({viewport:{width,height:900},isMobile:width===390,hasTouch:width===390});
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  const stations=[{stationId:'origin-station',stationName:'100. 출발 대여소',address:'서울 출발로 10',location:{latitude:37.498,longitude:127.028},rackTotalCount:10,availableBikeCount:5,distanceMeters:50,updatedAt:new Date().toISOString()},
    {stationId:'arrival-station',stationName:'200. 도착 대여소',address:'서울 도착로 20',location:{latitude:37.501,longitude:127.031},rackTotalCount:10,availableBikeCount:0,distanceMeters:200,updatedAt:new Date().toISOString()}];
  await page.addInitScript(stations=>{
    localStorage.setItem('bike:favorites:v1',JSON.stringify({schemaVersion:1,items:stations.map(s=>({stationId:s.stationId,addedAt:new Date().toISOString()}))}));
    window.geoLocation={latitude:37.4979,longitude:127.0276,accuracy:20};window.deferGeo=false;window.geoCallbacks=[];
    navigator.geolocation.getCurrentPosition=success=>{if(window.deferGeo)window.geoCallbacks.push(success);else setTimeout(()=>success({coords:window.geoLocation}),0);};
  },stations);
  let payload;
  await page.route('**/api/v1/**',async route=>{
    const url=new URL(route.request().url()),path=url.pathname;
    let data=[];
    if(path.endsWith('/status'))data=path.includes('/routes/')?{configured:true}:{mode:'infra'};
    if(path.includes('/stations/')&&!path.endsWith('/search'))data=stations;
    if(path.endsWith('/places/reverse')){const latitude=+url.searchParams.get('latitude'),longitude=+url.searchParams.get('longitude');data={name:`주소 ${latitude}, ${longitude}`,address:'서울 테스트로',location:{latitude,longitude}};}
    if(path.endsWith('/routes/plan')){payload=route.request().postDataJSON();return route.fulfill({status:422,json:{message:'검증용 응답'}});}
    await route.fulfill({json:{data}});
  });
  await page.goto(process.env.CHECK_BASE_URL || 'http://localhost:5173/');
  await page.getByRole('button',{name:'길찾기',exact:true}).click();
  const origin=page.locator('input[id="route-출발지"]'),destination=page.locator('input[id="route-목적지"]');
  await page.waitForFunction(()=>document.querySelector('#route-출발지').value.startsWith('주소 '));
  const favorites=page.locator('.route-favorite-chips');
  async function target(role){await (role==='origin'?origin:destination).click();await page.getByRole('group',{name:`${role==='origin'?'출발지':'목적지'} 지정 도구`,exact:true}).waitFor();}
  async function favorite(role,index){await target(role);await favorites.getByRole('button',{name:stations[index].stationName.replace(/^\d+\.\s*/,''),exact:true}).click();}
  async function submit(){await page.locator('.route-submit').click();await page.getByRole('alert').filter({hasText:'검증용 응답'}).waitFor();return payload;}
  await favorite('origin',0);await favorite('destination',1);
  assert.equal(await origin.inputValue(),stations[0].stationName);assert.equal(await destination.inputValue(),stations[1].stationName);
  let body=await submit();assert.equal(body.departureStationId,stations[0].stationId);assert.equal(body.arrivalStationId,stations[1].stationId);
  assert.equal(await page.getByRole('button',{name:'현재 위치',exact:true}).count(),1);assert.equal(await page.getByRole('button',{name:'지도에서 선택',exact:true}).count(),1);
  await target('destination');await page.evaluate(()=>window.geoLocation={latitude:37.502,longitude:127.033,accuracy:20});await page.getByRole('button',{name:'현재 위치',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#route-목적지').value==='주소 37.502, 127.033');
  assert.equal(await origin.inputValue(),stations[0].stationName);body=await submit();assert.equal(body.departureStationId,stations[0].stationId);assert.equal(body.arrivalStationId,undefined);assert.deepEqual(body.destination,{latitude:37.502,longitude:127.033});
  const destinationBefore=await destination.inputValue();await target('origin');await page.evaluate(()=>window.geoLocation={latitude:37.503,longitude:127.034,accuracy:20});await page.getByRole('button',{name:'현재 위치',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#route-출발지').value==='주소 37.503, 127.034');assert.equal(await destination.inputValue(),destinationBefore);
  for(const role of ['destination','origin']) {
    await target(role);const other=role==='origin'?destination:origin,otherBefore=await other.inputValue();
    await page.getByRole('button',{name:'지도에서 선택',exact:true}).click();
    await page.getByRole('button',{name:role==='origin'?'이 위치에서 출발':'이 위치로 도착',exact:true}).click();
    await page.waitForFunction(id=>document.querySelector(id).value.startsWith('주소 '),role==='origin'?'#route-출발지':'#route-목적지');
    assert.equal(await other.inputValue(),otherBefore);assert.equal(await page.locator('.origin-picker').count(),0);
  }
  await favorite('origin',0);await favorite('destination',1);await target('destination');await page.evaluate(()=>window.deferGeo=true);await page.getByRole('button',{name:'현재 위치',exact:true}).click();await page.waitForFunction(()=>window.geoCallbacks.length===1);await favorites.getByRole('button',{name:'도착 대여소',exact:true}).click();await page.evaluate(()=>{for(const success of window.geoCallbacks)success({coords:window.geoLocation});window.geoCallbacks=[];window.deferGeo=false;});await page.waitForTimeout(100);assert.equal(await destination.inputValue(),stations[1].stationName);assert.equal(await origin.inputValue(),stations[0].stationName);
  await target('destination');const beforeCancel=await destination.inputValue();await page.getByRole('button',{name:'지도에서 선택',exact:true}).click();await page.getByRole('button',{name:'취소',exact:true}).click();assert.equal(await destination.inputValue(),beforeCancel);
  await page.evaluate(()=>window.geoLocation={latitude:37.6,longitude:127.1,accuracy:1000});await page.getByRole('button',{name:'현재 위치',exact:true}).click();await page.getByRole('alert').filter({hasText:'목적지를 변경하지 않았어요'}).waitFor();assert.equal(await destination.inputValue(),stations[1].stationName);assert.equal(await origin.inputValue(),stations[0].stationName);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.screenshot({path:`.local/shared-route-tools-${width}.png`,fullPage:true});assert.deepEqual(errors,[]);console.log(`${width}px: shared favorites, zero-stock arrival, current location, map selection, station reset, request payload and stale location protection passed`);
  await page.close();
}
} finally {await browser.close();}
