import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../',import.meta.url);
const {chromium}=createRequire(new URL('../.local/browser-check/package.json',import.meta.url))('playwright');
const browser=await chromium.launch({channel:'msedge',headless:true}),results=[];
try {
  for(const [name,viewport] of [['desktop',{width:2555,height:1214}],['mobile',{width:390,height:844}]]) {
    const context=await browser.newContext({viewport,isMobile:name==='mobile',hasTouch:name==='mobile',permissions:['geolocation'],
      geolocation:{latitude:37.482,longitude:126.956,accuracy:20}});
    const page=await context.newPage(),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.goto('http://localhost:5173/',{waitUntil:'networkidle'});
    await page.waitForFunction(()=>window.kakao?.maps?.Map && !document.querySelector('.map-connection'));
    await page.evaluate(()=>{
      const setCenter=window.kakao.maps.Map.prototype.setCenter;
      window.kakao.maps.Map.prototype.setCenter=function(point){window.__markerMap=this;return setCenter.call(this,point);};
    });
    await page.getByRole('button',{name:'현재 위치에서 대여소 찾기',exact:true}).click();
    await page.waitForFunction(()=>window.__markerMap);
    if(name==='mobile')await page.getByRole('button',{name:'목록 접기',exact:true}).click();
    await page.evaluate(()=>window.__markerMap.setLevel(4));
    await page.waitForTimeout(1200);
    await page.waitForFunction(()=>document.querySelectorAll('.station-marker').length>5 && !document.querySelector('.cluster-marker'));
    const boxes=await page.locator('.station-marker').evaluateAll(markers=>markers.map(marker=>{
      const rect=marker.getBoundingClientRect();return {id:marker.dataset.stationId,x:rect.x,y:rect.y,width:rect.width,height:rect.height};
    }));
    for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++) {
      const a=boxes[i],b=boxes[j];
      const overlaps=a.x<b.x+b.width && a.x+a.width>b.x && a.y<b.y+b.height && a.y+a.height>b.y;
      assert(!overlaps,`Individual markers must not overlap: ${a.id}/${b.id}`);
    }
    const connectors=await page.locator('.marker-connector').count();
    await page.screenshot({path:fileURLToPath(new URL(`.local/markers-${name}-close.png`,root)),fullPage:true});
    const surface=await page.locator('.map-surface').boundingBox();
    const clickable=boxes.find(box=>box.x>surface.x+100 && box.x+box.width<surface.x+surface.width-70 &&
      box.y>surface.y+150 && box.y+box.height<surface.y+surface.height-80);
    assert(clickable,'A station must be selectable on the map');
    let tracksDuringDrag=null;
    if(name==='desktop') {
      const target=page.locator(`.station-marker[data-station-id="${clickable.id}"]`);
      const before=await target.boundingBox();
      const candidates=[0.6,0.7,0.8].flatMap(x=>[0.4,0.6,0.7].map(y=>({x:surface.x+surface.width*x,y:surface.y+surface.height*y})));
      let start;
      for(const point of candidates) {
        if(await page.evaluate(point=>!document.elementFromPoint(point.x,point.y)?.closest('button,a,input'),point)){start=point;break;}
      }
      assert(start,'Map drag must start on a free point');
      await page.mouse.move(start.x,start.y);await page.mouse.down();
      await page.mouse.move(start.x+90,start.y+50,{steps:10});
      await page.waitForTimeout(100);
      const during=await target.boundingBox();
      assert(during && Math.abs(during.x-before.x-90)<8 && Math.abs(during.y-before.y-50)<8,'Native marker must follow the map during dragging');
      tracksDuringDrag=true;
      await page.mouse.up();await page.waitForTimeout(1000);
    }
    await page.locator(`.station-marker[data-station-id="${clickable.id}"]`).click();
    await page.waitForFunction(id=>document.querySelector(`.station-marker[data-station-id="${id}"]`)?.getAttribute('aria-pressed')==='true',clickable.id);
    await page.getByRole('button',{name:'대여소 상세 닫기',exact:true}).filter({visible:true}).last().click();
    if(name==='mobile')await page.getByRole('button',{name:'목록 접기',exact:true}).click();
    await page.evaluate(()=>window.__markerMap.setLevel(3));
    await page.waitForTimeout(1000);
    assert.equal(await page.locator('.cluster-marker').count(),0);
    await page.screenshot({path:fileURLToPath(new URL(`.local/markers-${name}-street.png`,root)),fullPage:true});
    await page.evaluate(()=>window.__markerMap.setLevel(5));
    await page.waitForTimeout(1200);
    await page.waitForFunction(()=>document.querySelectorAll('.cluster-marker').length>0);
    const wideClusters=await page.locator('.cluster-marker').count();
    const cluster=page.locator('.cluster-marker').filter({visible:true}).first();
    await cluster.click();
    await page.waitForTimeout(1000);
    assert(await page.evaluate(()=>window.__markerMap.getLevel())<5,'Cluster click must zoom into its stations');
    assert.equal(await page.locator('.cluster-marker').count(),0);
    assert.deepEqual(errors,[]);
    results.push({name,closeLevel:4,streetLevel:3,individualMarkers:boxes.length,closeClusters:0,overlappingLabels:0,
      connectors,wideClusters,clusterClickSeparates:true,nativeMarkerClickable:true,tracksDuringDrag,pageErrors:errors});
    await context.close();
  }
  await writeFile(new URL('.local/marker-cluster-result.json',root),JSON.stringify(results,null,2));
  console.log(JSON.stringify(results,null,2));
}finally {await browser.close();}
