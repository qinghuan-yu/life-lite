'use strict';
// 页面不保存登录凭据。所有服务请求由原生层完成，网络异常后只查询订单。
const icons={drop:'<path d="M12 3C10 7 5 11 5 15a7 7 0 0 0 14 0c0-4-5-8-7-12Z"/><path d="M9 15a3 3 0 0 0 3 3"/>',bath:'<path d="m5 5 3-2a4 4 0 0 1 5 1l2 3M10 12l6-6 4 4-6 6Z"/><path d="m8 13-1 1m4 2-1 1m4 2-1 1m-8-3-1 1m4 2-1 1"/>',scan:'<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5M4 12h16"/>',home:'<path d="m3 10 9-7 9 7v10H3Z"/><path d="M9 20v-7h6v7"/>',device:'<rect x="5" y="3" width="14" height="18" rx="3"/><path d="M9 7h6m-3 5v4m-3 0h6"/>',user:'<circle cx="12" cy="7" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',arrow:'<path d="m9 5 7 7-7 7"/>',back:'<path d="m14 5-7 7 7 7M7 12h14"/>',check:'<path d="m5 12 4 4L19 6"/>',refresh:'<path d="M20 7v5h-5M4 17v-5h5M19 11a7 7 0 0 0-12-6M5 13a7 7 0 0 0 12 6"/>',lock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',plus:'<path d="M12 5v14M5 12h14"/>'};
const icon=n=>`<svg viewBox="0 0 24 24" aria-hidden="true">${icons[n]||icons.drop}</svg>`;
const escape=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const el=id=>document.getElementById(id);
let state={water:{loggedIn:false,devices:[]},bath:{loggedIn:false,devices:[]}};
let provider='water',view='home',busy=false,error='',prepared=null,captcha='',counter=0,toastTimer,lastSync=0;
let loginValues={phone:'',password:'',captcha:'',code:''};
let waterLoginMode='alipay';
const deviceValues={water:'',bath:''},paymentNames={21:'支付宝免密',91:'慧生活钱包'};
let paymentType=21,lastPage='',sheetFocus=null,sheetClosing=false,pointerMotion=true,billPrepared=null;
const reduceMotion=()=>matchMedia('(prefers-reduced-motion: reduce)').matches||!pointerMotion;
const pending=new Map();
const names={water:'慧生活798',bath:'住理生活'},verbs={water:'接水',bath:'洗澡'};
function bridge(action,payload={}) {
  return new Promise((resolve,reject)=>{const id='r'+(++counter);pending.set(id,{resolve,reject});
    if(!window.NativeLife){pending.delete(id);reject(new Error('请在 Android 应用中使用'));return;}
    window.NativeLife.call(id,action,JSON.stringify({provider,...payload}));
  });
}
window.nativeResult=(id,result)=>{const p=pending.get(id);if(!p)return;pending.delete(id);result.ok?p.resolve(result.data):p.reject(new Error(result.error||'操作未完成'));};
function toast(text){el('toast').textContent=text;el('toast').classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el('toast').classList.remove('visible'),4500);}
const money=v=>v!==null&&v!==undefined&&v!==''&&Number.isFinite(Number(v))?Number(v).toFixed(2):'—';
function btn(label,action,kind='primary',extra=''){return `<button class="${kind}" data-action="${action}" ${busy?'disabled':''} ${extra}>${label}</button>`;}
function nav(){return `<nav class="nav" aria-label="主导航">${[['home','home','日常'],['devices','device','设备'],['account','user','我的']].map(([id,i,t])=>`<button data-nav="${id}" class="${view===id?'active':''}">${icon(i)}<span>${t}</span></button>`).join('')}</nav>`;}
function switcher(){return `<div class="switcher" role="group" aria-label="选择服务">${['water','bath'].map(p=>`<button class="service ${p} ${provider===p?'selected':''}" data-provider="${p}" aria-pressed="${provider===p}">${provider===p?'<span class="selected-dot"></span>':''}${icon(p==='water'?'drop':'bath')}<strong>${verbs[p]}</strong><small>${names[p]}</small></button>`).join('')}</div>`;}
function statusBlock(){return `<div id="feedback"><div class="error-box ${error?'':'hidden'}" role="alert">${escape(error)}</div></div>`;}
function deviceList(manage=false){const list=state[provider].devices||[];if(!list.length)return '<div class="empty-line">暂无常用设备</div>';return list.map((d,i)=>`<div class="favorite"><button class="device-icon" data-device="${i}" aria-label="选择设备">${icon(provider==='water'?'drop':'bath')}</button><button class="device-name" data-device="${i}"><strong>${escape(d.name)}</strong><small>${escape(d.id)}</small></button>${manage?`<button class="delete" data-remove="${i}">移除</button>`:icon('arrow')}</div>`).join('');}
function activePanel(){
  const a=state[provider].active;if(!a)return '';
  const titles={running:'正在'+verbs[provider],starting:'确认启动中',unknown:'启动待确认',stopping:'确认结束中',stop_unknown:'结束待确认',pending:'等待确认'};
  const uncertain=!['running','stopping'].includes(a.phase);
  return `<section class="running ${uncertain?'uncertain':''}"><div class="eyebrow">${names[provider]}</div><h2>${titles[a.phase]||'订单待确认'}</h2><span class="device-number">${escape(a.device)}</span>${a.phase!=='running'?'<p>设备可能仍在计费，请刷新确认。</p>':''}${a.paymentType?`<div class="payment-label">${paymentNames[a.paymentType]||'平台支付'}</div>`:''}${btn('结束'+verbs[provider],'stop','danger')}${btn(icon('refresh')+'刷新','sync','text-button')}${a.idle?btn('核对设备已关闭','ackIdle','text-button'):''}${a.emptyConfirmed&&!a.orderId?btn('核对订单已结束','ackNoOrder','text-button'):''}<button class="text-button official-link" data-action="official">前往官方核对</button></section>`;
}
function home(){
  const s=state[provider],other=provider==='water'?'bath':'water';
  return `${switcher()}${statusBlock()}${state[other].active?`<button class="active-other" data-provider="${other}">${verbs[other]}订单待结束 →</button>`:''}${activePanel()}${!s.loggedIn?`<section class="panel login-prompt"><div class="empty-icon">${icon('lock')}</div><h2>${names[provider]}</h2><span class="muted">${s.expired?'登录已过期':'未登录'}</span>${btn(provider==='water'?'支付宝登录':'登录','loginView')}</section>`:!s.active?`<section class="panel"><div class="account-strip"><div><span>${provider==='bath'?escape(s.project||'余额'):'账户'}</span><br><strong>${provider==='bath'?'<small>¥</small>'+money(s.balance):escape(s.phone)}</strong></div><div class="status"><i class="dot"></i>已登录</div></div><div class="entry"><input id="device-input" aria-label="设备号" placeholder="输入设备号" autocomplete="off" value="${escape(deviceValues[provider])}"><button class="scan" data-action="scan" aria-label="扫描设备二维码">${icon('scan')}</button></div>${btn('查询设备','prepare')}</section>`:''}<div class="section-title"><span>常用设备</span><button class="text-button" data-nav="devices">管理</button></div>${deviceList()}${s.last?`<div class="hint">设备已结束${s.last.amount!=null?' · ¥'+money(s.last.amount):' · 费用请查账单'}</div>`:''}`;
}
function devices(){return `<h1 class="page-title">常用设备</h1>${switcher()}${statusBlock()}${deviceList(true)}<div class="gap"></div>${btn(icon('plus')+'添加设备','addDevice','secondary')}`;}
function account(){return `<h1 class="page-title">我的</h1>${statusBlock()}${['water','bath'].map(p=>{
  const s=state[p];return `<section class="panel account-card"><div class="row"><div><h3>${names[p]}</h3><span>${s.loggedIn?escape(s.phone):s.expired?'登录已过期':'未登录'}</span></div>${icon(p==='water'?'drop':'bath')}</div>${s.loggedIn?`<div class="row"><span>${p==='bath'?escape(s.project||'余额'):'钱包'}</span><strong>¥ ${money(s.balance)}</strong></div><div class="actions"><button class="secondary" data-account="refresh" data-p="${p}">刷新</button><button class="secondary" data-account="bills" data-p="${p}">账单</button></div>${p==='water'?'<button class="text-button" data-account="signAlipay" data-p="water">支付宝免密管理</button>':''}<div class="actions"><button class="text-button" data-account="loginView" data-p="${p}">${p==='water'?'支付宝重新登录':'重新登录'}</button><button class="text-button" data-account="logout" data-p="${p}">退出</button></div>`:`<button class="secondary" data-account="loginView" data-p="${p}">${p==='water'?'支付宝登录':'登录'}</button>`}</section>`;
}).join('')}<button class="about-row" data-action="about"><span>关于与隐私</span><span class="small">0.3.1 ${icon('arrow')}</span></button>`;}
function login(){
  if(provider==='water'&&waterLoginMode==='alipay')return `<button class="back" data-nav="home" aria-label="返回">${icon('back')}</button><h1 class="page-title">慧生活798</h1>${statusBlock()}<section class="panel alipay-login"><div class="empty-icon">${icon('lock')}</div><h2>支付宝快捷登录</h2><p>授权慧生活798账户</p>${btn('支付宝登录','loginAlipay')}<button class="text-button" data-action="smsLoginView">短信登录</button></section>`;
  return `<button class="back" data-nav="home" aria-label="返回">${icon('back')}</button><h1 class="page-title">${names[provider]}</h1>${statusBlock()}${provider==='water'?'<div class="login-method"><strong>短信登录</strong><button class="text-button" data-action="alipayLoginView">支付宝登录</button></div>':''}<div class="login-fields"><label for="phone">手机号</label><input id="phone" data-field="phone" inputmode="tel" autocomplete="off" maxlength="11" value="${escape(loginValues.phone)}" placeholder="手机号">${provider==='bath'?`<label for="password">密码</label><input id="password" data-field="password" type="password" autocomplete="off" value="${escape(loginValues.password)}" placeholder="登录密码">`:''}<label for="captcha">图形验证码${provider==='bath'?'（如需）':''}</label><div class="captcha-row"><input id="captcha" data-field="captcha" autocomplete="off" maxlength="12" value="${escape(loginValues.captcha)}" placeholder="图形验证码"><button class="captcha-button" data-action="captcha">${captcha?`<img src="${escape(captcha)}" alt="验证码，点击刷新">`:'获取图形码'}</button></div>${provider==='water'?`<label for="code">短信验证码</label><div class="code-row"><input id="code" data-field="code" inputmode="numeric" autocomplete="off" maxlength="10" value="${escape(loginValues.code)}" placeholder="短信验证码">${btn('获取短信','sms','secondary')}</div>`:''}</div><div class="login-submit">${btn('登录','login')}</div><button class="text-button" data-action="official">打开官方应用</button>`;}
