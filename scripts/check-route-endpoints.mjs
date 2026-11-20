import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium}=createRequire(new URL('../.local/browser-check/package.json',import.meta.url))('playwright');
const browser=await chromium.launch({channel:'msedge',headless:true});
try {
for(const width of [1440,390]) {
 const page=await browser.newPage({viewport:{width,height:900}}), errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 const location={latitude:37.4979,longitude:127.0276};
 const place={name:'테스트 카페',address:'서울 강남구',location};
 const returnLocation={latitude:37.51,longitude:127.04};
 const stations=[{stationId:'departure',stationName:'출발 테스트 대여소',address:'서울',stationLatitude:location.latitude,stationLongitude:location.longitude,rackTotCnt:10,parkingBikeTotCnt:5,distanceMeters:50,realtimeStatus:'FRESH',realtimeUpdatedAt:new Date().toISOString()},
 {stationId:'arrival',stationName:'반납 테스트 대여소',address:'서울',stationLatitude:returnLocation.latitude,stationLongitude:returnLocation.longitude,rackTotCnt:10,parkingBikeTotCnt:0,distanceMeters:200,realtimeStatus:'FRESH',realtimeUpdatedAt:new Date().toISOString()}];
 let payload;
 await page.route('**/api/**',async route=>{
 const path=new URL(route.request().url()).pathname;
 let data=path.endsWith('/status')?(path.includes('routes')?{configured:true}:{mode:'infra'}):[];
 if(path==='/api/client-config') data={maintenance:false};
 if(path.endsWith('/stations/nearby')) data={items:stations,center:location,radiusMeters:500};
 else if(path.endsWith('/stations/viewport')||path.endsWith('/stations/batch')) data=stations;
 if(path.endsWith('/places/search'))data=[place];
 if(path.endsWith('/routes/search')){payload=route.request().postDataJSON();return route.fulfill({status:422,json:{success:false,error:{code:'TEST_ROUTE_FAILURE',message:'검증용 응답'}}});}
 await route.fulfill({json:{success:true,data,meta:{requestId:'test'},timestamp:new Date().toISOString()}});
 });
 await page.goto(process.env.CHECK_BASE_URL || 'http://localhost:5173/');
 async function choosePlace(){await page.locator('.location-search-box.main input').fill('테스트 카페');await page.getByRole('option').filter({hasText:'테스트 카페'}).click();}
 await choosePlace();
 await page.getByRole('button',{name:'출발지로',exact:true}).click();
 assert.equal(await page.getByLabel('출발지',{exact:true}).inputValue(),place.name);
 await page.getByRole('button',{name:'주변 대여소',exact:true}).click();
 await choosePlace();await page.getByRole('button',{name:'도착지로',exact:true}).click();
 assert.equal(await page.getByLabel('목적지',{exact:true}).inputValue(),place.name);
 assert.equal(await page.getByLabel('출발지',{exact:true}).inputValue(),place.name);
 await page.getByRole('button',{name:'주변 대여소',exact:true}).click();
 await page.locator('.station-card-select').filter({hasText:'반납 테스트'}).click();
 assert(await page.getByRole('button',{name:'출발지',exact:true}).filter({visible:true}).isDisabled());
 await page.getByRole('button',{name:'도착지',exact:true}).filter({visible:true}).click();
 await page.waitForFunction(expected=>document.querySelector('#route-목적지')?.value===expected,stations[1].stationName);
 assert.equal(await page.getByLabel('목적지',{exact:true}).inputValue(),stations[1].stationName);
 await page.getByRole('button',{name:'주변 대여소',exact:true}).click();
 await page.locator('.station-card-select').filter({hasText:'출발 테스트'}).click();
 await page.getByRole('button',{name:'출발지',exact:true}).filter({visible:true}).click();
 await page.getByRole('button',{name:'따릉이 경로 찾기',exact:true}).click();
 await page.getByRole('alert').filter({hasText:'검증용 응답'}).waitFor();
 assert.equal(payload.departureStationId,'departure');assert.equal(payload.arrivalStationId,'arrival');
 assert.deepEqual(payload.destination,returnLocation);
 await page.screenshot({path:`.local/endpoints-${width}.png`,fullPage:true});
 await page.getByLabel('목적지',{exact:true}).fill('테스트 카페');
 assert.equal(await page.locator('.pinned-station').count(),1);
 await page.getByRole('option').filter({hasText:'테스트 카페'}).click();
 await page.getByRole('button',{name:'따릉이 경로 찾기',exact:true}).click();
 await page.getByRole('alert').filter({hasText:'검증용 응답'}).waitFor();
 assert.equal(payload.arrivalStationId,undefined);assert.equal(payload.departureStationId,'departure');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 assert.deepEqual(errors,[]); console.log(`${width}px: place endpoints, pinned stations, zero stock returns, clearing destination and request payload passed`);
 await page.close();
}
}finally{await browser.close();}
