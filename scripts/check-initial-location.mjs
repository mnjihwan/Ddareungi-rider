import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium}=createRequire(new URL('../.local/browser-check/package.json',import.meta.url))('playwright');
const browser=await chromium.launch({channel:'msedge',headless:true});
try {
 for(const scenario of ['success','denied','delayed','click-while-waiting']) {
  const page=await browser.newPage();
  await page.addInitScript(scenario=>{
   window.geoCalls=0;
   Object.defineProperty(navigator,'geolocation',{value:{getCurrentPosition(success,error,options){
    window.geoCalls++;window.geoOptions=options;
    window.resolveGeo=()=>success({coords:{latitude:37.55,longitude:126.95,accuracy:25}});
    if(scenario==='success')setTimeout(window.resolveGeo,50);
    if(scenario==='denied')setTimeout(()=>error({code:1}),50);
   }}});
  },scenario);
  const centers=[];
  await page.route('**/api/v1/**',async route=>{
   const url=new URL(route.request().url());
   if(url.pathname.endsWith('/stations/nearby'))centers.push(Number(url.searchParams.get('latitude')));
   const data=url.pathname.endsWith('/status')?{mode:'infra',configured:true}:[];
   await route.fulfill({json:{data}});
  });
  await page.goto('http://localhost:5173/');
  if(scenario==='success') {
   await page.waitForFunction(()=>document.querySelector('.list-heading h2')?.textContent.includes('내 위치 주변'));
   assert(centers.includes(37.55));
  } else if(scenario==='denied') {
   await page.getByRole('status').filter({hasText:'위치 접근이 차단됐어요'}).waitFor();
   assert((await page.locator('.list-heading h2').textContent()).includes('강남역'));
  } else if(scenario==='click-while-waiting') {
   await page.locator('.search-intro h1').click();
   await page.evaluate(()=>window.resolveGeo());
   await page.waitForFunction(()=>document.querySelector('.list-heading h2')?.textContent.includes('내 위치 주변'));
   assert(centers.includes(37.55));
  } else {
   await page.getByRole('button',{name:'즐겨찾기',exact:false}).first().click();
   await page.evaluate(()=>window.resolveGeo());
   await page.waitForTimeout(150);
   assert((await page.locator('.list-heading h2').textContent()).includes('저장한 대여소'));
   assert(!centers.includes(37.55));
  }
  assert.equal(await page.evaluate(()=>window.geoCalls),1);
  assert.equal(await page.evaluate(()=>window.geoOptions.enableHighAccuracy),true);
  console.log(`${scenario}: passed`);await page.close();
 }
}finally{await browser.close();}
