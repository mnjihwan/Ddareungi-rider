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
 const stations=[{stationId:'departure',stationName:'출발 테스트 대여소',address:'서울',location,rackTotalCount:10,availableBikeCount:5,distanceMeters:50,updatedAt:new Date().toISOString()},
 {stationId:'arrival',stationName:'반납 테스트 대여소',address:'서울',location:{latitude:37.51,longitude:127.04},rackTotalCount:10,availableBikeCount:0,distanceMeters:200,updatedAt:new Date().toISOString()}];
 let payload;
 await page.route('**/api/v1/**',async route=>{
 const path=new URL(route.request().url()).pathname;
 let data=path.endsWith('/status')?(path.includes('routes')?{configured:true}:{mode:'infra'}):[];
 if(path.includes('/stations/')&&!path.endsWith('/search')) data=stations;
 if(path.endsWith('/places/search'))data=[place];
 if(path.endsWith('/routes/plan')){payload=route.request().postDataJSON();return route.fulfill({status:422,json:{message:'검증용 응답'}});}
 await route.fulfill({json:{data}});
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
 assert(await page.getByRole('button',{name:'여기서 출발',exact:true}).filter({visible:true}).isDisabled());
 await page.getByRole('button',{name:'여기로 도착',exact:true}).filter({visible:true}).click();
 await page.waitForFunction(expected=>document.querySelector('#route-목적지')?.value===expected,stations[1].stationName);
 assert.equal(await page.getByLabel('목적지',{exact:true}).inputValue(),stations[1].stationName);
 await page.getByRole('button',{name:'주변 대여소',exact:true}).click();
 await page.locator('.station-card-select').filter({hasText:'출발 테스트'}).click();
 await page.getByRole('button',{name:'여기서 출발',exact:true}).filter({visible:true}).click();
 await page.getByRole('button',{name:'따릉이 경로 찾기',exact:true}).click();
 await page.getByRole('alert').filter({hasText:'검증용 응답'}).waitFor();
 assert.equal(payload.departureStationId,'departure');assert.equal(payload.arrivalStationId,'arrival');
 assert.deepEqual(payload.destination,stations[1].location);
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
