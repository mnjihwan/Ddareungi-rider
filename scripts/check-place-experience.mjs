import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const root=new URL('../',import.meta.url);
const {chromium}=createRequire(new URL('../.local/browser-check/package.json',import.meta.url))('playwright');
const browser=await chromium.launch({channel:'msedge',headless:true}), results=[];
try {
  for(const [name,viewport] of [['desktop',{width:1600,height:900}],['mobile',{width:390,height:844}]]) {
    const context=await browser.newContext({viewport,isMobile:name==='mobile',hasTouch:name==='mobile',permissions:['geolocation'],
      geolocation:{latitude:37.4819,longitude:126.9527,accuracy:20}});
    const page=await context.newPage(),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.goto('http://localhost:5173/',{waitUntil:'networkidle'});
    await page.waitForFunction(()=>window.kakao?.maps?.Map && !document.querySelector('.map-connection'));
    await page.evaluate(()=>{
      const setCenter=window.kakao.maps.Map.prototype.setCenter;
      window.kakao.maps.Map.prototype.setCenter=function(point){window.__focusMap=this;return setCenter.call(this,point);};
    });
    await page.getByRole('button',{name:'현재 위치에서 대여소 찾기',exact:true}).click();
    await page.getByText('내 주변역',{exact:true}).waitFor();
    await page.waitForFunction(()=>[...document.querySelectorAll('.subway-suggestions button')].some(button=>button.textContent.includes('서울대입구역')));
    const stations=await page.locator('.subway-suggestions button').allTextContents();
    assert(!stations.includes('강남역'),'Recommendations must follow the provided location');
    await page.locator('.subway-suggestions button').first().click();
    await page.locator('.search-place-pin').waitFor();
    const focus=async()=>page.evaluate(()=>({latitude:window.__focusMap.getCenter().getLat(),longitude:window.__focusMap.getCenter().getLng()}));
    const nearbyFocus=await focus();
    assert(Math.abs(nearbyFocus.latitude-37.4819)<.01 && Math.abs(nearbyFocus.longitude-126.9527)<.01);
    await page.getByRole('button',{name:'길찾기',exact:true}).click();
    await page.getByLabel('출발지',{exact:true}).fill('교대역');
    await page.getByRole('button',{name:'출발지 검색',exact:true}).click();
    await page.locator('.route-place-results button').filter({hasText:'교대역 2호선'}).first().click();
    await page.waitForFunction(()=>document.querySelector('.search-place-pin')?.textContent.includes('교대역'));
    const originFocus=await focus();
    assert(Math.abs(originFocus.longitude-127.014)<.01);
    await page.getByLabel('목적지',{exact:true}).fill('멀티캠퍼스 역삼');
    await page.getByRole('button',{name:'목적지 검색',exact:true}).click();
    await page.locator('.route-place-results button').filter({hasText:'멀티캠퍼스 역삼'}).first().click();
    await page.waitForFunction(()=>document.querySelector('.search-place-pin')?.textContent.includes('멀티캠퍼스'));
    const destinationFocus=await focus();
    assert(Math.abs(destinationFocus.longitude-127.0396)<.001);
    const history=await page.evaluate(()=>JSON.parse(localStorage.getItem('ddareungrider:recent-place-queries:v1')));
    assert.equal(history[0].query,'멀티캠퍼스 역삼');
    assert(history.every(item=>Object.keys(item).sort().join(',')==='query,usedAt'));
    await page.reload({waitUntil:'networkidle'});
    await page.getByRole('button',{name:'주변 대여소',exact:true}).click();
    assert(await page.locator('.recent-places').isVisible());
    await page.locator('.recent-places button').filter({hasText:'멀티캠퍼스 역삼'}).click();
    await page.locator('.place-result > button').filter({hasText:'멀티캠퍼스 역삼'}).first().click();
    await page.waitForFunction(()=>document.querySelector('.search-place-pin')?.textContent.includes('멀티캠퍼스'));
    await page.locator('.station-card-select').first().click();
    assert.equal(await page.getByRole('link',{name:'카카오맵에서 대여소 보기'}).count(),0);
    assert(await page.locator('.forecast-panel:visible').count()>0);
    await page.getByRole('button',{name:'대여소 상세 닫기',exact:true}).filter({visible:true}).last().click();
    await page.getByRole('button',{name:'최근 장소 지우기',exact:true}).click();
    assert.equal(await page.locator('.recent-places').count(),0);
    await context.setGeolocation({latitude:37.50128,longitude:127.0396,accuracy:50000});
    await page.getByRole('button',{name:'현재 위치에서 대여소 찾기',exact:true}).click();
    await page.getByText('지도 주변역',{exact:true}).waitFor();
    assert.equal(await page.getByText('내 주변역',{exact:true}).count(),0);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.screenshot({path:fileURLToPath(new URL(`.local/places-${name}.png`,root)),fullPage:true});
    assert.deepEqual(errors,[]);
    results.push({name,subwaySuggestions:stations,nearbyFocus,originFocus,destinationFocus,recentPlacesPersist:true,
      historyContainsNoCoordinates:true,recentPlacesClear:true,lowAccuracyUsesMapCenter:true,stationExternalLinkRemoved:true,aiVisible:true,pageErrors:errors});
    await context.close();
  }
  await writeFile(new URL('.local/place-experience-result.json',root),JSON.stringify(results,null,2));
  console.log(JSON.stringify(results,null,2));
}finally {await browser.close();}
