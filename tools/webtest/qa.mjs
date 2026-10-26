// Headless checks of the web app (docs/app) against tools/webtest/srv.py. Every non-local request is mocked or
// blocked, so no real service is hit. Run: python3 tools/webtest/srv.py & node tools/webtest/qa.mjs
// CHROME_PATH: optional Chromium binary (else Playwright's own).
import { chromium } from 'playwright';
import fs from 'node:fs';
const B='http://127.0.0.1:8781/index.html';
const b = await chromium.launch(process.env.CHROME_PATH ? {executablePath: process.env.CHROME_PATH} : {});
const now=Date.now();
const team=(loc,nick,ab,ha,score)=>({homeAway:ha,score,team:{displayName:loc+' '+nick,shortDisplayName:nick,name:nick,location:loc,abbreviation:ab,logo:''}});
const sb={week:{number:6},events:[
 {id:'1',date:new Date(now-3600e3).toISOString(),status:{type:{state:'in',shortDetail:'Q3 5:12'}},competitions:[{competitors:[team('Dallas','Cowboys','DAL','home','17'),team('New York','Giants','NYG','away','10')],broadcasts:[{names:['ESPN']}]}]},
 {id:'2',date:new Date(now+86400e3).toISOString(),status:{type:{state:'pre',shortDetail:''}},competitions:[{competitors:[team('Kansas City','Chiefs','KC','home','0'),team('Buffalo','Bills','BUF','away','0')],broadcasts:[{names:['FS1']}]}]}]};
