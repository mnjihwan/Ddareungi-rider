import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const root = new URL('../', import.meta.url);
const {chromium} = createRequire(new URL('../.local/browser-check/package.json', import.meta.url))('playwright');
const browser = await chromium.launch({channel:'msedge',headless:true});
const results=[];
try {
  for (const [name,viewport] of [['desktop',{width:1600,height:900}],['mobile',{width:390,height:844}]]) {
    const context=await browser.newContext({viewport,isMobile:name==='mobile',hasTouch:name==='mobile',permissions:['geolocation'],
      geolocation:{latitude:35.1,longitude:127.0,accuracy:50000}});
    const page=await context.newPage(), errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.goto('http://localhost:5173/',{waitUntil:'networkidle'});
    await page.locator('.station-card-select').first().click();
    await page.getByRole('button',{name:'여기서 출발',exact:true}).filter({visible:true}).click();
    await page.locator('.pinned-station').waitFor();
    await page.getByLabel('출발지',{exact:true}).fill('서울대입구역');
    assert.equal(await page.locator('.pinned-station').count(),0,'Changing origin must clear the previous station');
    await page.getByRole('button',{name:'출발지 검색',exact:true}).click();
    await page.locator('.route-place-results button').filter({hasText:'서울대입구역 2호선'}).first().click();
    await page.getByLabel('목적지',{exact:true}).fill('멀티캠퍼스 역삼');
    await page.getByRole('button',{name:'목적지 검색',exact:true}).click();
    await page.locator('.route-place-results button').filter({hasText:'멀티캠퍼스 역삼'}).first().click();
    const routeResponse=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/v1/routes/plan',{timeout:90000});
    await page.getByRole('button',{name:'따릉이 경로 찾기',exact:true}).click();
    const response=await routeResponse;
    assert.equal(response.status(),200,JSON.stringify(await response.json()));
    const plan=(await response.json()).data;
    assert.equal(response.request().postDataJSON().departureStationId,undefined);
    assert.deepEqual(plan.legs.map(leg=>leg.mode),['walk','bicycle','walk']);
    assert(plan.departureStation.distanceMeters<=500,'Automatic station must be near Seoul National University station');
    await page.locator('.route-summary').waitFor();
    await page.waitForTimeout(1000);
    await page.waitForFunction(()=>[...document.querySelectorAll('.kakao-map img')].some(image=>image.complete && image.naturalWidth>100));
    assert.equal(await page.locator('.route-endpoint').count(),4);
    await page.screenshot({path:fileURLToPath(new URL(`.local/origin-${name}-route.png`,root)),fullPage:true});
    await page.getByRole('button',{name:'현재 위치를 출발지로',exact:true}).click();
    await page.getByText(/브라우저 위치 정확도 약 50\.0km/).waitFor();
    const geoOptions=await page.evaluate(async()=>{
      const getPosition=navigator.geolocation.getCurrentPosition.bind(navigator.geolocation);
      window.__geoOptions=null;
      navigator.geolocation.getCurrentPosition=(success,error,options)=>{window.__geoOptions=options;getPosition(success,error,options);};
      return true;
    });
    assert(geoOptions);
    await page.getByRole('button',{name:'현재 위치를 출발지로',exact:true}).click();
    await page.waitForFunction(()=>window.__geoOptions?.enableHighAccuracy===true);
    const options=await page.evaluate(()=>window.__geoOptions);
    assert.equal(options.maximumAge,0);
    await page.getByRole('button',{name:'지도에서 출발지 선택',exact:true}).click();
    await page.locator('.origin-picker').waitFor();
    assert(await page.locator('.origin-crosshair').isVisible());
    await page.getByRole('button',{name:'이 위치에서 출발',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('#route-출발지')?.value==='지도에서 지정한 출발지');
    assert.equal(await page.locator('.origin-picker').count(),0);
    assert.equal(await page.getByText(/브라우저 위치 정확도/).count(),0);
    assert.equal(await page.locator('.pinned-station').count(),0);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    assert.deepEqual(errors,[]);
    results.push({name,route:{departure:plan.departureStation.stationName,stationDistanceMeters:plan.departureStation.distanceMeters,
      distanceMeters:plan.distanceMeters,durationSeconds:plan.durationSeconds},previousPinCleared:true,accuracyShown:true,mapOriginPicked:true,pageErrors:errors});
    await context.close();
  }
  await writeFile(new URL('.local/route-origin-result.json',root),JSON.stringify(results,null,2));
  console.log(JSON.stringify(results,null,2));
} finally {await browser.close();}
