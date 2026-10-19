import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium}=createRequire(new URL('../.local/browser-check/package.json',import.meta.url))('playwright');
const browser=await chromium.launch({channel:'msedge',headless:true});
try {
for(const width of [1440,390]) {
 const page=await browser.newPage({viewport:{width,height:900},permissions:['geolocation'],geolocation:{latitude:37.4979,longitude:127.0276,accuracy:20}}), errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 const stations=[{stationId:'departure',stationName:'출발 테스트 대여소',address:'서울 출발로',location:{latitude:37.498,longitude:127.027},rackTotalCount:10,availableBikeCount:5,distanceMeters:50,updatedAt:new Date().toISOString()},
 {stationId:'arrival',stationName:'도착 테스트 대여소',address:'서울 도착로',location:{latitude:37.501,longitude:127.031},rackTotalCount:10,availableBikeCount:0,distanceMeters:200,updatedAt:new Date().toISOString()},
 {stationId:'unrelated',stationName:'기타 대여소',address:'서울 다른 길',location:{latitude:37.499,longitude:127.029},rackTotalCount:10,availableBikeCount:9,distanceMeters:100,updatedAt:new Date().toISOString()}];
 await page.addInitScript(stations=>localStorage.setItem('bike:favorites:v1',JSON.stringify({schemaVersion:1,items:stations.slice(0,2).map(s=>({stationId:s.stationId,addedAt:new Date().toISOString()}))})),stations);
 let finish,body;
 await page.route('**/api/v1/**',async r=>{
  const url=new URL(r.request().url()),path=url.pathname;let data=[];
  if(path.endsWith('/status'))data=path.includes('/routes/')?{configured:true}:{mode:'infra'};
  if(path.includes('/stations/')&&!path.endsWith('/search'))data=stations;
  if(path.endsWith('/places/reverse'))data={name:'현재 위치 주소',address:'서울',location:{latitude:+url.searchParams.get('latitude'),longitude:+url.searchParams.get('longitude')}};
  if(path.endsWith('/routes/plan')) {
   body=r.request().postDataJSON();await new Promise(resolve=>finish=resolve);
   const departureStation=stations.find(s=>s.stationId===body.departureStationId)??stations[0],arrivalStation=stations.find(s=>s.stationId===body.arrivalStationId)??stations[1];
   data={origin:body.origin,destination:body.destination,departureStation,arrivalStation,
    legs:[{mode:'walk',path:[body.origin,departureStation.location],durationSeconds:60,distanceMeters:50,steps:[]},{mode:'bicycle',path:[departureStation.location,arrivalStation.location],durationSeconds:120,distanceMeters:500,steps:[]},{mode:'walk',path:[arrivalStation.location,body.destination],durationSeconds:60,distanceMeters:50,steps:[]}],distanceMeters:600,durationSeconds:240,generatedAt:new Date().toISOString(),attribution:'검증 경로'};
  }
  await r.fulfill({json:{data}});
 });
 await page.goto(process.env.CHECK_BASE_URL||'http://localhost:5173/');await page.getByRole('button',{name:'길찾기',exact:true}).click();
 async function choose(role,name){await page.locator(`input[id="route-${role}"]`).click();await page.locator('.route-favorite-chips').getByRole('button',{name,exact:true}).click();}
 async function assertStations(ids){await page.waitForFunction(ids=>JSON.stringify([...document.querySelectorAll('.station-marker[data-route-station="true"]')].map(el=>el.dataset.stationId).sort())===JSON.stringify([...ids].sort()),ids);assert.equal(await page.locator('.cluster-marker').count(),0);assert.equal(await page.locator('[data-station-id="unrelated"]').count(),0);}
 await choose('출발지',stations[0].stationName);await choose('목적지',stations[1].stationName);
 if(width===390){await page.getByRole('slider',{name:'목록 높이'}).focus();await page.keyboard.press('Home');await page.waitForTimeout(400);for(let i=0;i<2;i++){await page.getByRole('button',{name:'지도 축소',exact:true}).click();await page.waitForTimeout(350);}await page.getByRole('slider',{name:'목록 높이'}).focus();await page.keyboard.press('End');await page.waitForTimeout(400);}
 await page.locator('.route-submit').click();await assertStations(['departure','arrival']);finish();await page.locator('.route-summary').waitFor();await assertStations(['departure','arrival']);assert.equal(await page.locator('.route-endpoint').count(),4);
 if(width===390){await page.getByRole('slider',{name:'목록 높이'}).focus();await page.keyboard.press('Home');await page.waitForTimeout(400);}
 await page.locator('[data-station-id="arrival"]').click();await page.getByRole('button',{name:'대여소 상세 닫기',exact:true}).filter({visible:true}).waitFor();assert.equal(await page.locator('[data-station-id="departure"]').count(),1);await page.getByRole('button',{name:'대여소 상세 닫기',exact:true}).filter({visible:true}).click();
 await page.screenshot({path:`.local/route-station-markers-normal-${width}.png`});
 for(let i=0;i<3;i++){await page.getByRole('button',{name:'지도 축소',exact:true}).click();await page.waitForTimeout(350);}await assertStations(['departure','arrival']);
 await page.screenshot({path:`.local/route-station-markers-${width}.png`});
 if(width===390){await page.getByRole('slider',{name:'목록 높이'}).focus();await page.keyboard.press('End');await page.waitForTimeout(400);}
 await choose('목적지',stations[0].stationName);await page.locator('.route-submit').click();finish();await page.locator('.route-summary').waitFor();await page.waitForTimeout(300);assert.equal(await page.locator('.station-marker[data-route-station="true"]').count(),1);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);console.log(`${width}px: selected markers survive pending and completed routes, unrelated markers hidden, details clickable, zoom clustering disabled and same-station duplicates removed`);await page.close();
}
} finally {await browser.close();}