const fails=[]; const log=(...a)=>console.log(...a);
async function mk(vp,opts){
  const ctx=await b.newContext({viewport:vp,...(opts||{})}); const p=await ctx.newPage();
  const errs=[], blocked=new Set(), espn=[];
  p.on('pageerror',e=>errs.push(String(e).slice(0,300)));
  await ctx.route('**/*',r=>{const u=new URL(r.request().url());
    if(u.hostname==='127.0.0.1') return r.continue();
    // Settings > Check network: TMDB and the TV link (ntfy.sh) answer 200, so the "OK" path is tested.
    if(u.hostname==='api.themoviedb.org'&&u.pathname==='/3/configuration') return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{"images":{}}'});
    if(u.hostname==='ntfy.sh'&&u.pathname==='/v1/health') return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{"healthy":true}'});
    if(u.hostname==='site.api.espn.com'){espn.push(u.pathname);return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:JSON.stringify(u.pathname.includes('/nfl/')?sb:{events:[]})});}
    blocked.add(u.hostname); return r.fulfill({status:404,headers:{'Access-Control-Allow-Origin':'*'},body:''});});
  return {ctx,p,errs,blocked,espn};
}
const hsc=p=>p.evaluate(()=>({sw:document.documentElement.scrollWidth,iw:innerWidth,wide:[...document.querySelectorAll('body *')].filter(e=>{const r=e.getBoundingClientRect();return r.right>innerWidth+1&&r.width>0&&getComputedStyle(e).position!=='fixed'}).slice(0,4).map(e=>e.tagName+'.'+e.className)}));
for (const w of [390,1280]) {
  const {ctx,p,errs,blocked,espn}=await mk({width:w,height:844});
  // fresh (no setup) pass
  await p.goto(B); await p.evaluate(()=>localStorage.clear());
  for (const r of ['#/welcome','#/','#/pair','#/settings']) { await p.goto(B+r); await p.reload(); await p.waitForTimeout(900);
    const h=await hsc(p); const txt=(await p.innerText('body')).length; log(w,'fresh',r,'textlen',txt,'scrollW',h.sw, h.sw>h.iw?'OVERFLOW '+h.wide:''); if(w===390&&h.sw>h.iw) fails.push(`${w} fresh ${r} overflow ${h.sw} ${h.wide}`); }
  // configured pass
  await p.evaluate(()=>{ localStorage.setItem('mcdtv_web_profile','p1'); localStorage.setItem('mcdtv_web_init', JSON.stringify({m3u_url:'http://127.0.0.1:8781/get.php?username=u&password=p&type=m3u_plus', tmdb_key:'x'})); });
  for (const r of ['#/','#/browse','#/live','#/list','#/settings','#/pair','#/welcome','#/profiles','#/search']) {
    const before=errs.length; await p.goto(B+r); await p.reload(); await p.waitForTimeout(r==='#/live'?3500:1200);
    const h=await hsc(p); const txt=(await p.innerText('#app, main, body').catch(()=>'')).replace(/\s+/g,' ').slice(0,70);
    log(w,'cfg',r,'scrollW',h.sw,h.sw>h.iw?'OVERFLOW '+h.wide:'ok','|',txt);
    if(errs.length>before) fails.push(`${w} ${r} pageerror: ${errs.slice(before).join('|')}`);
    if(w===390&&h.sw>h.iw) fails.push(`${w} ${r} horizontal overflow ${h.sw}>${h.iw} ${h.wide}`);
  }
  // Live sections + sports chips
  await p.goto(B+'#/live'); await p.reload(); await p.waitForTimeout(3500);
  const chips=await p.$$eval('#lvChips .chip',r=>r.map(x=>x.dataset.sec+':'+x.innerText.replace(/\s+/g,' ')));
  log(w,'chips',chips.join(' | '));
  // The default view: six rows, then All Channels A-Z (closed). The old section chips are gone.
  const lhRows=await p.$$eval('.lh-row',r=>r.map(x=>x.dataset.row).join(','));
  log(w,'live rows',lhRows,'A-Z closed',await p.$eval('#lvAZ',d=>!d.open).catch(()=>'missing'));
  if(lhRows!=='myteams,games,docs,military,tech,prime') fails.push(`${w} live rows: "${lhRows}"`);
  if(await p.$('#lvChips .chip[data-sec="Sports"]')) fails.push(`${w} the old Sports chip is still shown`);
  for (const s of ['Favorites','']) { await p.click(`#lvChips .chip[data-sec="${s}"]`).catch(e=>fails.push(`${w} chip "${s}" missing`)); await p.waitForTimeout(300); log(w,'sec',s||"What's on",'rows',await p.$$eval('.lv-row,.lh-tile',r=>r.length)); }
  // Games rail
  const nfl=await p.$('#lvChips .chip[data-sec="game:NFL"]'); if(!nfl) fails.push(`${w} NFL games chip missing`); else {
    log(w,'NFL chip count',await nfl.innerText());
    await nfl.click(); await p.waitForTimeout(1200);
    const cards=await p.$$eval('.gm-card',r=>r.map(x=>x.innerText.replace(/\s+/g,' ')));
    log(w,'game cards',cards.length,cards.join(' || '), 'headers', await p.$$eval('.gm-h',r=>r.map(x=>x.innerText).join(',')), 'week', await p.$eval('.gm-week',e=>e.innerText).catch(()=>'none'));
    if(cards.length!==2) fails.push(`${w} expected 2 game cards got ${cards.length}`);
    await p.click('.gm-card .gm-main'); await p.waitForTimeout(500);
    const ch=await p.$$eval('.gm-card.open .lv-row b',r=>r.map(x=>x.innerText).join(','));
    log(w,'open game channels',ch); if(!/ESPN/.test(ch)) fails.push(`${w} live game did not list ESPN: "${ch}"`);
    const h=await hsc(p); if(w===390&&h.sw>h.iw) fails.push(`${w} games overflow ${h.sw} ${h.wide}`);
  }
  for (const L of ['NBA','NHL','EPL']) { const c=await p.$(`#lvChips .chip[data-sec="game:${L}"]`); log(w,'empty league chip',L,c?'still present':'removed'); }
  for (const L of ['WNBA','MLS','EPL']) if(await p.$(`#lvChips .chip[data-sec="game:${L}"]`)) fails.push(`${w} ${L} schedule chip shown`);
  // Favorites star toggle, on a channel in All Channels A-Z
  await p.click('#lvChips .chip[data-sec=""]'); await p.waitForTimeout(300);
  await p.click('#lvAZ summary'); await p.waitForTimeout(500);
  const fv=()=>p.evaluate(()=>{try{return JSON.parse(localStorage.mcdtv_web_plists).p1.live_favorites.length}catch{return 0}});
  const f0=await fv(); await p.click('.lv-row .lv-star'); await p.waitForTimeout(300); const f1=await fv();
  const star1=await p.$eval('.lv-row .lv-star',e=>e.innerText+'|'+e.getAttribute('aria-pressed'));
  await p.click('.lv-row .lv-star'); await p.waitForTimeout(300); const f2=await fv();
  log(w,'fav toggle',f0,f1,f2,'star after add',star1); if(!(f1===f0+1&&f2===f0)) fails.push(`${w} favorite toggle ${f0}->${f1}->${f2}`);
  // Settings > Help > Check network: runs, lists TMDB, addons, the relay and the TV link (all mocked here), shows no keys
  await p.goto(B+'#/settings/help'); await p.reload(); await p.waitForTimeout(800);
  if(!await p.$('#netBtn')) fails.push(`${w} Check network button missing`); else {
    await p.click('#netBtn'); await p.waitForFunction(()=>!document.querySelector('#netBtn').disabled,null,{timeout:15000}).catch(()=>fails.push(`${w} network check never finished`));
    const net=await p.$$eval('#netList li',r=>r.map(x=>x.innerText.replace(/\s+/g,' ')));
    log(w,'netcheck',net.join(' || '));
    if(net.length<3) fails.push(`${w} network check listed ${net.length} rows`);
    if(net.some(x=>x.includes('Checking'))) fails.push(`${w} network check left a row pending`);
    if(net.some(x=>/api_key|password|username=/.test(x))) fails.push(`${w} network check shows a key or login`);
    for (const name of ['TMDB','TV link']) { const row=net.find(x=>x.includes(name)); if(!row||!/^OK\b/.test(row)) fails.push(`${w} network check: ${name} not OK: "${row||'missing'}"`); }
  }
  // Recent errors: an uncaught error is logged without its query string, shown in Settings, and cleared
  await p.evaluate(()=>{ try{logErr('qa boom https://example.com/x?api_key=SECRET123','qa.js:1');}catch{} });
  await p.goto(B+'#/settings/help'); await p.reload(); await p.waitForTimeout(800);
  const el=await p.$eval('#errList',e=>e.innerText).catch(()=>'missing');
  log(w,'recent errors',el.replace(/\s+/g,' ').slice(0,120));
  if(!el.includes('qa boom')) fails.push(`${w} recent errors list missing the logged error`);
  if(/SECRET|api_key/.test(el)) fails.push(`${w} recent errors list shows a key`);
  await p.click('#errClear').catch(()=>fails.push(`${w} recent errors Clear missing`)); await p.waitForTimeout(200);
  if(!/No errors recorded/.test(await p.$eval('#errList',e=>e.innerText).catch(()=>''))) fails.push(`${w} recent errors not cleared`);
  // Service worker: shell cache named after BUILD
  const sw=await p.evaluate(async()=>{ try{ await navigator.serviceWorker.ready; await new Promise(r=>setTimeout(r,500)); return {keys:await caches.keys(),build:BUILD}; }catch(e){ return {err:String(e)}; } });
  log(w,'sw caches',JSON.stringify(sw));
  if(!sw.keys||!sw.keys.includes('jarvis-shell-'+sw.build)) fails.push(`${w} service worker cache not tied to BUILD: ${JSON.stringify(sw)}`);
  // Watchlist edits while the TV is off: queued, shown in the list, kept until the TV confirms
  const wq=await p.evaluate(async()=>{ const link=S.link, key=S.key; S.link=null; S.key=null; localStorage.removeItem('mcdtv_web_wlq');
    await WLQ.send({cmd:'watchlist',type:'movie',id:603,on:true,name:'QA'},'p1'); await WLQ.send({cmd:'watchlist',type:'movie',id:604,on:true},'p1'); await WLQ.send({cmd:'watchlist',type:'movie',id:604,on:false},'p1');
    const L={watchlist:[{type:'movie',id:604}]}; WLQ.overlay('p1',L); const n=WLQ.size(); localStorage.removeItem('mcdtv_web_wlq'); S.link=link; S.key=key;
    return {n,wl:L.watchlist.map(r=>r.id).join(',')}; });
  log(w,'watchlist queue',JSON.stringify(wq));
  if(wq.n!==2||wq.wl!=='603') fails.push(`${w} watchlist queue ${JSON.stringify(wq)}`);
  log(w,'pageerrors',errs.length?errs.join(' | '):'none','blocked hosts',[...blocked].join(','),'espn calls',espn.length);
  if(errs.length) fails.push(...errs.map(e=>`${w} pageerror ${e}`));
  await ctx.close();
}
// Live TV extras: Huntsville call signs in Local, guide cache kept across launches, Tap to play when autoplay is blocked.
{
  const {ctx,p,errs}=await mk({width:390,height:844});
  await ctx.addInitScript(()=>{ HTMLMediaElement.prototype.play=function(){ return Promise.reject(new DOMException('blocked','NotAllowedError')); };
    const cpt=HTMLMediaElement.prototype.canPlayType; HTMLMediaElement.prototype.canPlayType=function(t){ return /mpegurl/i.test(t)?'maybe':cpt.call(this,t); }; });
  await p.goto(B); await p.evaluate(()=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1'); localStorage.setItem('mcdtv_web_init', JSON.stringify({m3u_url:'http://127.0.0.1:8781/get.php?username=u&password=p&type=m3u_plus', tmdb_key:'x'})); });
  const secs=await p.evaluate(()=>[['USA','WHNT News 19'],['US| NEWS','WAAY 31 News'],['US| NEWS','CNN'],['US| NEWS','WHNTX News']].map(([g,n])=>LO.section(g,n)));
  log('LO huntsville',secs.join(',')); if(secs.join()!=='Local,Local,News,News') fails.push(`LO Huntsville sections ${secs}`);
  await p.goto(B+'#/live'); await p.reload(); await p.waitForTimeout(6000);
  const saved=await p.evaluate(()=>localStorage.getItem('mcdtv_web_epg')||'');
  let rows=0; try{ rows=Object.keys(JSON.parse(saved).rows).length; }catch{}
  log('epg cache rows',rows,'bytes',saved.length); if(!rows) fails.push('guide cache not saved'); if(/password|username/.test(saved)) fails.push('guide cache holds the playlist login');
  await p.reload(); await p.waitForTimeout(1500);
  const loading=await p.$$eval('.lv-row',r=>r.filter(x=>/Loading guide/.test(x.innerText)).length);
  log('rows still loading after relaunch',loading);
  await ctx.route('**/live/**',()=>{}); // the stream never answers, so only the blocked play() is tested
  await p.click('.lh-tile:not([disabled])'); await p.waitForTimeout(1500);
  const tap=await p.$eval('#lvTap',e=>!e.classList.contains('hidden')).catch(()=>false);
  const msgShown=await p.$eval('#lvMsg',e=>!e.classList.contains('hidden')).catch(()=>false);
  log('live tap to play shown',tap,'error shown',msgShown,await p.$eval('#lvMsgText',e=>e.innerText).catch(()=>'')); if(!tap||msgShown) fails.push(`live Tap to play: tap ${tap} msg ${msgShown}`);
  if(errs.length) fails.push(...errs.map(e=>`extras pageerror ${e}`));
  await ctx.close();
}
// Safe updates: a new version reloads right away when idle, and waits with "Update ready" during a source lookup.
{
  const {ctx,p,errs}=await mk({width:390,height:844},{serviceWorkers:'block'});
  await ctx.route(/127\.0\.0\.1:\d+\/index\.html\?u=/,r=>r.fulfill({status:200,headers:{'Content-Type':'text/html'},body:'<script>const BUILD = "qa-next";</script>'}));
  await p.goto(B); await p.evaluate(()=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1'); localStorage.setItem('mcdtv_web_init', JSON.stringify({tmdb_key:'x'})); });
  await p.goto(B+'#/sources/movie/603'); await p.reload(); await p.evaluate(()=>{ window.__qaMark=1; });
  const chip=await p.waitForSelector('#updChip',{timeout:8000}).catch(()=>null);
  const kept=await p.evaluate(()=>window.__qaMark===1).catch(()=>false);
  log('update during source lookup: chip',chip?await chip.innerText():'none','page kept',kept);
  if(!chip) fails.push('update prompt did not show during a source lookup'); if(!kept) fails.push('update reloaded during a source lookup');
  if(chip){ const nav=p.waitForEvent('load',{timeout:8000}).then(()=>true).catch(()=>false); await chip.click(); if(!await nav) fails.push('tapping Update ready did not reload'); }
  await p.goto(B+'#/settings'); await p.waitForTimeout(500); await p.evaluate(()=>{ window.__qaMark=1; });
  await p.waitForTimeout(4500);
  const idle=await p.evaluate(()=>window.__qaMark===1).catch(()=>false);
  log('update when idle: reloaded',!idle);
  if(idle) fails.push('update did not apply when idle');
  if(errs.length) fails.push(...errs.map(e=>`update test pageerror ${e}`));
  await ctx.close();
}
// Find my TVs: the house link from the Control page's "mcdtv_tvs", then "tv_who" on the house topic and the TVs'
// "tv_link" answers (mocked here, encrypted with a test house key) relink the phone and update "mcdtv_tvs".
{
  const rb=(n)=>{ const a=new Uint8Array(n); globalThis.crypto.getRandomValues(a); return Buffer.from(a).toString('base64url'); };
  const HID='qahouse'+Date.now().toString(36), HK=rb(32), house=HID+'.'+HK;
  const OLD={id:'oldfiretvaa22',key:rb(32)}, NEW={id:'newfiretvbb22',key:rb(32)}, GTV={id:'googletvcc22',key:rb(32)};
  const keysAll=[HK,OLD.key,NEW.key,GTV.key];
  let reply=''; const posts=[];
  const ntfyMock=async(ctx)=>ctx.route(/^https:\/\/ntfy\.sh\/mcdtv-qahouse/,r=>{ const q=r.request(), u=new URL(q.url());
    if(q.method()==='POST'){ posts.push(q.postData()||''); return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{}'}); }
    if(u.pathname.endsWith('/json')) return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/x-ndjson'},body:reply});
    return r.fulfill({status:404,headers:{'Access-Control-Allow-Origin':'*'},body:''}); });
  const seed=(p,link)=>p.evaluate(([house,OLD,link])=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1');
    localStorage.setItem('mcdtv_web_init',JSON.stringify({tmdb_key:'x'}));
    localStorage.setItem('mcdtv_tvs',JSON.stringify([{id:OLD.id,key:OLD.key,name:'Fire TV',house}]));
    if(link) localStorage.setItem('mcdtv_link',OLD.id+'.'+OLD.key); },[house,OLD,link]);
  // (a) + (b): linked to an old Fire TV link that no longer answers.
  {
    const {ctx,p,errs}=await mk({width:390,height:844}); await ntfyMock(ctx);
    await p.goto(B); await seed(p,true);
    const t=Date.now();
    reply=(await p.evaluate(async([HK,NEW,GTV,t])=>{ const k=await importKey(HK); const ev=async(o,i)=>JSON.stringify({id:'m'+i,time:Math.floor(t/1000),event:'message',topic:'x',message:await encrypt(o,k)});
      return [await ev({type:'sync',ts:t},0), await ev({type:'tv_link',from:'devfire',name:'Fire TV',link:'bad.link',ts:t-1000},1),
        await ev({type:'tv_link',from:'devfire',name:'Fire TV',link:NEW.id+'.'+NEW.key,ts:t-500},2),
        await ev({type:'tv_link',from:'devgoogle',name:'Google TV',link:GTV.id+'.'+GTV.key,ts:t},3)].join('\n')+'\n'; },[HK,NEW,GTV,t]));
    await p.reload(); await p.waitForTimeout(800);
    const a=await p.evaluate(()=>({ready:RC.ready(),house:!!(S.init&&S.init.house),saved:!!(JSON.parse(localStorage.mcdtv_web_init||'{}').house)}));
    log('find tvs (a) house from mcdtv_tvs',JSON.stringify(a));
    if(!a.ready||!a.house||!a.saved) fails.push(`find tvs (a): house link not adopted from mcdtv_tvs ${JSON.stringify(a)}`);
    const r=await p.evaluate(async()=>{ const r=await findTvs(); await new Promise(z=>setTimeout(z,300));
      return {n:r.tvs.length,link:S.link&&S.link.id,tvs:JSON.parse(localStorage.mcdtv_tvs||'[]').map(t=>t.id+':'+(t.dev||'')+':'+t.name),stored:localStorage.mcdtv_link.split('.')[0],house:!!(S.init&&S.init.house),toast:document.querySelector('#toast').innerText,errs:localStorage.mcdtv_web_errors||''}; });
    log('find tvs (b)',JSON.stringify({...r,errs:r.errs.length}));
    if(r.n!==2) fails.push(`find tvs (b): expected 2 TVs, got ${r.n}`);
    if(r.link!==NEW.id||r.stored!==NEW.id) fails.push(`find tvs (b): link not switched to the same-name TV (${r.link})`);
    if(!r.tvs.includes(NEW.id+':devfire:Fire TV')||!r.tvs.includes(GTV.id+':devgoogle:Google TV')) fails.push(`find tvs (b): mcdtv_tvs not updated ${r.tvs}`);
    if(!r.house) fails.push('find tvs (b): house link lost after relinking');
    if(!/Found your Fire TV( #\w+)?\. Linked again\./.test(r.toast)) fails.push(`find tvs (b): toast "${r.toast}"`);
    const leak=keysAll.find(k=>r.toast.includes(k)||r.errs.includes(k)); if(leak) fails.push('find tvs (b): a key shows in a toast or the error log');
    const who=await p.evaluate(async([HK,msgs])=>{ const k=await importKey(HK); const out=[]; for(const m of msgs){ const o=await decrypt(m,k); if(o) out.push(o.type+':'+typeof o.from+':'+typeof o.ts); } return out; },[HK,posts]);
    log('find tvs posts',who.join(',')); if(!who.length||!who.every(x=>x==='tv_who:string:number')) fails.push(`find tvs: tv_who post ${who}`);
    if(errs.length) fails.push(...errs.map(e=>`find tvs pageerror ${e}`));
    await ctx.close();
  }
  // (c): nobody answers: Settings > Find my TVs says so.
  {
    reply='';
    const {ctx,p,errs}=await mk({width:390,height:844}); await ntfyMock(ctx);
    await p.goto(B); await seed(p,false);
    await p.goto(B+'#/settings/tvs'); await p.reload(); await p.waitForTimeout(800);
    if(!await p.$('#findTvBtn')) fails.push('find tvs (c): Find my TVs button missing'); else {
      await p.click('#findTvBtn');
      await p.waitForFunction(()=>!document.querySelector('#findTvBtn').disabled,null,{timeout:20000}).catch(()=>fails.push('find tvs (c): never finished'));
      const txt=await p.$eval('#tvFound',e=>e.innerText.replace(/\s+/g,' ')).catch(()=>'');
      log('find tvs (c) no answer',txt);
      if(!txt.includes('No TV answered. TVs need the latest Jarvis update to answer here. Open Jarvis on a TV and try again.')) fails.push(`find tvs (c): no-TV message missing: "${txt}"`);
      const h=await hsc(p); if(h.sw>h.iw) fails.push(`find tvs (c): settings overflow ${h.sw} ${h.wide}`);
    }
    if(errs.length) fails.push(...errs.map(e=>`find tvs (c) pageerror ${e}`));
    await ctx.close();
  }
}
// TV names, now playing and the TV picker: two TVs in "mcdtv_tvs" with device ids; "tv_status" messages on the house
// topic (mocked, encrypted with the house key) show what each TV plays; the header pill opens the picker.
{
  const rb=(n)=>{ const a=new Uint8Array(n); globalThis.crypto.getRandomValues(a); return Buffer.from(a).toString('base64url'); };
  const HID='qahouse'+Date.now().toString(36), HK=rb(32), house=HID+'.'+HK;
  const LR={id:'livingroomaa22',key:rb(32)}, OF={id:'officetvbbb222',key:rb(32)};
  let reply='';
  const {ctx,p,errs}=await mk({width:390,height:844});
  await ctx.route(/^https:\/\/ntfy\.sh\/mcdtv-/,r=>{ const q=r.request(), u=new URL(q.url());
    if(q.method()==='POST') return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{}'});
    if(u.pathname.includes('-house/json')) return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/x-ndjson'},body:reply});
    return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/x-ndjson'},body:''}); });
  await p.goto(B);
  await p.evaluate(([house,LR,OF])=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1');
    localStorage.setItem('mcdtv_web_init',JSON.stringify({tmdb_key:'x',house}));
    localStorage.setItem('mcdtv_tvs',JSON.stringify([
      {id:LR.id,key:LR.key,name:'Living Room TV',label:'Living Room TV',platform:'Google TV',version:'0.2.197',dev:'devlr',house,seen:Date.now()-3600e3},
      {id:OF.id,key:OF.key,name:'Office TV',label:'Office TV',platform:'Fire TV',model:'AFTHY7ABBA',version:'0.2.197',dev:'devof',house,seen:Date.now()-7200e3}]));
    localStorage.setItem('mcdtv_link',LR.id+'.'+LR.key); },[house,LR,OF]);
  const t=Date.now();
  reply=(await p.evaluate(async([HK,t])=>{ const k=await importKey(HK); const ev=async(o,i)=>JSON.stringify({id:'s'+i,time:Math.floor(t/1000),event:'message',topic:'x',message:await encrypt(o,k)});
    return [await ev({type:'tv_status',from:'devof',name:'Office TV',now:{media:'tv',id:82728,name:'Bluey',season:1,episode:4,playing:false,position_ms:125000,duration_ms:420000,ts:t-60000},ts:t-60000},0),
      await ev({type:'tv_status',from:'devof',name:'Office TV',now:null,ts:t-90000},1),
      await ev({type:'tv_status',from:'devlr',name:'Living Room TV',now:{media:'live',id:0,name:'ESPN',playing:true,position_ms:0,duration_ms:0,ts:t-30000},ts:t-30000},2)].join('\n')+'\n'; },[HK,t]));
  await p.goto(B+'#/settings/tvs'); await p.reload(); await p.waitForTimeout(1500);
  const txt=await p.$eval('#tvFound',e=>e.innerText.replace(/\s+/g,' ')).catch(()=>'');
  log('tv status',txt);
  if(!/Living Room TV \(Google TV\)/.test(txt)||!/Office TV \(Fire TV\)/.test(txt)) fails.push(`tv status: names missing "${txt}"`);
  if(!/Playing ESPN \(Live TV\)/.test(txt)) fails.push(`tv status: linked TV's live status missing "${txt}"`);
  if(!/Paused Bluey S1 E4 · 2:05 \/ 7:00/.test(txt)) fails.push(`tv status: Office TV paused status missing or not the newest "${txt}"`);
  if(!/Play on TV goes here/.test(txt)) fails.push('tv status: default target not marked');
  const pill=await p.$eval('#tvlbl',e=>e.textContent); if(!/Living Room TV/.test(pill)) fails.push(`tv status: pill says "${pill}"`);
  await p.click('#tvpill'); await p.waitForTimeout(300);
  const pick=await p.$eval('#tvpick',e=>(e.classList.contains('hidden')?'HIDDEN ':'')+e.innerText.replace(/\s+/g,' ')).catch(()=>'');
  log('tv picker',pick);
  if(/^HIDDEN/.test(pick)||!/Office TV \(Fire TV\)/.test(pick)||!/USE THIS TV/i.test(pick)) fails.push(`tv picker: "${pick}"`);
  await p.click('#tvpick [data-act="usetv"]'); await p.waitForTimeout(600);
  const sw=await p.evaluate(()=>({link:S.link&&S.link.id,stored:localStorage.mcdtv_link.split('.')[0],hidden:document.querySelector('#tvpick').classList.contains('hidden'),toast:document.querySelector('#toast').innerText}));
  log('tv picker switch',JSON.stringify(sw));
  if(sw.link!==OF.id||sw.stored!==OF.id||!sw.hidden) fails.push(`tv picker: switch failed ${JSON.stringify(sw)}`);
  const h=await hsc(p); if(h.sw>h.iw) fails.push(`tv status: settings overflow ${h.sw}`);
  if(errs.length) fails.push(...errs.map(e=>`tv status pageerror ${e}`));
  await ctx.close();
}
// TVs with no name or the same name: the picker and Settings add the model, then the first letters of the device id,
// and off TVs show when they were last seen. The Control page (served from docs/index.html) fills Your other TVs
// from Find my TVs with the house link the web app saved, even when its own TV does not answer.
{
  const rb=(n)=>{ const a=new Uint8Array(n); globalThis.crypto.getRandomValues(a); return Buffer.from(a).toString('base64url'); };
  const HID='qahouse'+Date.now().toString(36), HK=rb(32), house=HID+'.'+HK;
  const LR={id:'livingroomaa22',key:rb(32)}, OF={id:'officetvbbb222',key:rb(32)}, W1={id:'wallstickcc222',key:rb(32)}, W2={id:'deckstickdd222',key:rb(32)};
  const tvs=[
    {id:LR.id,key:LR.key,name:'Living Room TV',label:'Living Room TV',platform:'Google TV',version:'0.2.199',dev:'devlrxx',house,seen:Date.now()-60e3},
    {id:OF.id,key:OF.key,name:'Fire TV',label:'',platform:'Fire TV',model:'AFTHY7ABBA',version:'0.2.199',dev:'devofxx',house,seen:Date.now()-2*3600e3},
    {id:W1.id,key:W1.key,name:'Fire TV',label:'',platform:'Fire TV',model:'AFTSS',version:'0.2.199',dev:'vat3xhaa',house,seen:Date.now()-3*3600e3},
    {id:W2.id,key:W2.key,name:'Fire TV',label:'',platform:'Fire TV',model:'AFTSS',version:'0.2.198',dev:'cn7cwgbb',house,seen:Date.now()-4*3600e3}];
  // (a) web app picker and Settings
  {
    const {ctx,p,errs}=await mk({width:390,height:844});
    await ctx.route(/^https:\/\/ntfy\.sh\/mcdtv-/,r=>r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/x-ndjson'},body:r.request().method()==='POST'?'{}':''}));
    await p.goto(B);
    await p.evaluate(([house,tvs,LR])=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1');
      localStorage.setItem('mcdtv_web_init',JSON.stringify({tmdb_key:'x',house})); localStorage.setItem('mcdtv_tvs',JSON.stringify(tvs));
      localStorage.setItem('mcdtv_link',LR.id+'.'+LR.key); },[house,tvs,LR]);
    await p.goto(B+'#/settings/tvs'); await p.reload(); await p.waitForTimeout(1500);
    await p.click('#tvpill'); await p.waitForTimeout(300);
    const pick=await p.$eval('#tvpick',e=>e.innerText.replace(/\s+/g,' ')).catch(()=>'');
    log('tv names picker',pick);
    for (const n of ['Living Room TV (Google TV)','Fire TV (AFTHY7ABBA)','Fire TV (AFTSS) #VAT3','Fire TV (AFTSS) #CN7C'])
      if(!pick.includes(n)) fails.push(`tv names: picker misses "${n}": "${pick}"`);
    if((pick.match(/Last seen/g)||[]).length<3) fails.push(`tv names: off TVs need last seen in the picker "${pick}"`);
    const set=await p.$eval('#tvFound',e=>e.innerText.replace(/\s+/g,' ')).catch(()=>'');
    if(!set.includes('Fire TV (AFTSS) #VAT3')||!set.includes('Fire TV (AFTHY7ABBA)')) fails.push(`tv names: Settings list "${set}"`);
    const h=await hsc(p); if(h.sw>h.iw) fails.push(`tv names: overflow ${h.sw} ${h.wide}`);
    if(errs.length) fails.push(...errs.map(e=>`tv names pageerror ${e}`));
    await ctx.close();
  }
  // (b) Control page: its TV is off; the web app's house link finds the other TVs.
  {
    const CTRL='http://127.0.0.1:8781/control.html';
    const page=fs.readFileSync(new URL('../../docs/index.html',import.meta.url),'utf8');
    const ME={id:'thispagetv2222',key:rb(32)};
    let reply=''; const posts=[];
    const {ctx,p,errs}=await mk({width:390,height:844});
    await ctx.route(CTRL,r=>r.fulfill({status:200,headers:{'Content-Type':'text/html'},body:page}));
    await ctx.route(/^https:\/\/ntfy\.sh\//,r=>{ const q=r.request(), u=new URL(q.url());
      if(q.method()==='POST'){ if(u.pathname.endsWith('-house')) posts.push(q.postData()||''); return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{}'}); }
      if(u.pathname.includes('-house/json')) return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/x-ndjson'},body:reply});
      return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'text/event-stream'},body:''}); });
    await p.goto(CTRL);
    reply=await p.evaluate(async([HK,tvs])=>{ const k=await crypto.subtle.importKey('raw',b64u.dec(HK),'AES-GCM',false,['encrypt','decrypt']); const t=Date.now();
      const out=[]; let i=0;
      for (const v of tvs) out.push(JSON.stringify({id:'l'+i,time:Math.floor(v.seen/1000),event:'message',topic:'x',message:await encrypt({type:'tv_link',from:v.dev,name:v.name,label:v.label,platform:v.platform,model:v.model||'',version:v.version,link:v.id+'.'+v.key,ts:v.seen},k)})), i++;
      return out.join('\n')+'\n'; },[HK,tvs]);
    await p.evaluate(([house,ME])=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_init',JSON.stringify({house})); localStorage.setItem('mcdtv_link',ME.id+'.'+ME.key); },[house,ME]);
    await p.goto('about:blank'); await p.goto(CTRL+'#'+ME.id+'.'+ME.key);
    await p.waitForFunction(()=>(document.querySelector('#tvList')||{}).innerText?.includes('AFTHY7ABBA'),null,{timeout:25000}).catch(()=>{});
    const txt=await p.$eval('#tvList',e=>e.innerText.replace(/\s+/g,' ')).catch(()=>'');
    log('control page other tvs',txt);
    for (const n of ['Living Room TV (Google TV)','Fire TV (AFTHY7ABBA)','Fire TV (AFTSS) #VAT3','Fire TV (AFTSS) #CN7C'])
      if(!txt.includes(n)) fails.push(`control page: Your other TVs misses "${n}": "${txt}"`);
    if(!/Last seen/.test(txt)) fails.push('control page: no last seen');
    const who=await p.evaluate(async([HK,msgs])=>{ const k=await crypto.subtle.importKey('raw',b64u.dec(HK),'AES-GCM',false,['encrypt','decrypt']); const o=[]; for(const m of msgs){ const d=await decrypt(m,k); if(d) o.push(d.type); } return o; },[HK,posts]);
    if(!who.includes('tv_who')) fails.push(`control page: no tv_who on the house topic (${who})`);
    const h=await hsc(p); if(h.sw>h.iw) fails.push(`control page: overflow ${h.sw} ${h.wide}`);
    if(errs.length) fails.push(...errs.map(e=>`control page pageerror ${e}`));
    await ctx.close();
  }
}
// Real-Debrid budget: Best source over refused sources, a 429 pause, and the page-wide limiter. The relay's /rd/
// routes, TMDB and one addon are mocked. The API token is a fake test value.
{
  const hashes=Array.from({length:10},(_,i)=>(i.toString(16)+'abcdef0123456789').repeat(3).slice(0,40));
  const streams=hashes.map((h,i)=>({name:'QA RD download 1080p',title:`Qa.Movie.1999.1080p.BluRay.x264.Q${i}\n💾 ${2+i/10} GB`,infoHash:h,fileIdx:0}));
  const J={'Access-Control-Allow-Origin':'*','Content-Type':'application/json'};
  async function rdPage(rd){
    const env=await mk({width:390,height:844}); const {ctx,p}=env; const calls=[];
    await ctx.route(/^https:\/\/api\.themoviedb\.org\/3\/movie\/603/,r=>r.fulfill({status:200,headers:J,body:JSON.stringify({id:603,title:'QA Movie',imdb_id:'tt0133093',runtime:120,release_date:'1999-03-31'})}));
    await ctx.route(/^https:\/\/addon\.qa\.test\//,r=>{ const u=new URL(r.request().url());
      return r.fulfill({status:200,headers:J,body:JSON.stringify(u.pathname.includes('/stream/')?{streams}:{id:'qa',name:'QA addon'})}); });
    await ctx.route(/^https:\/\/jarvis-rd\.netlify\.app\/rd\//,r=>{ const q=r.request();
      if(q.method()==='OPTIONS') return r.fulfill({status:204,headers:{'Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'Authorization, Content-Type','Access-Control-Allow-Methods':'GET, POST, DELETE'}});
      const path=new URL(q.url()).pathname.replace('/rd/rest/1.0',''); const c={t:Date.now(),m:q.method(),path}; calls.push(c);
      const a=rd(c,calls)||{status:200,body:'{}'};
      return r.fulfill({status:a.status,headers:{...J,'Access-Control-Expose-Headers':'Retry-After',...(a.headers||{})},body:a.body}); });
    await ctx.addInitScript(()=>{ window.__st=[]; setInterval(()=>{ const e=document.querySelector('#srcStatus'); const t=e&&e.innerText; if(t&&window.__st[window.__st.length-1]!==t) window.__st.push(t); },50); });
    await p.goto(B); await p.evaluate(()=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1');
      localStorage.setItem('mcdtv_web_init',JSON.stringify({tmdb_key:'x'}));
      localStorage.setItem('mcdtv_web_addons_mine',JSON.stringify(['https://addon.qa.test/manifest.json']));
      localStorage.setItem('mcdtv_web_rdt','QAFAKETOKEN'+'0'.repeat(29)); });
    return {...env,calls};
  }
  const best=async(p,want,gap)=>{ await p.goto(B+'#/sources/movie/603'); await p.reload();
    await p.waitForFunction(()=>{const b=document.querySelector('#bestBtn');return b&&!b.disabled},null,{timeout:15000});
    if(gap) await p.evaluate(g=>{ window.__rdAddGap=g; },gap);
    await p.click('#bestBtn');
    if(want) await p.waitForFunction((w)=>{const s=document.querySelector('#srcStatus');return s&&s.innerText.includes(w)},want,{timeout:40000}).catch(()=>{});
    return p.$eval('#srcStatus',e=>e.innerText).catch(()=>''); };
  // (1) 8 sources refused at addMagnet (HTTP 451, error 35): 1 listing + 8 addMagnet = 9 calls at most.
  // The add gap is shortened to 1500 ms here: adds must be at least that far apart (a), and only the first add
  // (no add before it, so a full gap) may mark its source refused; the later ones went out too soon (c).
  {
    const {ctx,p,errs,calls}=await rdPage((c)=>c.path==='/torrents'?{status:200,body:'[]'}
      :c.path==='/torrents/addMagnet'?{status:451,body:'{"error":"infringing_file","error_code":35}'}:null);
    const txt=await best(p,'refused the top sources',1500);
    const addT=calls.filter(c=>c.path==='/torrents/addMagnet').map(c=>c.t), adds=addT.length, lists=calls.filter(c=>c.path==='/torrents').length;
    const addGaps=addT.slice(1).map((t,i)=>t-addT[i]);
    const marked=await p.evaluate(hs=>hs.map(h=>SI.wasRefused({infoHash:h,fileIdx:0,name:'',title:'',addon:''})),hashes);
    const waitNote=await p.evaluate(()=>window.__st.includes('Trying source 2 of 8. Real-Debrid allows one new source every 25 seconds.'));
    const stored=await p.evaluate(()=>localStorage.getItem('mcdtv_web_rd_last_add'));
    log('rd refused x8:',txt,'| calls',calls.length,'addMagnet',adds,'listings',lists,'add gaps',addGaps.join(','),'marked refused',marked.filter(Boolean).length,'wait note',waitNote,'stored',!!stored);
    if(addGaps.some(g=>g<1450)) fails.push(`rd add gate: adds ${addGaps} ms apart (min 1500)`);
    if(marked.filter(Boolean).length!==1) fails.push(`rd add gate: ${marked.filter(Boolean).length} sources marked refused (expected 1, the full-gap add)`);
    if(!waitNote) fails.push('rd add gate: "Trying source 2 of 8" note not shown');
    if(!stored) fails.push('rd add gate: last add time not saved');
    if(!/Real-Debrid refused the top sources \(error 35\)/.test(txt)) fails.push(`rd refused: status "${txt}"`);
    if(adds>8) fails.push(`rd refused: ${adds} addMagnet calls (max 8)`);
    if(calls.length>9) fails.push(`rd refused: ${calls.length} RD calls (max 9)`);
    if(errs.length) fails.push(...errs.map(e=>`rd refused pageerror ${e}`));
    await ctx.close();
  }
  // (2) 429 with Retry-After: 2 on the listing: Best source stops with the busy text; the next call waits about 2 s.
  {
    let n429=0;
    const {ctx,p,errs,calls}=await rdPage((c)=>{ if(c.path==='/torrents'&&!n429++) return {status:429,headers:{'Retry-After':'2'},body:'{"error":"too_many_requests","error_code":34}'};
      if(c.path==='/user') return {status:200,body:'{"username":"qa"}'}; return null; });
    const txt=await best(p,'Real-Debrid is busy');
    const before=calls.length, t429=calls[0]&&calls[0].t;
    await p.evaluate(()=>rdCall('GET','/user')).catch(()=>{});
    const gap=calls[before]?calls[before].t-t429:-1;
    log('rd 429:',txt,'| calls before stop',before,'next call after',gap,'ms');
    if(!txt.includes('Real-Debrid is busy. Wait a moment and try again.')) fails.push(`rd 429: status "${txt}"`);
    if(before!==1) fails.push(`rd 429: Best source made ${before} calls (expected 1, then stop)`);
    if(!(gap>=1800&&gap<=3500)) fails.push(`rd 429: next call ${gap} ms after the 429 (expected about 2000)`);
    if(errs.length) fails.push(...errs.map(e=>`rd 429 pageerror ${e}`));
    await ctx.close();
  }
  // (b) A source whose torrent is already in the account is tried first, with no addMagnet.
  {
    const {ctx,p,errs,calls}=await rdPage((c)=>{
      if(c.path==='/torrents') return {status:200,body:JSON.stringify([{id:'ACC1',hash:hashes[7],status:'downloaded'}])};
      if(c.path==='/torrents/info/ACC1') return {status:200,body:JSON.stringify({id:'ACC1',status:'downloaded',files:[{id:1,path:'/Qa.Movie.1999.1080p.mp4',bytes:2e9,selected:1}],links:['https://rd.qa.test/d/1']})};
      if(c.path==='/unrestrict/link') return {status:200,body:JSON.stringify({id:'U1',download:'https://dl.qa.test/x.mp4',mimeType:'video/mp4',filename:'x.mp4',streamable:1})};
      if(c.path==='/torrents/addMagnet') return {status:451,body:'{"error":"infringing_file","error_code":35}'};
      return null; });
    await best(p,'');
    await p.waitForFunction(()=>location.hash.startsWith('#/play/'),null,{timeout:15000}).catch(()=>{});
    const h=await p.evaluate(()=>location.hash), seq=calls.map(c=>c.m+' '+c.path);
    log('rd in account first:',h,'|',seq.join(', '));
    if(seq.some(x=>x.includes('addMagnet'))) fails.push(`rd in account: addMagnet was called (${seq})`);
    if(seq[1]!=='GET /torrents/info/ACC1') fails.push(`rd in account: first try was not the owned torrent (${seq})`);
    if(!h.startsWith('#/play/')) fails.push(`rd in account: did not open the player (${h})`);
    await ctx.close();
  }
  // (3) The limiter: 6 calls at once leave at least 250 ms apart.
  {
    const {ctx,p,errs,calls}=await rdPage(()=>({status:200,body:'{"username":"qa"}'}));
    await p.goto(B+'#/settings'); await p.waitForTimeout(500);
    await p.evaluate(()=>Promise.all(Array.from({length:6},()=>rdCall('GET','/user'))));
    const gaps=calls.slice(1).map((c,i)=>c.t-calls[i].t);
    log('rd limiter gaps',gaps.join(','));
    if(calls.length!==6||gaps.some(g=>g<235)) fails.push(`rd limiter: ${calls.length} calls, gaps ${gaps}`);
    if(errs.length) fails.push(...errs.map(e=>`rd limiter pageerror ${e}`));
    await ctx.close();
  }
}
// Your IPTV provider's movies and shows: the relay's /iptv route answers player_api calls from
// tools/webtest/xtream-vod-fixture.json (real shapes). TMDB, one addon and the RD routes are mocked too.
{
  const FX=JSON.parse(fs.readFileSync(new URL('./xtream-vod-fixture.json', import.meta.url),'utf8'));
  const USER='qaIptvUserZ7', PASS='qaIptvPassQ9', M3U=`http://iptv.qa.test:8080/get.php?username=${USER}&password=${PASS}&type=m3u_plus`;
  const J={'Access-Control-Allow-Origin':'*','Content-Type':'application/json'};
  const {ctx,p,errs}=await mk({width:390,height:844}); const iv=[], rd=[];
  await ctx.route(/^https:\/\/api\.themoviedb\.org\/3\/movie\/398978/,r=>r.fulfill({status:200,headers:J,body:JSON.stringify({id:398978,title:'The Irishman',imdb_id:'tt1302006',runtime:209,release_date:'2019-11-01'})}));
  await ctx.route(/^https:\/\/api\.themoviedb\.org\/3\/tv\/57532/,r=>r.fulfill({status:200,headers:J,body:JSON.stringify({id:57532,name:'PAW Patrol',first_air_date:'2013-08-12',external_ids:{imdb_id:'tt3121722'},episode_run_time:[22],seasons:[{season_number:1,episode_count:26}]})}));
  await ctx.route(/^https:\/\/addon\.qa\.test\//,r=>{ const u=new URL(r.request().url());
    return r.fulfill({status:200,headers:J,body:JSON.stringify(u.pathname.includes('/stream/')?{streams:[{name:'QA RD 1080p',title:'Qa.Show.1080p.WEB-DL.x264\n💾 2 GB',infoHash:'ab'.repeat(20),fileIdx:0}]}:{id:'qa',name:'QA addon'})}); });
  await ctx.route(/^https:\/\/jarvis-rd\.netlify\.app\/rd\//,r=>{ rd.push(r.request().method()+' '+new URL(r.request().url()).pathname);
    return r.fulfill({status:200,headers:{...J,'Access-Control-Allow-Headers':'Authorization, Content-Type'},body:'[]'}); });
  await ctx.route(/^https:\/\/jarvis-rd\.netlify\.app\/iptv/,r=>{ let t; try{ t=new URL(new URL(r.request().url()).searchParams.get('u')); }catch{ return r.fulfill({status:400,headers:J,body:'{}'}); }
    if(!t.pathname.endsWith('/player_api.php')||!t.searchParams.get('username')) return r.fulfill({status:404,headers:J,body:'{}'});
    const a=t.searchParams.get('action')||''; iv.push(a+(t.searchParams.get('series_id')?':'+t.searchParams.get('series_id'):''));
    const d=a==='get_vod_streams'?FX.get_vod_streams:a==='get_series'?FX.get_series:a==='get_series_info'?(FX['get_series_info_'+t.searchParams.get('series_id')]||{}):a===''?{user_info:{auth:1,status:'Active'}}:[];
    return r.fulfill({status:200,headers:J,body:JSON.stringify(d)}); });
  await p.goto(B); await p.evaluate(([M3U])=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1');
    localStorage.setItem('mcdtv_web_init',JSON.stringify({tmdb_key:'x',m3u_url:M3U}));
    localStorage.setItem('mcdtv_web_addons_mine',JSON.stringify(['https://addon.qa.test/manifest.json']));
    localStorage.setItem('mcdtv_web_extplayer','vlc');
    localStorage.setItem('mcdtv_web_rdt','QAFAKETOKEN'+'0'.repeat(29)); },[M3U]);
  const rowsOf=()=>p.$$eval('#srcList .src',r=>r.map(x=>({iptv:x.classList.contains('iptv'),t:x.innerText.replace(/\s+/g,' ')})));
  const openSrc=async(h)=>{ await p.goto(B+h); await p.reload(); await p.waitForFunction(()=>document.querySelectorAll('#srcList .src').length>0,null,{timeout:15000}).catch(()=>{}); return rowsOf(); };
  // (a) The Irishman (TMDB 398978): 4 copies from the provider, listed first and labeled, the 4K copy on top.
  const mv=await openSrc('#/sources/movie/398978');
  const nIv=mv.filter(x=>x.iptv).length, firstIv=mv.slice(0,4).every(x=>x.iptv&&x.t.includes('Your IPTV provider'));
  log('iptv (a) movie rows',mv.map(x=>x.t).join(' || '),'| status',await p.$eval('#srcStatus',e=>e.innerText));
  if(nIv!==4||!firstIv) fails.push(`iptv (a): expected 4 IPTV sources first, got ${nIv} (first four labeled: ${firstIv})`);
  if(!/4K/.test(mv[0]&&mv[0].t||'')) fails.push(`iptv (a): first IPTV copy is not the 4K one: "${mv[0]&&mv[0].t}"`);
  if(!mv.some(x=>!x.iptv)) fails.push('iptv (a): the addon source is missing below the IPTV ones');
  // (c) Best source hands the top copy to the player app at once, with no Real-Debrid call.
  await p.evaluate(()=>{ window.__opened=[]; window.openApp=(l)=>{ window.__opened.push(l); }; });
  const rd0=rd.length; await p.click('#bestBtn');
  await p.waitForFunction(()=>window.__opened.length>0,null,{timeout:8000}).catch(()=>{});
  const opened=await p.evaluate(()=>window.__opened.slice());
  const box=await p.$eval('#extBox',e=>e.innerText.replace(/\s+/g,' ')).catch(()=>'');
  const dec=opened[0]?decodeURIComponent(opened[0].split('url=')[1]||''):'';
  log('iptv (c) best source opened',opened.length,dec.replace(USER,'[u]').replace(PASS,'[p]'),'| rd calls',rd.length-rd0,'| box',box.slice(0,160));
  if(opened.length!==1||!opened[0].startsWith('vlc-x-callback://')) fails.push(`iptv (c): player app not opened (${opened.length})`);
  if(!/\/movie\/[^/]+\/[^/]+\/900004\.mkv$/.test(dec)) fails.push('iptv (c): Best source did not pick the 4K copy (900004.mkv)');
  if(rd.length!==rd0) fails.push(`iptv (c): Best source made ${rd.length-rd0} Real-Debrid calls: ${rd.slice(rd0)}`);
  if(!box.includes('Your IPTV account plays one stream at a time. Stop Live TV or other playback, then try again.')) fails.push('iptv (c): one-stream note missing');
  // (b) PAW Patrol (TMDB 57532) S1 E1: the episode from get_series_info.
  const ep=await openSrc('#/sources/tv/57532?s=1&e=1');
  log('iptv (b) episode rows',ep.map(x=>x.t).join(' || '),'| calls',iv.join(','));
  if(!ep[0]||!ep[0].iptv||!/S01E01/.test(ep[0].t)) fails.push(`iptv (b): PAW Patrol S1E1 not listed first: "${ep[0]&&ep[0].t}"`);
  if(ep.filter(x=>x.iptv).length!==1) fails.push(`iptv (b): expected 1 IPTV episode, got ${ep.filter(x=>x.iptv).length}`);
  // The index is kept for 24 hours (IndexedDB): a reload does not download the library again.
  await openSrc('#/sources/movie/398978');
  const vodCalls=iv.filter(x=>x==='get_vod_streams').length;
  log('iptv library downloads',vodCalls); if(vodCalls!==1) fails.push(`iptv: library downloaded ${vodCalls} times (expected 1, then the saved index)`);
  // Settings > Help > Check network: the library size.
  await p.goto(B+'#/settings/help'); await p.reload(); await p.waitForTimeout(600);
  await p.click('#netBtn'); await p.waitForFunction(()=>!document.querySelector('#netBtn').disabled,null,{timeout:15000}).catch(()=>{});
  const nrow=(await p.$$eval('#netList li',r=>r.map(x=>x.innerText.replace(/\s+/g,' ')))).find(x=>x.includes('IPTV movies and shows'))||'';
  log('iptv netcheck',nrow); if(!/^OK\b.*8 movies, 2 shows/.test(nrow)) fails.push(`iptv netcheck row: "${nrow}"`);
  // (d) No login in the page, toasts, error log, browser storage or the saved index.
  await p.evaluate(()=>logErr('qa iptv probe','qa.js:1'));
  const leak=await p.evaluate(async([U,P])=>{ const html=document.documentElement.outerHTML+((document.querySelector('#toast')||{}).innerText||'');
    const idx=await new Promise(res=>{ try{ const q=indexedDB.open('jarvis-iptv'); q.onsuccess=()=>{ try{ const g=q.result.transaction('kv').objectStore('kv').get('index'); g.onsuccess=()=>res(JSON.stringify(g.result||null)); g.onerror=()=>res('err'); }catch(e){ res('none'); } }; q.onerror=()=>res('err'); }catch{ res('err'); } });
    const errs=localStorage.getItem('mcdtv_web_errors')||'';
    return {dom:html.includes(U)||html.includes(P),errs:errs.includes(U)||errs.includes(P),idx:idx.includes(U)||idx.includes(P),idxSaved:idx.length>100}; },[USER,PASS]);
  // Back on the Sources page too (the hand-off panel is shown there).
  await openSrc('#/sources/movie/398978'); await p.evaluate(()=>{ window.openApp=()=>{}; }); await p.click('#srcList .src.iptv'); await p.waitForTimeout(700);
  const srcDom=await p.evaluate(([U,P])=>{ const h=document.documentElement.outerHTML; return h.includes(U)||h.includes(P)||h.includes(encodeURIComponent(U)); },[USER,PASS]);
  log('iptv (d) leaks',JSON.stringify({...leak,srcDom}));
  if(leak.dom||srcDom) fails.push('iptv (d): the IPTV login shows in the page');
  if(leak.errs) fails.push('iptv (d): the IPTV login is in the error log');
  if(leak.idx) fails.push('iptv (d): the IPTV login is in the saved index');
  if(!leak.idxSaved) fails.push('iptv: the index was not saved in IndexedDB');
  if(errs.length) fails.push(...errs.map(e=>`iptv pageerror ${e}`));
  await ctx.close();
}
// Settings keeps what is set up: saved keys and Real-Debrid show as status, never as empty boxes; secret boxes open
// only after Replace; visiting every category changes no stored value and shows no secret in the page.
{
  const TOK='QATOKEN'+'x'.repeat(45), TM='QATMDBKEY1234567890abcdef', MDB='QAMDBKEY998877';
  const seedKeys={mcdtv_web_profile:'p1',mcdtv_web_tmdb:TM,mcdtv_web_mdb:MDB,mcdtv_web_rdt:TOK,
    mcdtv_web_rdc:JSON.stringify({id:'QAID',secret:'QASECRET',t:'QAACCESS',r:'QAREFRESH',exp:Date.now()+3600e3}),
    mcdtv_web_init:JSON.stringify({tmdb_key:'',rd_connected:true}),mcdtv_web_addons_mine:JSON.stringify(['https://addon.example/manifest.json']),
    mcdtv_web_extplayer:'vlc',mcdtv_web_origin:'us'};
  for (const w of [390,1280]) {
    const {ctx,p,errs}=await mk({width:w,height:844});
    await p.goto(B); await p.evaluate((k)=>{ localStorage.clear(); for(const [a,v] of Object.entries(k)) localStorage.setItem(a,v); },seedKeys);
    for (const cat of ['','tvs','profile','rd','playback','addons','keys','house','help']) {
      await p.goto(B+'#/settings'+(cat?'/'+cat:'')); await p.reload(); await p.waitForTimeout(500);
      const h=await hsc(p); if(h.sw>h.iw) fails.push(`${w} settings/${cat} overflow ${h.sw} ${h.wide}`);
      const leak=await p.evaluate((sec)=>{ const html=document.documentElement.outerHTML; return sec.filter(x=>html.includes(x)); },[TOK,TM,MDB,'QASECRET','QAREFRESH']);
      if(leak.length) fails.push(`${w} settings/${cat} shows a secret`);
      const open=await p.$$eval('input[type=password]',a=>a.filter(e=>e.checkVisibility()).map(e=>e.id));
      if(open.length) fails.push(`${w} settings/${cat}: secret box open without Replace: ${open}`);
    }
    await p.goto(B+'#/settings/rd'); await p.reload(); await p.waitForTimeout(500);
    const rd=await p.$eval('#rdPanel',e=>e.innerText.replace(/\s+/g,' '));
    log(w,'settings rd',rd.slice(0,200));
    if(!/Your TV Connected/.test(rd)||!/This device Signed in/.test(rd)||!/API token Set/.test(rd)) fails.push(`${w} settings rd status: "${rd.slice(0,160)}"`);
    if(/Paste API token/.test(await p.$eval('#rdPanel',e=>e.innerText))) fails.push(`${w} settings rd: token box text visible`);
    await p.click('#rdPanel details.replace summary'); await p.waitForTimeout(150);
    if(!await p.$eval('#rdtIn',e=>e.checkVisibility()).catch(()=>false)) fails.push(`${w} settings rd: Replace token does not open the box`);
    await p.goto(B+'#/settings/keys'); await p.reload(); await p.waitForTimeout(500);
    const ky=await p.$eval('.spane-body',e=>e.innerText.replace(/\s+/g,' '));
    if(!/Saved on this device/.test(ky)||!/Replace key/i.test(ky)) fails.push(`${w} settings keys status: "${ky.slice(0,160)}"`);
    // Quality (auto step down): the setting shows Auto by default; the live 1080p copy uses the real guide key.
    await p.goto(B+'#/settings/playback'); await p.reload(); await p.waitForTimeout(400);
    const q=await p.$eval('#qualitySel',e=>e.value+'|'+[...e.options].map(o=>o.text).join(',')).catch(()=>'missing');
    if(q!=='auto|Auto,Prefer 4K,Max 1080p') fails.push(`${w} settings playback quality: ${q}`);
    const sib=await p.evaluate(()=>{ const L=[{raw:'US| ESPN 4K',url:'a'},{raw:'US| ESPN',url:'b'},{raw:'US| ESPN 2 FHD',url:'d'},{raw:'US| ESPN FHD',url:'c'},{raw:'US| ESPN UHD',url:'e'}]; return QF.liveSibling(L,L[0],LV.gkey,()=>true); });
    if(sib!==3) fails.push(`${w} live 1080p copy of ESPN 4K: index ${sib}`);
    if(w===390){ await p.goto(B+'#/settings'); await p.reload(); await p.waitForTimeout(400);
      const rows=await p.$$eval('.slist .srow',a=>a.length); if(rows!==8) fails.push(`settings overview rows ${rows}`);
      if(await p.$('.spane-body')) fails.push('settings overview on a phone shows a category too'); }
    const after=await p.evaluate((k)=>Object.keys(k).filter(a=>localStorage.getItem(a)!==k[a]),seedKeys);
    log(w,'settings stored values changed',after.join(',')||'none');
    if(after.length) fails.push(`${w} settings changed stored values: ${after}`);
    if(errs.length) fails.push(...errs.map(e=>`${w} settings pageerror ${e}`));
    await ctx.close();
  }
}
// Source health (SH): the same cases as app/src/test/.../SourceHealthTest.kt, so the TV and the web app agree on
// which file is cut off, its key, and what gets saved when you switch devices.
{
  const {ctx,p,errs}=await mk({width:390,height:844});
  await p.goto(B); await p.evaluate(()=>localStorage.clear());
  const r=await p.evaluate(async()=>{
    const min=60000, runtime=209*min, death=(34*60+50)*1000, A='a'.repeat(40), B2='b'.repeat(40);
    const src=(title,o)=>({addon:'Torrentio',name:'Torrentio 1080p',title,url:null,infoHash:null,...o});
    const out={};
    out.keyHash=await SH.key(src('x',{infoHash:A.toUpperCase(),fileIdx:0}));
    out.keyLink=await SH.key(src('x',{url:`https://torrentio.strem.fun/realdebrid/SECRETTOKEN/${A}/null/0/The.Irishman.mkv`}));
    out.keyUrl=await SH.key(src('x',{url:'http://example.com/a.mkv'}));
    out.noQueryHash=SH.hashInLink(`https://x.io/play?h=${A}`);
    out.title=[SH.titleKey('movie',398978),SH.titleKey('tv',1399,1,2)];
    out.inc=[SH.incomplete(death,SH.expected(death,runtime)),SH.incomplete(death,SH.expected(runtime,0)),
      SH.incomplete(runtime-4*min,runtime),SH.incomplete(195*min,runtime),SH.incomplete(22*min,30*min),SH.incomplete(death,0)];
    out.dur=[SH.progressDur(death,runtime),SH.progressDur(runtime-min,runtime),SH.progressDur(death,0,runtime),SH.progressDur(death,0,0)];
    // A flag written by the TV (BadSources / SourceHealth.encode) reads here.
    out.tvFlag=SH.decode(`{"at":1,"f":[{"at":1760000000000,"s":${death},"k":"h:${A}"}]}`);
    // Web stops at the break; the flag is saved here; the TV's copy of the list puts that file last.
    await SH.mark('movie:398978','h:'+A,death);
    const fl=SH.flagged('movie:398978');
    const order=SH.demote([src('1080p',{infoHash:A}),src('720p',{infoHash:B2})],fl,(s)=>'h:'+s.infoHash);
    out.order=order.map(s=>s.title);
    out.savedDur=SH.progressDur(death,runtime); out.resume=(death<SH.expected(runtime,runtime,out.savedDur)-10000)?death:0;
    return out;
  });
  const want={keyHash:'h:'+'a'.repeat(40),keyLink:'h:'+'a'.repeat(40),keyUrl:'u:895c204c8f5b450e',noQueryHash:null,title:['movie:398978','tv:1399:1:2'],
    inc:[true,true,false,false,false,false],dur:[209*60000,208*60000,209*60000,(34*60+50)*1000],
    tvFlag:[{k:'h:'+'a'.repeat(40),s:(34*60+50)*1000,at:1760000000000,n:1}],order:['720p','1080p'],savedDur:209*60000,resume:(34*60+50)*1000};
  for (const k of Object.keys(want)) if (JSON.stringify(r[k])!==JSON.stringify(want[k])) fails.push(`source health ${k}: got ${JSON.stringify(r[k])} want ${JSON.stringify(want[k])}`);
  log('source health', fails.filter(f=>f.startsWith('source health')).length ? 'FAIL' : 'ok');
  if(errs.length) fails.push(...errs.map(e=>`source health pageerror ${e}`));
  await ctx.close();
}
// Profile photos: Settings > Profile > Profile photos turns a picked file into a 192 px JPEG kept on this device,
// sends it to the linked TV in pieces (decrypted here and checked) and shows it in every profile circle. Then the
// TV's "photos" in web_init: the same version confirms it, an old TV changes nothing, a removal elsewhere applies,
// and a photo set elsewhere is fetched in pieces. The picture is a gradient drawn here, never a real photo.
{
  const rb=(n)=>{ const a=new Uint8Array(n); globalThis.crypto.getRandomValues(a); return Buffer.from(a).toString('base64url'); };
  const LR={id:'photostvaa2222',key:rb(32)};
  const posts=[];
  const {ctx,p,errs}=await mk({width:390,height:844});
  await ctx.route(/^https:\/\/ntfy\.sh\/mcdtv-/,r=>{ const q=r.request();
    if(q.method()==='POST'){ posts.push({url:q.url(),body:q.postData()}); return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/json'},body:'{}'}); }
    return r.fulfill({status:200,headers:{'Access-Control-Allow-Origin':'*','Content-Type':'application/x-ndjson'},body:''}); });
  p.on('dialog',d=>d.accept());
  const PROFS=[{id:'p1',name:'Dad'},{id:'p2',name:'Mom'},{id:'p3',name:'Kids'}];
  await p.goto(B);
  await p.evaluate(([LR,PROFS])=>{ localStorage.clear(); localStorage.setItem('mcdtv_web_profile','p1');
    localStorage.setItem('mcdtv_web_init',JSON.stringify({tmdb_key:'x',profiles:PROFS}));
    localStorage.setItem('mcdtv_tvs',JSON.stringify([{id:LR.id,key:LR.key,name:'Living Room TV',label:'Living Room TV',platform:'Google TV',version:'0.2.212',dev:'devph',seen:Date.now()}]));
    localStorage.setItem('mcdtv_link',LR.id+'.'+LR.key); },[LR,PROFS]);
  await p.goto(B+'#/settings/profile'); await p.reload(); await p.waitForTimeout(1200);
  const decryptPosts=(m)=>p.evaluate(async([bodies,k])=>{ const key=await importKey(k); const o=[]; for(const b of bodies) o.push({len:b.length,m:await decrypt(b,key)}); return o; },[m,LR.key]);
  const toTv=()=>posts.filter(x=>x.url.includes(LR.id+'-in')).map(x=>x.body);
  const got={}, want={};
  got.vector=await p.evaluate(()=>PH.version('/9j/AAAA')); want.vector='978d81e975db561c'; // same as PhotoCodecTest
  got.choose=await p.$$eval('#photoList [data-act="photopick"]',r=>r.map(x=>x.textContent)); want.choose=['Choose photo','Choose photo','Choose photo'];
  const png=Buffer.from(await p.evaluate(()=>{ const c=document.createElement('canvas'); c.width=900; c.height=1200; const g=c.getContext('2d');
    const gr=g.createLinearGradient(0,0,900,1200); gr.addColorStop(0,'#c33'); gr.addColorStop(1,'#33c'); g.fillStyle=gr; g.fillRect(0,0,900,1200);
    for(let i=0;i<500;i++){ g.fillStyle=`hsl(${i*37%360},70%,${30+i%40}%)`; g.fillRect((i*97)%900,(i*61)%1200,30+i%50,30+i%40); }
    return c.toDataURL('image/png').split(',')[1]; }),'base64');
  posts.length=0;
  const [fc]=await Promise.all([p.waitForEvent('filechooser'),p.click('#photoList [data-act="photopick"][data-pid="p2"]')]);
  await fc.setFiles({name:'test.png',mimeType:'image/png',buffer:png});
  await p.waitForFunction(()=>{ try{ return JSON.parse(localStorage.mcdtv_web_photos).p2.d.length>0; }catch{ return false; } },null,{timeout:8000}).catch(()=>fails.push('photos: nothing saved'));
  await p.waitForTimeout(800);
  const saved=await p.evaluate(async()=>{ const r=JSON.parse(localStorage.mcdtv_web_photos).p2; const i=new Image(); i.src='data:image/jpeg;base64,'+r.d; await i.decode();
    return {v:r.v,d:r.d,small:r.d.length<=30000,jpeg:r.d.startsWith('/9j/'),size:i.naturalWidth+'x'+i.naturalHeight,vOk:(await PH.version(r.d))===r.v}; });
  got.saved={small:saved.small,jpeg:saved.jpeg,size:saved.size,vOk:saved.vOk}; want.saved={small:true,jpeg:true,size:'192x192',vOk:true};
  const pieces=(await decryptPosts(toTv())).filter(x=>x.m&&x.m.cmd==='profile_photo').sort((a,b)=>a.m.part-b.m.part);
  got.sent={many:pieces.length>=1,parts:[...new Set(pieces.map(x=>x.m.parts))].join(),reqs:new Set(pieces.map(x=>x.m.req)).size,to:[...new Set(pieces.map(x=>x.m.to))].join(),
    profile:[...new Set(pieces.map(x=>x.m.profile))].join(),v:[...new Set(pieces.map(x=>x.m.v))].join(),join:pieces.map(x=>x.m.data).join('')===saved.d,
    pieceOk:pieces.every(x=>x.m.data.length<=1600),bodyOk:pieces.every(x=>x.len<4000)};
  want.sent={many:true,parts:String(pieces.length),reqs:1,to:'devph',profile:'p2',v:saved.v,join:true,pieceOk:true,bodyOk:true};
  got.list=await p.$$eval('#photoList .pav',r=>r.map(x=>x.classList.contains('photo'))); want.list=[false,true,false];
  got.buttons=await p.$$eval('#photoList li[data-pid="p2"] button',r=>r.map(x=>x.textContent)); want.buttons=['Change','Remove'];
  const h=await hsc(p); if(h.sw>h.iw) fails.push(`photos: settings overflow ${h.sw} ${h.wide}`);
  await p.goto(B+'#/profiles'); await p.waitForTimeout(500);
  got.picker=await p.$$eval('.who .pav',r=>r.map(x=>(x.classList.contains('photo')?'photo':'letter')+':'+x.textContent.trim()+':'+(/^url\("?data:image\/jpeg;base64,\/9j\//.test(x.style.backgroundImage)?'img':'')));
  want.picker=['letter:D:','photo::img','letter:K:'];
  await p.click('.who button[data-pid="p2"]'); await p.waitForTimeout(400);
  got.pill=await p.$eval('#profav',x=>x.classList.contains('photo')+':'+x.textContent); want.pill='true:';
  // The TV's answers.
  let ts=Date.now()+1000;
  got.sync=await p.evaluate(async([v,PROFS,ts])=>{ const r={}, init=(o)=>applyInit({type:'web_init',profile:'p2',tmdb_key:'x',profiles:PROFS,watchlist:[],favorites:[],continue:[],...o});
    init({ts:ts++,photos:{p1:'',p2:v,p3:''}}); r.confirmed=JSON.parse(localStorage.mcdtv_web_photos).p2.ok===true;
    init({ts:ts++}); r.oldTvKeeps=!!PH.get('p2');
    const o=JSON.parse(localStorage.mcdtv_web_photos); o.p2.at=Date.now()-600000; localStorage.mcdtv_web_photos=JSON.stringify(o);
    init({ts:ts++,photos:{p1:'',p2:'',p3:''}}); r.removedElsewhere=!PH.get('p2');
    return r; },[saved.v,PROFS,ts]); ts+=10;
  want.sync={confirmed:true,oldTvKeeps:true,removedElsewhere:true};
  // Set on another phone: this one asks the TV, which answers in pieces (out of order here). A damaged copy is refused.
  posts.length=0;
  got.fetch=await p.evaluate(async([d,PROFS,ts])=>{ const v=await PH.version(d), n=d.match(/.{1,1600}/g);
    applyInit({type:'web_init',profile:'p2',tmdb_key:'x',profiles:PROFS,watchlist:[],favorites:[],continue:[],ts,photos:{p1:v,p2:'',p3:''}});
    await sleep(300);
    const piece=(pid,i,data)=>encrypt({type:'profile_photo',req:'r1',ts:Date.now(),profile:pid,v,part:i,parts:n.length,data},S.key);
    for (const i of n.map((_,i)=>i).reverse()) await onRelay(await piece('p3',i,i===0?n[0].slice(0,-4)+'AAAA':n[i]),false);
    for (const i of n.map((_,i)=>i).reverse()) await onRelay(await piece('p1',i,n[i]),true);
    await sleep(200);
    return {p1:(PH.get('p1')||{}).v===v,p3:!PH.get('p3')}; },[saved.d,PROFS,ts]);
  want.fetch={p1:true,p3:true};
  const asks=(await decryptPosts(toTv())).filter(x=>x.m&&x.m.cmd==='profile_photo_get').map(x=>x.m.profile+':'+(x.m.to||''));
  got.asked=asks.join(); want.asked='p1:devph';
  // Remove (asks first): gone here, and the TV is told.
  await p.goto(B+'#/settings/profile'); await p.waitForTimeout(600);
  posts.length=0;
  await p.click('#photoList [data-act="photodel"][data-pid="p1"]'); await p.waitForTimeout(600);
  const rm=(await decryptPosts(toTv())).filter(x=>x.m&&x.m.cmd==='profile_photo').map(x=>x.m.profile+':'+JSON.stringify(x.m.v));
  got.remove={gone:await p.evaluate(()=>!PH.get('p1')),told:rm.join(),circle:await p.$eval('#photoList li[data-pid="p1"] .pav',x=>x.classList.contains('photo')+':'+x.textContent)};
  want.remove={gone:true,told:'p1:""',circle:'false:D'};
  for (const k of Object.keys(want)) if (JSON.stringify(got[k])!==JSON.stringify(want[k])) fails.push(`photos ${k}: got ${JSON.stringify(got[k])} want ${JSON.stringify(want[k])}`);
  log('profile photos', fails.filter(f=>f.startsWith('photos')).length ? 'FAIL' : 'ok', JSON.stringify({pieces:pieces.length,chars:saved.d.length}));
  if(errs.length) fails.push(...errs.map(e=>`photos pageerror ${e}`));
  await ctx.close();
}
// The real failure, replayed with a real video: web dies at 34:50 on a cut-off file, resumes on a healthy one.
{ const { run } = await import('./interchange.mjs'); await run({ executablePath: process.env.CHROME_PATH, fails, log }); }
log('FAILS', fails.length); fails.forEach(f=>log(' -',f));
await b.close();
process.exit(fails.length ? 1 : 0);