// 同一页面只修补改变的节点，轮询不会重建输入框或打断键盘。
function patchNode(current,next){
  if(current.nodeType!==next.nodeType||current.nodeName!==next.nodeName){current.replaceWith(next.cloneNode(true));return;}
  if(current.nodeType===Node.TEXT_NODE){if(current.nodeValue!==next.nodeValue)current.nodeValue=next.nodeValue;return;}
  if(current.nodeType!==Node.ELEMENT_NODE)return;
  for(const a of [...current.attributes])if(!next.hasAttribute(a.name))current.removeAttribute(a.name);
  for(const a of [...next.attributes])if(current.getAttribute(a.name)!==a.value)current.setAttribute(a.name,a.value);
  if(current instanceof HTMLInputElement){if(current!==document.activeElement&&current.value!==next.value)current.value=next.value;return;}
  const old=[...current.childNodes],fresh=[...next.childNodes];
  for(let i=0;i<Math.max(old.length,fresh.length);i++){
    if(!fresh[i])old[i].remove();else if(!old[i])current.append(fresh[i].cloneNode(true));else patchNode(old[i],fresh[i]);
  }
}
function render(){
  const content=view==='home'?home():view==='devices'?devices():view==='account'?account():login();
  const key=view+':'+provider,changed=key!==lastPage;
  const template=document.createElement('template');
  template.innerHTML=`<header><div class="brand">${icon('drop')}一水生活</div><i class="network-progress ${busy?'loading':''}" aria-label="正在加载"></i></header><main id="page">${content}</main><div id="navigation">${view!=='login'?nav():''}</div>`;
  if(!lastPage)el('app').append(template.content.cloneNode(true));
  else{
    const fresh=template.content;
    patchNode(el('app').querySelector('header'),fresh.querySelector('header'));
    if(changed)el('page').replaceWith(fresh.querySelector('#page').cloneNode(true));else patchNode(el('page'),fresh.querySelector('#page'));
    patchNode(el('navigation'),fresh.querySelector('#navigation'));
  }
  if(changed&&lastPage&&!reduceMotion())el('page').animate([{opacity:0,transform:'translateY(7px)'},{opacity:1,transform:'translateY(0)'}],{duration:200,easing:'cubic-bezier(.22,1,.36,1)'});
  lastPage=key;updateBusy();
}
function updateBusy(){
  document.querySelectorAll('button').forEach(b=>{if(b.dataset.action!=='close')b.disabled=busy||b.dataset.unavailable==='true';});
  const progress=document.querySelector('.network-progress');if(progress)progress.classList.toggle('loading',busy);
  el('app').setAttribute('aria-busy',String(busy));
}
function navigate(next){if(busy)return;if(view==='login'&&next!=='login'){loginValues.password='';loginValues.code='';loginValues.captcha='';captcha='';}view=next;error='';render();window.scrollTo(0,0);}
async function run(action,payload={},options={}){
  if(busy)return null;busy=true;error='';updateBusy();
  const box=document.querySelector('#feedback .error-box');if(box){box.textContent='';box.classList.add('hidden');}
  try{const data=await bridge(action,payload);if(data?.water&&data?.bath)state=data;return data;}
  catch(e){error=e.message;if(el('overlay').hasChildNodes())toast(error);try{state=await bridge('snapshot');}catch(_){}return null;}
  finally{busy=false;render();updateBusy();}
}
function sheet(html){
  if(!el('overlay').hasChildNodes())sheetFocus=document.activeElement;
  sheetClosing=false;el('overlay').innerHTML=`<div class="modal-backdrop"><div class="sheet" role="dialog" aria-modal="true" aria-label="操作确认" tabindex="-1"><div class="handle"></div>${html}</div></div>`;
  el('app').inert=true;document.body.style.overflow='hidden';
  const panel=el('overlay').querySelector('.sheet'),back=panel.parentElement;
  if(!reduceMotion()){
    back.animate([{opacity:0},{opacity:1}],{duration:180});
    panel.animate([{transform:'translateY(32px)',opacity:0},{transform:'translateY(0)',opacity:1}],{duration:240,easing:'cubic-bezier(.22,1,.36,1)'});
  }
  panel.focus({preventScroll:true});updateBusy();
}
function closeSheet(){
  const back=el('overlay').firstElementChild;if(!back||sheetClosing)return;
  sheetClosing=true;prepared=null;billPrepared=null;back.inert=true;
  const finish=()=>{if(el('overlay').firstElementChild!==back)return;back.remove();sheetClosing=false;el('app').inert=false;document.body.style.overflow='';if(sheetFocus?.isConnected)sheetFocus.focus({preventScroll:true});};
  if(reduceMotion()){finish();return;}
  const panel=back.querySelector('.sheet');back.getAnimations().forEach(a=>a.cancel());panel.getAnimations().forEach(a=>a.cancel());
  back.animate([{opacity:1},{opacity:0}],{duration:160,fill:'forwards'});
  panel.animate([{transform:'translateY(0)',opacity:1},{transform:'translateY(20px)',opacity:0}],{duration:160,easing:'ease-out',fill:'forwards'}).finished.then(finish,finish);
}
function showPrepared(d){
  prepared=d;const methods=d.payments||[];paymentType=methods.includes(21)?21:methods.includes(91)?91:0;
  sheet(`<p class="eyebrow">${names[provider]}</p><h2>${escape(d.name)}</h2><div class="hero-id">${escape(d.id)}</div>${d.location?.trim()?`<p>${escape(d.location)}</p>`:''}${provider==='water'?`<div class="payment-options" role="group" aria-label="支付方式">${methods.map(t=>`<button data-payment="${t}" class="payment-option ${t===paymentType?'selected':''}" aria-pressed="${t===paymentType}">${escape(paymentNames[t])}</button>`).join('')}</div>`:''}<p>${escape(d.billing)}，开始后产生费用。</p><button class="text-button" data-action="favoritePrepared">${icon('plus')} 收藏</button><div class="actions">${btn('取消','close','secondary')}${btn('开始'+verbs[provider],'start')}</div>`);
}
async function refresh(){const d=await run('refresh');if(d)lastSync=Date.now();}
async function doAction(action){
  if(action==='close'){closeSheet();return;}
  if(action==='about'){sheet('<h2>一水生活</h2><div class="legal"><p>独立第三方客户端，账户与计费由原平台管理。</p><p>登录凭据在本机加密保存，不备份。相机仅用于扫码，画面不上传。</p><p>协议参考 drink_798（MIT），扫码使用 ZXing（Apache-2.0）。支付宝授权与支付使用支付宝 SDK。完整说明与许可随源码提供。</p></div>'+btn('关闭','close','secondary'));return;}
  if(busy)return;
  if(action==='alipayLoginView'||action==='smsLoginView'){waterLoginMode=action==='alipayLoginView'?'alipay':'sms';error='';render();return;}
  if(action==='loginAlipay'){const d=await run('loginAlipay');if(d){navigate('home');toast('支付宝登录成功');await refresh();}return;}
  if(action==='signAlipay'){sheet('<h2>支付宝免密</h2><p>前往支付宝查看慧生活798的免密协议，由你确认签约。</p><div class="actions">'+btn('取消','close','secondary')+btn('前往支付宝','confirmSignAlipay')+'</div>');return;}
  if(action==='confirmSignAlipay'){closeSheet();const d=await run('signAlipay');if(d)toast(d.message||'请以支付宝协议状态为准');return;}
  if(action==='confirmPay'){if(!billPrepared)return;const ticket=billPrepared.ticket;closeSheet();const d=await run('payAlipay',{ticket});if(d){toast(d.message);await doAction('bills');}return;}
  if(action==='loginView'){waterLoginMode='alipay';loginValues={phone:'',password:'',captcha:'',code:''};captcha='';navigate('login');return;}
  if(action==='addDevice'){navigate('home');toast('输入或扫描设备号，查询后收藏');return;}
  if(action==='official'){try{await bridge('official');}catch(e){toast(e.message);}return;}
  if(action==='scan'){const d=await run('scan');if(d){deviceValues[provider]=d.text;render();}return;}
  if(action==='prepare'){
    const device=el('device-input')?.value?.trim();if(!device){toast('请先输入或扫描设备号');return;}
    const d=await run('prepare',{device});if(d)showPrepared(d);return;
  }
  if(action==='favoritePrepared'){if(!prepared)return;const d=await run('saveDevice',{id:prepared.id,name:prepared.name});if(d)toast('已加入常用设备');return;}
  if(action==='start'){if(!prepared)return;const ticket=prepared.ticket;closeSheet();const d=await run('start',{ticket,paymentType});if(d)await run('sync');return;}
  if(action==='stop'){await run('stop');return;}
  if(action==='sync'){await run('sync');lastSync=Date.now();return;}
  if(action==='ackIdle'){sheet('<h2>确认设备已关闭</h2><p>仅在你已检查设备不再出水后确认。这会清除本地待确认状态，不会撤销消费。费用请查看平台账单。</p><div class="actions">'+btn('再等等','close','secondary')+btn('已确认关闭','confirmIdle')+'</div>');return;}
  if(action==='confirmIdle'){closeSheet();await run('ackIdle');return;}
  if(action==='ackNoOrder'){sheet('<h2>核对洗浴状态</h2><p>平台当前没有返回进行中的洗浴订单。请先在住理生活账单和现场设备确认已结束，再清除本地待确认状态。</p><div class="actions">'+btn('再检查一下','close','secondary')+btn('已核对结束','confirmNoOrder')+'</div>');return;}
  if(action==='confirmNoOrder'){closeSheet();await run('ackNoOrder');return;}
  if(action==='captcha'){const d=await run('captcha',{phone:loginValues.phone});if(d){captcha=d.image;loginValues.captcha='';render();}return;}
  if(action==='sms'){if(!/^1\d{10}$/.test(loginValues.phone)||!loginValues.captcha){toast('请填写手机号和图形验证码');return;}const d=await run('sms',{...loginValues});if(d)toast(d.message);return;}
  if(action==='login'){
    if(!/^1\d{10}$/.test(loginValues.phone)){toast('请输入正确的手机号');return;}
    if(provider==='water'&&!loginValues.code||provider==='bath'&&!loginValues.password){toast(provider==='water'?'请输入短信验证码':'请输入密码');return;}
    const d=await run('login',{...loginValues});loginValues.password='';loginValues.code='';
    if(d){captcha='';navigate('home');toast('已连接，正在同步账户');await refresh();}else render();return;
  }
  if(action==='refresh'){await refresh();return;}
  if(action==='logout'){sheet(`<h2>退出${names[provider]}？</h2><div class="actions">${btn('取消','close','secondary')}${btn('退出','confirmLogout')}</div>`);return;}
  if(action==='confirmLogout'){closeSheet();await run('logout');return;}
  if(action==='bills'){
    const d=await run('bills');if(!d)return;
    let rows=Array.isArray(d.records)?d.records:d.records?.records||d.records?.list||d.records?.items||[];
    if(!Array.isArray(rows))rows=[];
    const statuses=provider==='water'?{1:'未付款',2:'待确认',3:'已付款',4:'付款失败',5:'核算中',9:'已取消'}:{consuming:'使用中',finished:'已结束',refund:'已退款',waiting:'等待确认'};
    sheet(`<h2>账单</h2>${rows.length?rows.slice(0,20).map(r=>`<div class="bill"><div class="bill-head"><span>${escape(r.dev?.name||r.device_name||r.devName||r.name||'设备消费')}</span><strong>¥ ${money(r.consume_money??r.payment??r.amount)}</strong></div><small>${escape(statuses[r.status]||r.status||'待确认')} · ${escape(r.order_id||r.id||'')}</small>${provider==='water'&&[1,4].includes(r.status)?`<button class="text-button" data-bill="${escape(r.id)}">支付宝支付</button>`:''}</div>`).join(''):'<div class="empty-line">暂无账单</div>'}<div class="actions">${btn('刷新','bills','secondary')}${btn('关闭','close','secondary')}</div>`);return;
  }
}
document.addEventListener('input',e=>{if(e.target.dataset.field)loginValues[e.target.dataset.field]=e.target.value;if(e.target.id==='device-input')deviceValues[provider]=e.target.value;});
document.addEventListener('pointerdown',()=>{pointerMotion=true;});
document.addEventListener('keydown',e=>{
  pointerMotion=false;const panel=el('overlay').querySelector('.sheet');if(!panel)return;
  if(e.key==='Escape'&&!busy){e.preventDefault();closeSheet();}
  if(e.key==='Tab'){
    const items=[...panel.querySelectorAll('button:not(:disabled),input:not(:disabled),[tabindex="0"]')];if(!items.length){e.preventDefault();return;}
    if(e.shiftKey&&(document.activeElement===items[0]||document.activeElement===panel)){e.preventDefault();items.at(-1).focus();}
    else if(!e.shiftKey&&(document.activeElement===items.at(-1)||document.activeElement===panel)){e.preventDefault();items[0].focus();}
  }
});
document.addEventListener('click',async e=>{
  if(e.target.classList.contains('modal-backdrop')){if(!busy)closeSheet();return;}
  const b=e.target.closest('button');if(!b||b.disabled)return;
  if(b.dataset.bill){const d=await run('prepareBill',{id:b.dataset.bill});if(d){billPrepared=d;sheet(`<h2>支付宝支付</h2><div class="hero-id">¥ ${money(d.amount)}</div><p>账单 ${escape(d.id)}。确认后将通过已签免密协议扣款，或跳转支付宝付款。</p><div class="actions">${btn('取消','close','secondary')}${btn('确认支付','confirmPay')}</div>`);}return;}
  if(b.dataset.payment){if(busy)return;paymentType=Number(b.dataset.payment);document.querySelectorAll('[data-payment]').forEach(x=>{const selected=Number(x.dataset.payment)===paymentType;x.classList.toggle('selected',selected);x.setAttribute('aria-pressed',String(selected));});return;}
  if(b.dataset.nav){closeSheet();navigate(b.dataset.nav);return;}
  if(b.dataset.provider){if(busy)return;provider=b.dataset.provider;error='';render();if(state[provider].loggedIn)await run('sync');return;}
  if(b.dataset.account){if(busy)return;provider=b.dataset.p;await doAction(b.dataset.account);return;}
  if(b.dataset.device!=null){if(busy)return;const d=state[provider].devices[+b.dataset.device];deviceValues[provider]=d.id;navigate('home');if(!state[provider].loggedIn)toast('请先登录');return;}
  if(b.dataset.remove!=null){if(busy)return;const d=state[provider].devices[+b.dataset.remove];await run('removeDevice',{id:d.id});return;}
  if(b.dataset.action)await doAction(b.dataset.action);
});
window.appBack=()=>{if(busy)return;if(el('overlay').hasChildNodes()){closeSheet();return;}if(view!=='home'){navigate('home');return;}toast('正在使用的设备不会自动结束');};
window.appResume=async()=>{if(!busy&&view!=='login'&&state[provider].loggedIn&&Date.now()-lastSync>8000){lastSync=Date.now();await run(state[provider].active?'sync':'refresh');}};
setInterval(async()=>{if(document.hidden||busy||view==='login'||el('overlay').hasChildNodes())return;if(state[provider].active&&Date.now()-lastSync>8000){lastSync=Date.now();await run('sync');}},4000);
render();bridge('snapshot').then(s=>{state=s;render();window.appResume();}).catch(e=>{error=e.message;render();});
