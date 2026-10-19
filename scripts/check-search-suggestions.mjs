import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../',import.meta.url);
const {chromium}=createRequire(new URL('../.local/browser-check/package.json',import.meta.url))('playwright');
const browser=await chromium.launch({channel:'msedge',headless:true}),results=[];
let currentPage,currentName;
try {
  for(const [name,viewport] of [['desktop',{width:1600,height:900}],['mobile',{width:390,height:844}]]) {
    const context=await browser.newContext({viewport,isMobile:name==='mobile',hasTouch:name==='mobile',permissions:['geolocation'],
      geolocation:{latitude:37.482,longitude:126.956,accuracy:20}});
    const page=await context.newPage(),errors=[],requests=[];
    currentPage=page;currentName=name;
    page.on('pageerror',error=>errors.push(error.message));
    page.on('request',request=>{const url=new URL(request.url());if(url.pathname==='/api/v1/places/search')requests.push(url.searchParams.get('query'));});
    await page.goto('http://localhost:5173/',{waitUntil:'networkidle'});
    await page.waitForFunction(()=>window.kakao?.maps?.Map && !document.querySelector('.map-connection'));
    await page.getByRole('button',{name:'현재 위치에서 대여소 찾기',exact:true}).click();
    if(name==='mobile')await page.getByRole('button',{name:'목록 접기',exact:true}).click();
    const main=page.locator('#main-place-search');
    async function suggestions(input,query) {
      await input.fill(query);
      const list=input.locator('xpath=ancestor::div[contains(@class,"location-search-box")]').locator('.search-suggestions');
      await list.locator('li').first().waitFor();
      return list;
    }
    async function clickMap() {
      await page.waitForTimeout(300);
      const box=await page.locator('.kakao-map').boundingBox();
      let point;
      for(const x of [0.7,0.45,0.85])for(const y of [0.3,0.4,0.5,0.6]) {
        const candidate={x:box.x+box.width*x,y:box.y+box.height*y};
        if(await page.evaluate(p=>!document.elementFromPoint(p.x,p.y)?.closest('button,a,input,.native-marker-content,.station-sidebar,.search-area,.desktop-detail,.map-top-label,.map-legend'),candidate)){point=candidate;break;}
      }
      assert(point,'A map background point must be available');
      if(name==='mobile')await page.touchscreen.tap(point.x,point.y);else await page.mouse.click(point.x,point.y);
    }
    const list=await suggestions(main,'서울대입');
    const names=await list.locator('.suggestion-text b').allTextContents();
    assert.match(names[0],/서울대입구역/);
    assert.equal(await list.locator('.suggestion-kind').first().textContent(),'지하철역');
    assert(await list.locator('.suggestion-kind').filter({hasText:'대여소'}).count()>0);
    const form=await main.locator('xpath=ancestor::form').boundingBox(),popup=await list.boundingBox();
    assert(popup.y>=form.y+form.height && popup.x>=0 && popup.x+popup.width<=viewport.width,'Suggestions must sit below the search input within the viewport');
    await page.screenshot({path:fileURLToPath(new URL(`.local/suggestions-${name}.png`,root)),fullPage:true});
    await list.locator('li button').first().click();
    await page.locator('.search-place-pin').waitFor();
    await clickMap();
    await page.waitForFunction(()=>!document.querySelector('.search-place-pin'));
    const again=await suggestions(main,'서울대입');
    await again.locator('li button').first().click();await page.locator('.search-place-pin').waitFor();
    await main.fill('봉천');
    assert.equal(await page.locator('.search-place-pin').count(),0,'Editing a new query must immediately remove the previous place pin');
    await page.locator('.location-search-box.main .search-suggestions li').first().waitFor();
    await main.press('ArrowDown');await main.press('Enter');
    await page.locator('.search-place-pin').waitFor();
    assert.match(await page.locator('.search-place-pin').textContent(),/봉천역/);
    await page.getByRole('button',{name:'선택한 장소 표시 지우기',exact:true}).click();
    await page.waitForFunction(()=>!document.querySelector('.search-place-pin'));
    await main.fill('서울대입구역');await main.press('Enter');
    await page.locator('.place-results .place-result').first().waitFor();
    assert.match(await page.locator('.place-results b').first().textContent(),/서울대입구역/);
    assert.equal(await page.locator('.location-search-box.main .search-suggestions').count(),0);
    await page.locator('.place-results .place-result button').first().click();
    await page.locator('.search-place-pin').waitFor();
    await page.getByRole('button',{name:'길찾기',exact:true}).click();
    const origin=page.getByLabel('출발지',{exact:true}),destination=page.getByLabel('목적지',{exact:true});
    const originList=await suggestions(origin,'교대');
    assert.equal(await page.locator('.search-place-pin').count(),0);
    assert.equal(await originList.locator('.suggestion-kind').first().textContent(),'지하철역');
    await originList.locator('li button').first().click();
    await page.locator('.search-place-pin').waitFor();
    const destinationList=await suggestions(destination,'멀티캠퍼스 역삼');
    assert.equal(await page.locator('.search-place-pin').count(),0);
    await destinationList.locator('li button').first().click();
    await page.locator('.search-place-pin').waitFor();
    assert.equal(await page.locator('.route-place-selected').count(),2);
    assert(await page.getByRole('button',{name:'따릉이 경로 찾기',exact:true}).isEnabled());
    if(name==='mobile')await page.getByRole('button',{name:'목록 접기',exact:true}).click();
    await clickMap();
    await page.waitForFunction(()=>!document.querySelector('.search-place-pin'));
    assert.equal(await page.locator('.route-place-selected').count(),2,'Dismissing a visual pin must preserve route inputs');
    await page.getByRole('button',{name:'주변 대여소',exact:true}).click();
    if(name==='mobile')await page.getByRole('button',{name:'목록 접기',exact:true}).click();
    // A composition in progress must not send incomplete Korean input.
    await main.focus();await main.dispatchEvent('compositionstart');await main.fill('낙성대');
    const beforeComposition=requests.filter(query=>query==='낙성대').length;
    await page.waitForTimeout(550);
    assert.equal(requests.filter(query=>query==='낙성대').length,beforeComposition);
    await main.dispatchEvent('compositionend');
    await page.locator('.location-search-box.main .search-suggestions li').first().waitFor();
    assert(requests.filter(query=>query==='낙성대').length>beforeComposition);
    await main.press('Escape');
    assert.equal(await page.locator('.location-search-box.main .search-suggestions').count(),0);
    // Only the provider connection is interrupted; rental station data remains real.
    await page.route('**/api/v1/places/search?**',route=>route.fulfill({status:503,contentType:'application/json',body:'{"message":"Test provider outage"}'}));
    const fallback=await suggestions(main,'서울대입');
    assert(await fallback.locator('li').count()>0);
    assert((await fallback.locator('.suggestion-kind').allTextContents()).every(kind=>kind==='대여소'));
    await fallback.getByText('장소 연결을 확인하지 못해 대여소만 표시해요.').waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    assert.deepEqual(errors,[]);
    results.push({name,query:'서울대입',suggestions:names,subwayFirst:true,containsRealRentalStations:true,
      mapTapDismissesPin:true,newQueryDismissesPin:true,closeButton:true,keyboardSelection:true,
      submittedSearchSubwayFirst:true,routeSuggestions:true,routeInputsPreserved:true,
      koreanComposition:true,providerOutageFallback:true,noHorizontalOverflow:true,pageErrors:errors});
    await context.close();
  }
  await writeFile(new URL('.local/search-suggestion-result.json',root),JSON.stringify(results,null,2));
  console.log(JSON.stringify(results,null,2));
}catch(error){
  if(currentPage && !currentPage.isClosed()){
    await currentPage.screenshot({path:fileURLToPath(new URL(`.local/suggestions-failure-${currentName}.png`,root)),fullPage:true});
    console.log(JSON.stringify({name:currentName,state:await currentPage.evaluate(()=>({input:document.querySelector('#main-place-search')?.value,
      expanded:document.querySelector('#main-place-search')?.getAttribute('aria-expanded'),
      suggestions:document.querySelector('.location-search-box.main .search-suggestions')?.textContent,
      pin:document.querySelector('.search-place-pin')?.textContent}))}));
  }
  throw error;
}finally{await browser.close();}
