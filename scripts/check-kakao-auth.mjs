import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const settings = Object.fromEntries((await readFile(new URL('.env', root), 'utf8'))
  .split(/\r?\n/).flatMap(line => {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/);
    return match ? [[match[1], match[2].trim().replace(/^(['"])(.*)\1$/, '$2')]] : [];
  }));
const restKey = settings.KAKAO_MAPS_REST_API_KEY;
const jsKey = settings.KAKAO_MAPS_JAVASCRIPT_KEY;
function safeMessage(message) {
  let value = String(message ?? '');
  for (const secret of Object.values(settings)) {
    if (secret.length > 6) value = value.replaceAll(secret, '[redacted]');
  }
  return value.replace(/[0-9a-f]{32}/gi, '[redacted]').slice(0, 300);
}

const results = await Promise.all([
  ['places', '/v2/local/search/keyword.json', { query: '서울역', size: '1' }],
  ['walk', '/v2/routing/walk', { start_x: '126.9779', start_y: '37.5663', end_x: '126.981', end_y: '37.5702' }],
  ['bicycle', '/v2/routing/bicycle', { start_x: '126.9779', start_y: '37.5663', end_x: '126.981', end_y: '37.5702' }],
].map(async ([name, path, params]) => {
  if (!restKey) return { name, ok: false, message: 'REST API key missing' };
  try {
    const response = await fetch(`https://dapi.kakao.com${path}?${new URLSearchParams(params)}`, {
      headers: { Authorization: `KakaoAK ${restKey}` }, signal: AbortSignal.timeout(20000),
    });
    const body = await response.json();
    if (!response.ok) return { name, ok: false, httpStatus: response.status, message: safeMessage(body.message ?? body.msg) };
    if (name === 'places') return { name, ok: Array.isArray(body.documents) && body.documents.length > 0, count: body.documents?.length };
    return { name, ok: body.status === 'OK', status: body.status,
      distanceMeters: body.route?.properties?.totalDistance, durationSeconds: body.route?.properties?.totalTime };
  } catch {
    return { name, ok: false, message: 'Network request failed or timed out' };
  }
}));

const require = createRequire(new URL('../.local/browser-check/package.json', import.meta.url));
let browser;
try {
  const { chromium } = require('playwright');
  browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
  await page.goto('http://localhost:5173/', { waitUntil: 'networkidle', timeout: 20000 });
  results.push({ name: 'branding', ok: (await page.title()).includes('따릉라이더') &&
    (await page.locator('.brand').innerText()).includes('ddareungrider'), title: await page.title() });
  await page.screenshot({ path: fileURLToPath(new URL('.local/ddareungrider-brand.png', root)), fullPage: true });
  await page.route('**/__kakao_auth_probe__', route => route.fulfill({
    contentType: 'text/html', body: '<!doctype html><html><body><div id="map" style="width:800px;height:600px"></div></body></html>',
  }));
  await page.goto('http://localhost:5173/__kakao_auth_probe__');
  let successfulTiles = 0;
  page.on('response', response => {
    if (response.ok() && response.request().resourceType() === 'image' &&
      /(?:daumcdn|kakao)/.test(new URL(response.url()).hostname)) successfulTiles++;
  });
  const loaded = jsKey && await page.evaluate(key => new Promise(resolve => {
    const timeout = setTimeout(() => resolve(false), 15000);
    const script = document.createElement('script');
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?autoload=false&appkey=${encodeURIComponent(key)}`;
    script.onerror = () => { clearTimeout(timeout); resolve(false); };
    script.onload = () => {
      if (!window.kakao?.maps?.load) { clearTimeout(timeout); resolve(false); return; }
      window.kakao.maps.load(() => {
        try {
          new window.kakao.maps.Map(document.getElementById('map'), {
            center: new window.kakao.maps.LatLng(37.5663, 126.9779), level: 3,
          });
          clearTimeout(timeout); resolve(true);
        } catch { clearTimeout(timeout); resolve(false); }
      });
    };
    document.head.appendChild(script);
  }), jsKey);
  if (loaded) await page.waitForFunction(() => [...document.images].some(image => image.complete && image.naturalWidth > 0), null, { timeout: 10000 }).catch(() => {});
  results.push({ name: 'web-map', ok: !!loaded && successfulTiles > 0, sdkLoaded: !!loaded, successfulTiles });
} catch {
  results.push({ name: 'web-map', ok: false, message: 'Browser authentication check unavailable or timed out' });
} finally { await browser?.close(); }

await mkdir(new URL('.local/', root), { recursive: true });
await writeFile(new URL('.local/kakao-auth-result.json', root), JSON.stringify({ checkedAt: new Date().toISOString(), results }, null, 2));
console.log(JSON.stringify(results, null, 2));
if (results.some(result => !result.ok)) process.exitCode = 1;
