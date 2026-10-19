import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const { chromium } = createRequire(new URL('../.local/browser-check/package.json', import.meta.url))('playwright');
await mkdir(new URL('.local/', root), { recursive: true });
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const results = [];
try {
  for (const [name, viewport] of [['desktop', {width:1600,height:900}], ['mobile', {width:390,height:844}]]) {
    const context = await browser.newContext({viewport, isMobile:name==='mobile', hasTouch:name==='mobile',
      permissions:['geolocation'], geolocation:{latitude:37.4979,longitude:127.0276}});
    const page = await context.newPage();
    const errors = [], naverRequests = [];
    page.on('pageerror', error => errors.push(error.name));
    page.on('request', request => {
      if (/naver|ntruss/.test(new URL(request.url()).hostname)) naverRequests.push(new URL(request.url()).hostname);
    });
    await page.goto('http://localhost:5173/', {waitUntil:'networkidle'});
    await page.waitForFunction(() => !document.querySelector('.map-connection') && document.querySelectorAll('.station-card').length > 0);
    assert((await page.title()).includes('따릉라이더'));
    assert.equal(await page.locator('.station-card').count(), 5);
    const tiles = await page.locator('.kakao-map img').evaluateAll(images => images.filter(image => image.complete && image.naturalWidth > 100).length);
    assert(tiles > 0, 'Actual Kakao tiles must load');
    await page.getByRole('button', {name:'지도 확대', exact:true}).click();
    await page.getByRole('button', {name:'지도 축소', exact:true}).click();
    await page.locator('.station-card-select').first().click();
    const detail = page.locator(name === 'desktop' ? '.desktop-detail' : '.mobile-selected');
    // The mobile details are rendered inside the same sheet as the station list.
    assert(await page.locator('.forecast-panel:visible').count() > 0, 'AI prediction area must remain visible');
    if(name==='desktop') {
      const panel = await detail.boundingBox(), sidebar = await page.locator('.station-sidebar').boundingBox();
      assert(panel.width >= 400 && panel.x - sidebar.x - sidebar.width <= 20);
      assert(panel.height < (await page.locator('.map-region').boundingBox()).height);
    }
    await page.getByRole('button', {name:'즐겨찾기 추가', exact:true}).filter({visible:true}).last().click();
    assert(await page.getByRole('button', {name:'즐겨찾기 해제', exact:true}).filter({visible:true}).count() > 0);
    await page.getByRole('button', {name:'대여소 상세 닫기', exact:true}).filter({visible:true}).last().click();
    await page.getByRole('button', {name:'길찾기', exact:true}).click();
    await page.getByRole('button', {name:'현재 위치를 출발지로', exact:true}).click();
    await page.waitForFunction(() => document.querySelector('#route-출발지')?.value === '현재 위치');
    await page.getByLabel('목적지', {exact:true}).fill('역삼역');
    await page.getByRole('button', {name:'목적지 검색', exact:true}).click();
    await page.locator('.route-place-results button').first().click();
    await page.evaluate(() => {
      window.__routeLines = [];
      const Base = window.kakao.maps.Polyline;
      window.kakao.maps.Polyline = class extends Base {
        constructor(options) {
          super(options);
          window.__routeLines.push({line:this, options});
        }
      };
    });
    const responsePromise = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/routes/plan', {timeout:90000});
    await page.getByRole('button', {name:'따릉이 경로 찾기', exact:true}).click();
    const response = await responsePromise;
    assert.equal(response.status(), 200, 'Actual routing service must succeed');
    const plan = (await response.json()).data;
    assert.deepEqual(plan.legs.map(leg => leg.mode), ['walk','bicycle','walk']);
    assert(plan.attribution.includes('Kakao'));
    assert(plan.legs.every(leg => leg.path.length >= 2 && leg.steps.length > 0));
    await page.locator('.route-summary').waitFor();
    await page.waitForTimeout(700);
    const displayed = await page.evaluate(() => window.__routeLines.map(({options}) => ({
      mode:options.strokeStyle, points:options.path.length,
    })));
    assert.equal(displayed.length, 3);
    assert.deepEqual(displayed.map(line => line.mode), ['shortdash','solid','shortdash']);
    assert.equal(await page.locator('.route-endpoint').count(), 4);
    const map = await page.locator('.map-surface').boundingBox();
    for (const endpoint of await page.locator('.route-endpoint').all()) {
      const box = await endpoint.boundingBox();
      assert(box.x >= map.x && box.x + box.width <= map.x + map.width + 2 &&
        box.y >= map.y && box.y + box.height <= map.y + map.height + 2, 'Route endpoint must fit in the visible map');
    }
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({path:fileURLToPath(new URL(`.local/kakao-${name}-route.png`, root)), fullPage:true});
    await page.getByLabel('목적지', {exact:true}).fill('선릉역');
    await page.waitForFunction(() => !document.querySelector('.route-endpoint') && window.__routeLines.every(({line}) => line.getMap() === null));
    assert.equal(errors.length, 0, 'No browser runtime errors');
    assert.equal(naverRequests.length, 0, 'Browser must not request the previous Naver SDK');
    results.push({name, tiles, route:{modes:plan.legs.map(leg => leg.mode), distanceMeters:plan.distanceMeters,
      durationSeconds:plan.durationSeconds, renderedLines:displayed.length, endpoints:4},
      aiVisible:true, favorites:true, clearsChangedRoute:true, horizontalOverflow:false, pageErrors:errors, naverRequests:naverRequests.length});
    await context.close();
  }
  await writeFile(new URL('.local/kakao-web-result.json', root), JSON.stringify(results, null, 2));
  console.log(JSON.stringify(results, null, 2));
} finally { await browser.close(); }
