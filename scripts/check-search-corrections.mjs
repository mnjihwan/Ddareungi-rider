import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const {chromium} = createRequire(new URL('../.local/browser-check/package.json', import.meta.url))('playwright');
const base = process.env.SEARCH_CHECK_URL ?? 'http://localhost:5173';
const root = new URL('../', import.meta.url);
const browser = await chromium.launch({channel: 'msedge', headless: true});
const results = [];
try {
  for (const [name, viewport] of [['desktop', {width: 1600, height: 900}], ['mobile', {width: 390, height: 844}]]) {
    const context = await browser.newContext({viewport, isMobile: name === 'mobile', hasTouch: name === 'mobile'});
    const page = await context.newPage(), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base, {waitUntil: 'networkidle'});
    const input = page.locator('#main-place-search');
    const list = page.locator('.location-search-box.main .search-suggestions');
    const cases = [];
    for (const [query, label] of [['rkdskadur', '한/영 키 일치'], ['rkdska역', '한/영 키 일치'], ['ㄱㄴㅇ', '초성 일치'], ['강남얙', '유사 이름']]) {
      const response = await context.request.get(`${base}/api/v1/stations/search`, {
        params: {query, latitude: 37.4979, longitude: 127.0276},
      });
      assert.equal(response.status(), 200);
      const stations = (await response.json()).data;
      assert(stations.some(station => station.stationName.includes('강남역')), `Station API: ${query}`);
      await input.fill(query);
      await list.locator('li button').filter({hasText: '강남역'}).first().waitFor();
      assert.match(await list.innerText(), new RegExp(label));
      const popup = await list.boundingBox();
      assert(popup.x >= 0 && popup.x + popup.width <= viewport.width + 1, 'Popup stays in viewport');
      cases.push({query, label, suggestions: await list.locator('.suggestion-text b').allTextContents()});
    }
    await page.screenshot({path: fileURLToPath(new URL(`.local/search-corrections-${name}.png`, root)), fullPage: true});
    await input.press('ArrowDown');
    await input.press('Enter');
    assert.equal(await list.count(), 0, 'Choosing a corrected suggestion closes autocomplete');
    assert.match(await page.locator('.map-top-label').innerText(), /강남역/);

    await input.fill('ㄱㄴㅇ');
    await input.press('Enter');
    await page.locator('.station-card h3').filter({hasText: '강남역'}).first().waitFor();
    await page.locator('.place-results b').filter({hasText: '강남역'}).first().waitFor();
    assert.equal(await list.count(), 0, 'Submit shows corrected full search results');

    await page.getByRole('button', {name: '길찾기', exact: true}).click();
    const origin = page.getByLabel('출발지', {exact: true});
    await origin.fill('rkdska역');
    const routeList = page.locator('.location-search-box.route .search-suggestions');
    await routeList.locator('li button').filter({hasText: '강남역'}).first().waitFor();
    assert.match(await routeList.innerText(), /한\/영 키 일치/);
    const routePopup = await routeList.boundingBox();
    assert(routePopup.x >= 0 && routePopup.x + routePopup.width <= viewport.width + 1);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    assert.deepEqual(errors, []);
    results.push({name, cases, routeKeyboard: true, fullSearch: true, errors});
    await context.close();
  }
  await writeFile(new URL('.local/search-corrections-result.json', root), JSON.stringify(results, null, 2));
  console.log(JSON.stringify(results, null, 2));
} finally {
  await browser.close();
}
