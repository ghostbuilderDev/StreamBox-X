const $=(s,r=document)=>r.querySelector(s), $$=(s,r=document)=>[...r.querySelectorAll(s)];
const STORE_PREFIX='sbv8_';
const store={
  get(k,d){try{return JSON.parse(localStorage.getItem(STORE_PREFIX+k))??d}catch{return d}},
  set(k,v){
    try{localStorage.setItem(STORE_PREFIX+k,JSON.stringify(v));return true}
    catch(e){
      console.warn('Stockage local limité',k,e);
      try{localStorage.removeItem('sbv9_catalog');localStorage.removeItem('sbv7_catalog');localStorage.removeItem('sbv8_catalog')}catch{}
      try{localStorage.setItem(STORE_PREFIX+k,JSON.stringify(v));return true}catch{return false}
    }
  },
  del(k){try{localStorage.removeItem(STORE_PREFIX+k)}catch{}}
};
// Migration automatique des identifiants enregistrés par les versions précédentes.
try{
  for(const key of ['profile','profiles','lastCredentials']){
    if(localStorage.getItem('sbv8_'+key))continue;
    const previous=localStorage.getItem('sbv7_'+key)||localStorage.getItem('sbv6_'+key)||localStorage.getItem('sbv9_'+key);
    if(previous)localStorage.setItem('sbv8_'+key,previous)
  }
}catch{}
// Nettoyage des anciens catalogues complets responsables des erreurs QuotaExceededError.
try{localStorage.removeItem('sbv9_catalog');localStorage.removeItem('sbv7_catalog')}catch{}
const blank=()=>({live:[],movies:[],series:[],liveCats:[],movieCats:[],seriesCats:[]});
const demo={
  live:[
    {stream_id:1,name:'Vision Live',stream_icon:'',category_id:'1',stream_type:'live'},
    {stream_id:2,name:'Arena Sports',stream_icon:'https://storage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerEscapes.jpg',category_id:'1',stream_type:'live'},
    {stream_id:3,name:'Cinema One',stream_icon:'https://storage.googleapis.com/gtv-videos-bucket/sample/images/ElephantsDream.jpg',category_id:'1',stream_type:'live'}],
  movies:[
    {stream_id:101,name:'Big Buck Bunny',stream_icon:'https://storage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg',category_id:'10',rating:'8.2',container_extension:'mp4',plot:'Un film de démonstration pour découvrir la nouvelle expérience StreamBox Vision.',genre:'Animation',releaseDate:'2008'},
    {stream_id:102,name:'Elephants Dream',stream_icon:'https://storage.googleapis.com/gtv-videos-bucket/sample/images/ElephantsDream.jpg',category_id:'10',rating:'7.7',container_extension:'mp4',plot:'Une aventure visuelle qui met en valeur la nouvelle interface cinématique.',genre:'Science-fiction',releaseDate:'2006'},
    {stream_id:103,name:'For Bigger Fun',stream_icon:'https://storage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerFun.jpg',category_id:'10',rating:'8.0',container_extension:'mp4',plot:'Un contenu de démonstration pour tester le lecteur vidéo.',genre:'Action'}],
  series:[
    {series_id:201,name:'For Bigger Blazes',cover:'https://storage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerBlazes.jpg',category_id:'20',rating:'7.9',plot:'Une série de démonstration avec une présentation premium.',genre:'Aventure'},
    {series_id:202,name:'For Bigger Escapes',cover:'https://storage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerEscapes.jpg',category_id:'20',rating:'8.1',plot:'Découvre la gestion des séries et des épisodes.',genre:'Action'}],
  liveCats:[{category_id:'1',category_name:'Général'}],movieCats:[{category_id:'10',category_name:'Films'}],seriesCats:[{category_id:'20',category_name:'Séries'}]
};
let profile=store.get('profile',null),profiles=store.get('profiles',[]),data=blank(),favorites=store.get('favorites',[]),watchHistory=store.get('history',[]),positions=store.get('positions',{}),settings=store.get('settings',{autoplay:true,resume:true,external:false,liveFormat:'m3u8'});
let currentPage='home',currentCatalogType='live',catalog=[],visible=50,currentSelection=null,currentEpisode=null,heroItems=[],heroIndex=0,heroTimer=null,hls=null,dash=null,lastStreamUrl='',searchTimer=null,dragStartY=0;
let castSdkReady=false,castState='idle',castDeviceName='',remotePlayer=null,remoteController=null;
const esc=s=>(s??'').toString().replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
const normServer=s=>(s||'').trim().replace(/\/+$/,'');
const titleOf=x=>x?.name||x?.title||'Sans titre';
const itemId=(type,item)=>String(type==='series'?item.series_id:item.stream_id);
const keyOf=(type,item,episode)=>`${type}:${itemId(type,item)}${episode?':'+episode.id:''}`;
const imageOf=(item,type)=>type==='series'?(item.cover||item.backdrop_path?.[0]||item.stream_icon||''):(item.stream_icon||item.cover||item.movie_image||item.backdrop_path?.[0]||'');
function icon(id){return `<svg><use href="#${id}"/></svg>`}
function buzz(ms=12){try{navigator.vibrate?.(ms)}catch{}}
function toast(t){const el=$('#toast');el.textContent=t;el.classList.add('show');clearTimeout(el._t);el._t=setTimeout(()=>el.classList.remove('show'),2400)}
function setHidden(el,hidden=true){el?.classList.toggle('hidden',hidden)}
function status(text,error=true){const el=$('#loginStatus');el.textContent=text||'';el.style.color=error?'#ffc0cb':'#bdf8df'}
function castBridge(){return window.StreamBoxCast||window.AndroidCast||window.NativeAndroidCast||null}
function playerBridge(){return window.StreamBoxPlayer||window.AndroidPlayer||window.NativeAndroidPlayer||null}
function nativePlayerCapabilities(){const b=playerBridge();if(!b)return{native:false,play:false};try{if(typeof b.getCapabilities==='function')return{native:true,...parseBridgeResult(b.getCapabilities(),{})}}catch(e){console.warn('Player capabilities',e)}return{native:true,play:typeof b.play==='function'}}
function nativePlayPayload(item,type,episode,url){return{url,title:episode?.title||titleOf(item),subtitle:type==='live'?'En direct':type==='movies'?'Film':'Série',image:imageOf(item,type),mime:castMime(url),isLive:type==='live',startPositionMs:type==='live'?0:Math.round((positions[keyOf(type,item,episode)]?.time||0)*1000)}}
function launchNativePlayer(item,type,episode,url){const b=playerBridge(),caps=nativePlayerCapabilities();if(!b||!caps.play||typeof b.play!=='function')return false;try{const result=parseBridgeResult(b.play(JSON.stringify(nativePlayPayload(item,type,episode,url))),{ok:true});if(result.ok===false)throw Error(result.error||'Lecteur natif indisponible');return true}catch(e){console.warn('Native player',e);toast(e.message||'Impossible d’ouvrir le lecteur natif');return false}}
function parseBridgeResult(value,fallback={}){if(value==null)return fallback;if(typeof value==='object')return value;try{return JSON.parse(value)}catch{return fallback}}

function diagnosticBridge(){return window.StreamBoxDiagnostics||null}
function sanitizeDiagnosticUrl(raw){try{const u=new URL(raw);const p=u.pathname.split('/');if(['live','movie','series'].includes(p[1])&&p.length>3){p[2]='***USER***';p[3]='***PASSWORD***';u.pathname=p.join('/')}return u.toString()}catch{return String(raw||'').replace(/\/(live|movie|series)\/[^/]+\/[^/]+\//,'/$1/***USER***/***PASSWORD***/')}}
function diagnosticPayload(eventName,extra={}){const v=$('#video'),caps=nativePlayerCapabilities(),castCaps=nativeCapabilities();return{event:eventName,generatedAt:new Date().toISOString(),appVersion:'10.0.0',page:currentPage,online:navigator.onLine,userAgent:navigator.userAgent,platform:navigator.platform,screen:{width:screen.width,height:screen.height,pixelRatio:devicePixelRatio},profile:{name:profile?.name||'',server:profile?.server||'',demo:!!profile?.demo},selection:currentSelection?{type:currentSelection.type,title:titleOf(currentSelection.item),id:itemId(currentSelection.type,currentSelection.item),episode:currentEpisode?.title||null}:null,url:sanitizeDiagnosticUrl(lastStreamUrl||v?.currentSrc||''),video:v?{errorCode:v.error?.code||null,errorMessage:v.error?.message||null,networkState:v.networkState,readyState:v.readyState,paused:v.paused,currentTime:v.currentTime,duration:Number.isFinite(v.duration)?v.duration:null}:null,nativePlayer:caps,nativeCast:castCaps,...extra}}
function diagnosticText(payload){return 'STREAMBOX VISION — RAPPORT DIAGNOSTIC\nNe pas modifier avant envoi.\n\n'+JSON.stringify(payload,null,2)+'\n'}
function fallbackDownloadDiagnostic(payload){try{const blob=new Blob([diagnosticText(payload)],{type:'text/plain'}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=`streambox-diagnostic-${new Date().toISOString().replace(/[:.]/g,'-')}.txt`;document.body.appendChild(a);a.click();setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove()},1000);return true}catch{return false}}
function saveDiagnostic(eventName,extra={},announce=true){const payload=diagnosticPayload(eventName,extra),bridge=diagnosticBridge();try{if(bridge&&typeof bridge.saveReport==='function'){const result=parseBridgeResult(bridge.saveReport(JSON.stringify(payload)),{});if(result.ok){if(announce)toast('Diagnostic enregistré : '+(result.path||result.name||'Téléchargements'));window.__lastDiagnostic=payload;return result}}}catch(e){payload.bridgeSaveError=String(e?.message||e)}const downloaded=fallbackDownloadDiagnostic(payload);window.__lastDiagnostic=payload;if(announce)toast(downloaded?'Diagnostic téléchargé':'Diagnostic prêt mais téléchargement bloqué');return{ok:downloaded,payload}}
function shareDiagnostic(){const payload=window.__lastDiagnostic||diagnosticPayload('manual_diagnostic');const bridge=diagnosticBridge();try{if(bridge&&typeof bridge.shareLast==='function')return parseBridgeResult(bridge.shareLast(JSON.stringify(payload)),{ok:true})}catch{}fallbackDownloadDiagnostic(payload);return{ok:true}}

function nativeCapabilities(){const bridge=castBridge();if(!bridge)return{native:false,cast:false,settings:false,share:false,external:false};try{if(typeof bridge.getCapabilities==='function')return{native:true,...parseBridgeResult(bridge.getCapabilities(),{})}}catch(e){console.warn('Cast capabilities',e)}return{native:true,cast:typeof bridge.requestSession==='function'||typeof bridge.cast==='function',settings:typeof bridge.openCastSettings==='function',share:typeof bridge.shareMedia==='function',external:typeof bridge.openExternal==='function'}}
function castMime(url){const clean=(url||'').split('?')[0].toLowerCase();if(clean.endsWith('.m3u8'))return'application/x-mpegURL';if(clean.endsWith('.mpd'))return'application/dash+xml';if(clean.endsWith('.ts'))return'video/mp2t';if(clean.endsWith('.webm'))return'video/webm';return'video/mp4'}
function currentCastPayload(){if(!currentSelection)return null;const {item,type}=currentSelection;const playerOpen=!$('#playerOverlay').classList.contains('hidden');const url=playerOpen&&lastStreamUrl?lastStreamUrl:streamUrl(item,type,currentEpisode);if(!url)return null;return{url,title:currentEpisode?.title||titleOf(item),subtitle:type==='live'?'En direct':type==='movies'?'Film':'Série',image:imageOf(item,type),mime:castMime(url),isLive:type==='live',currentTime:type==='live'?0:($('#video')?.currentTime||0)}}
function setCastState(state,name=''){castState=state;castDeviceName=name||castDeviceName;const connected=state==='connected',connecting=state==='connecting';$$('.cast-button').forEach(b=>{b.classList.toggle('connected',connected);b.classList.toggle('connecting',connecting)});$('#castStatusText').textContent=connected?`Connecté à ${castDeviceName||'un téléviseur'}`:connecting?'Connexion au téléviseur…':'Choisis une méthode disponible';$('#settingsCastStatus').textContent=connected?`Connecté à ${castDeviceName||'un téléviseur'}`:'Chromecast et solutions Android compatibles';$('#castDirectLabel').textContent=connected?'Caster ce contenu':'Choisir un téléviseur';setHidden($('#stopCastButton'),!connected);setHidden($('#remoteCastOverlay'),!connected);if(connected)$('#remoteCastDevice').textContent=`Diffusion en cours sur ${castDeviceName||'le téléviseur'}.`}
function setOptionAvailability(button,available,hint){if(!button)return;button.disabled=!available;button.classList.toggle('option-disabled',!available);const small=button.querySelector('small');if(small&&hint)small.textContent=hint}
function updateCastPanel(){const payload=currentCastPayload();setHidden($('#castNowPlaying'),!payload);if(payload){$('#castTitle').textContent=payload.title;$('#castMeta').textContent=payload.subtitle;$('#castPoster').style.backgroundImage=payload.image?`url('${payload.image}')`:'linear-gradient(145deg,#1b2e64,#2b1756)'}const caps=nativeCapabilities();const hasWeb=castSdkReady||!!(window.__castApiAvailable&&window.cast?.framework&&window.chrome?.cast);const hasRemote=!!$('#video')?.remote?.prompt;const direct=!!(caps.cast||hasWeb||hasRemote);setOptionAvailability($('#castDirectButton'),direct,direct?(caps.cast?'Sélecteur Google Cast Android natif':hasWeb?'Google Cast disponible dans le navigateur':'Lecture à distance disponible'):'Module Cast natif absent de cet APK');setOptionAvailability($('#castScreenButton'),!!caps.settings,caps.settings?'Ouvrir les réglages Cast Android':'Nécessite le pont Android inclus dans le dossier native-cast-addon');setOptionAvailability($('#castShareButton'),!!(caps.share||navigator.clipboard),caps.share?'Ouvrir le partage Android':'Copier le lien du flux dans le presse-papiers');const statusEl=$('#castCapabilityStatus');if(statusEl){statusEl.className='cast-capability '+(direct?'ready':'warning');statusEl.innerHTML=direct?'<b>Cast direct prêt</b><span>Le module compatible a été détecté dans l’APK.</span>':'<b>Cast natif non intégré</b><span>Cette version HTML ne peut pas découvrir seule les téléviseurs. Intègre le module Android fourni pour activer le sélecteur réel.</span>'}}
function openCastSheet(){updateCastPanel();setHidden($('#castSheet'),false);pushState('overlay','cast');buzz()}
function closeCastSheet(){setHidden($('#castSheet'),true)}
function initCastSdk(){if(castSdkReady||!window.__castApiAvailable||!window.cast?.framework||!window.chrome?.cast)return false;try{const ctx=cast.framework.CastContext.getInstance();ctx.setOptions({receiverApplicationId:chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID,autoJoinPolicy:chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED});ctx.addEventListener(cast.framework.CastContextEventType.SESSION_STATE_CHANGED,e=>{const active=[cast.framework.SessionState.SESSION_STARTED,cast.framework.SessionState.SESSION_RESUMED].includes(e.sessionState);const ending=[cast.framework.SessionState.SESSION_ENDED,cast.framework.SessionState.SESSION_ENDING].includes(e.sessionState);if(active){const session=ctx.getCurrentSession();const device=session?.getCastDevice?.();setupRemoteController();setCastState('connected',device?.friendlyName||'Chromecast')}else if(ending){remotePlayer=null;remoteController=null;setCastState('idle','');setHidden($('#remoteCastOverlay'),true)}});castSdkReady=true;return true}catch(e){console.warn('Cast SDK init',e);return false}}
function setupRemoteController(){if(!window.cast?.framework)return;try{remotePlayer=new cast.framework.RemotePlayer();remoteController=new cast.framework.RemotePlayerController(remotePlayer);remoteController.addEventListener(cast.framework.RemotePlayerEventType.IS_PAUSED_CHANGED,()=>{$('#togglePlay').innerHTML=icon(remotePlayer.isPaused?'i-play':'i-pause')})}catch(e){console.warn('Remote controller',e)}}
async function castViaWeb(payload){if(!initCastSdk())throw Error('SDK Cast indisponible');const ctx=cast.framework.CastContext.getInstance();let session=ctx.getCurrentSession();if(!session){setCastState('connecting');await ctx.requestSession();session=ctx.getCurrentSession()}if(!session)throw Error('Aucun téléviseur sélectionné');const mediaInfo=new chrome.cast.media.MediaInfo(payload.url,payload.mime);mediaInfo.streamType=payload.isLive?chrome.cast.media.StreamType.LIVE:chrome.cast.media.StreamType.BUFFERED;const metadata=new chrome.cast.media.GenericMediaMetadata();metadata.title=payload.title;metadata.subtitle=payload.subtitle;if(payload.image)metadata.images=[new chrome.cast.Image(payload.image)];mediaInfo.metadata=metadata;const request=new chrome.cast.media.LoadRequest(mediaInfo);request.autoplay=true;if(!payload.isLive&&payload.currentTime>0)request.currentTime=payload.currentTime;await session.loadMedia(request);const device=session.getCastDevice?.();setupRemoteController();$('#video').pause();setCastState('connected',device?.friendlyName||'Chromecast');toast(`Diffusion sur ${device?.friendlyName||'le téléviseur'}`)}
async function castViaNative(payload){const bridge=castBridge();const caps=nativeCapabilities();if(!bridge||!caps.cast)return false;setCastState('connecting');const method=typeof bridge.requestSession==='function'?'requestSession':'cast';const result=parseBridgeResult(bridge[method](JSON.stringify(payload)),{});if(result.ok===false){setCastState('idle','');throw Error(result.error||'Échec du cast')}if(result.deviceName)castDeviceName=result.deviceName;return true}
async function castViaRemotePlayback(){const v=$('#video');if(!v?.remote||typeof v.remote.prompt!=='function')return false;await v.remote.prompt();setCastState(v.remote.state==='connected'?'connected':'connecting','Téléviseur');return true}
async function startCast(){const payload=currentCastPayload();if(!payload){toast(currentSelection?.type==='series'?'Choisis d’abord un épisode':'Lance une vidéo avant de caster');return}const caps=nativeCapabilities();try{if(caps.cast&&await castViaNative(payload)){closeCastSheet();return}}catch(e){console.warn('Native cast',e);toast(e.message||'Le sélecteur TV n’a pas pu démarrer');return}try{if(window.__castApiAvailable){await castViaWeb(payload);closeCastSheet();return}}catch(e){console.warn('Web cast',e)}try{if(await castViaRemotePlayback()){closeCastSheet();return}}catch(e){console.warn('Remote playback',e)}updateCastPanel();toast('Cast direct indisponible : le module Android natif doit être intégré dans HTMLtoAPK.')}
function openAndroidCastSettings(){const bridge=castBridge(),caps=nativeCapabilities();if(bridge&&caps.settings&&typeof bridge.openCastSettings==='function'){try{const result=parseBridgeResult(bridge.openCastSettings(),{ok:true});if(result.ok===false)toast(result.error||'Réglages Cast indisponibles');return}catch(e){console.warn('Cast settings',e)}}toast('Réglages Cast indisponibles dans cette WebView. Utilise le bouton Cast du panneau rapide Android.')}
async function copyStreamUrl(payload){try{await navigator.clipboard.writeText(payload.url);toast('Lien du flux copié');return true}catch{const input=document.createElement('textarea');input.value=payload.url;input.style.position='fixed';input.style.opacity='0';document.body.appendChild(input);input.select();const ok=document.execCommand('copy');input.remove();toast(ok?'Lien du flux copié':'Impossible de copier le lien');return ok}}
async function shareToTvApp(){const payload=currentCastPayload();if(!payload){toast('Lance une vidéo avant de partager le flux');return}const bridge=castBridge(),caps=nativeCapabilities();if(bridge&&caps.share&&typeof bridge.shareMedia==='function'){try{const result=parseBridgeResult(bridge.shareMedia(JSON.stringify(payload)),{ok:true});if(result.ok===false)toast(result.error||'Aucune application compatible trouvée');return}catch(e){console.warn('Native share',e)}}await copyStreamUrl(payload)}
async function openExternalPlayer(){const payload=currentCastPayload();if(!payload&&lastStreamUrl){const fallback={url:lastStreamUrl,title:$('#playingTitle')?.textContent||'Lecture',mime:castMime(lastStreamUrl)};return openExternalPayload(fallback)}if(!payload){toast('Aucun flux à ouvrir');return}return openExternalPayload(payload)}
async function openExternalPayload(payload){const bridge=castBridge(),caps=nativeCapabilities();if(bridge&&caps.external&&typeof bridge.openExternal==='function'){try{const result=parseBridgeResult(bridge.openExternal(JSON.stringify(payload)),{ok:true});if(result.ok===false)toast(result.error||'Aucun lecteur compatible installé');return}catch(e){console.warn('External player',e)}}await copyStreamUrl(payload);toast('Lien copié. Colle-le dans VLC ou BubbleUPnP.')}
async function stopCasting(){try{const bridge=castBridge();if(bridge&&typeof bridge.stop==='function')bridge.stop();else if(castSdkReady){const ctx=cast.framework.CastContext.getInstance();const session=ctx.getCurrentSession();if(session)await session.endSession(true)}}catch(e){console.warn('Stop cast',e)}remotePlayer=null;remoteController=null;setCastState('idle','');setHidden($('#remoteCastOverlay'),true);closeCastSheet();if(lastStreamUrl&&!settings.external)startPlayback(lastStreamUrl);toast('Diffusion arrêtée')}
function toggleTransport(){if(castState==='connected'&&remoteController){remoteController.playOrPause();return}const v=$('#video');v.paused?v.play():v.pause()}
function seekTransport(delta){if(castState==='connected'&&remoteController&&remotePlayer){remotePlayer.currentTime=Math.max(0,(remotePlayer.currentTime||0)+delta);remoteController.seek();return}const v=$('#video');v.currentTime=Math.max(0,Math.min(v.duration||Infinity,v.currentTime+delta))}
window.onStreamBoxCastState=function(value){try{const state=typeof value==='string'?JSON.parse(value):value;setCastState(state?.connected?'connected':state?.connecting?'connecting':'idle',state?.deviceName||'');updateCastPanel()}catch{}}
window.onStreamBoxCastError=function(message){setCastState('idle','');updateCastPanel();toast(message||'Erreur de diffusion')}
window.addEventListener('streambox-cast-api',()=>initCastSdk());
function api(action,extra={}){const u=new URL(profile.server+'/player_api.php');u.searchParams.set('username',profile.username);u.searchParams.set('password',profile.password);if(action)u.searchParams.set('action',action);Object.entries(extra).forEach(([k,v])=>u.searchParams.set(k,v));return fetch(u,{cache:'no-store'}).then(r=>{if(!r.ok)throw Error('Erreur serveur '+r.status);return r.json()})}
function saveProfile(){const idx=profiles.findIndex(p=>p.server===profile.server&&p.username===profile.username);const p={...profile};idx>=0?profiles[idx]=p:profiles.unshift(p);profiles=profiles.slice(0,8);store.set('profiles',profiles)}
function renderProfiles(){const render=(manager=false)=>profiles.length?profiles.map((p,i)=>manager?`<div class="manager-profile" data-profile="${i}"><div class="avatar">${esc((p.name||'P')[0].toUpperCase())}</div><div><b>${esc(p.name||'Profil')}</b><small>${esc(p.server||'Mode démo')}</small></div><button data-delete="${i}">${icon('i-trash')}</button></div>`:`<div class="saved-profile" data-profile="${i}"><div class="avatar">${esc((p.name||'P')[0].toUpperCase())}</div><div class="meta"><b>${esc(p.name||'Profil')}</b><small>${esc(p.server||'Mode démo')}</small></div><button data-connect="${i}">${icon('i-chevron')}</button></div>`).join(''):'<div class="empty">Aucun profil enregistré</div>';
  $('#savedProfiles').innerHTML=render(false);$('#profileManager').innerHTML=render(true);setHidden($('#savedProfilesSection'),!profiles.length)}
async function connectWith(p){
  if(!p.server||!p.username||!p.password){status('Complète le serveur, l’identifiant et le mot de passe.');return}
  profile={...p,server:normServer(p.server),autoLogin:true};
  // Mémorise immédiatement les champs saisis, même si le serveur est momentanément indisponible.
  const remembered={name:profile.name||'Mon profil',server:profile.server,username:profile.username,password:profile.password};
  store.set('lastCredentials',remembered);
  try{window.NativeAndroid?.saveSecret?.('lastCredentials',JSON.stringify(remembered))}catch{}
  $('#connectBtn').disabled=true;
  status('Vérification du compte…',false);
  try{
    const info=await api('');
    if(String(info?.user_info?.auth)!=='1')throw Error('Identifiants refusés');
    profile.expDate=info.user_info.exp_date||null;
    if(!store.set('profile',profile))throw Error('Impossible d’enregistrer le profil sur ce téléphone');
    saveProfile();
    status('Compte validé. Ouverture de l’application…',false);
    data=blank();
    openApp();
    showStartupSync('Synchronisation du catalogue…');
    try{
      await loadCatalog(progress=>showStartupSync(progress));
      renderHome();
      if(['live','movies','series'].includes(currentPage))showPage(currentPage,false);
      hideStartupSync();
      toast('Catalogue prêt');
    }catch(syncError){
      console.error(syncError);
      hideStartupSync();
      toast('Application ouverte, mais le catalogue n’a pas pu être chargé');
    }
    status('',false)
  }catch(e){
    profile=null;
    store.del('profile');
    setHidden($('#app'),true);
    setHidden($('#auth'),false);
    const msg=e?.message||'vérifie les informations.';
    status('Connexion impossible : '+msg)
  }finally{$('#connectBtn').disabled=false}
}
function showStartupSync(text='Chargement du catalogue…'){
  const box=$('#startupSync'),label=$('#startupSyncText');
  if(label)label.textContent=text;
  setHidden(box,false)
}
function hideStartupSync(){setHidden($('#startupSync'),true)}
async function loadCatalog(onProgress=()=>{}){
  toast('Synchronisation du catalogue…');
  onProgress('Chargement des catégories…');
  const categoryResults=await Promise.allSettled([
    api('get_live_categories'),api('get_vod_categories'),api('get_series_categories')
  ]);
  const arr=(result)=>result.status==='fulfilled'&&Array.isArray(result.value)?result.value:[];
  data.liveCats=arr(categoryResults[0]);
  data.movieCats=arr(categoryResults[1]);
  data.seriesCats=arr(categoryResults[2]);

  onProgress('Chargement des chaînes en direct…');
  try{const value=await api('get_live_streams');data.live=Array.isArray(value)?value:[]}catch{data.live=[]}
  onProgress(`Chaînes chargées : ${data.live.length}. Chargement des films…`);
  await new Promise(r=>setTimeout(r,0));
  try{const value=await api('get_vod_streams');data.movies=Array.isArray(value)?value:[]}catch{data.movies=[]}
  onProgress(`Films chargés : ${data.movies.length}. Chargement des séries…`);
  await new Promise(r=>setTimeout(r,0));
  try{const value=await api('get_series');data.series=Array.isArray(value)?value:[]}catch{data.series=[]}
  onProgress(`${data.live.length+data.movies.length+data.series.length} contenus prêts.`);
  // Important : le catalogue complet reste en mémoire et n'est jamais écrit dans localStorage.
  // Cela évite définitivement QuotaExceededError sur les gros abonnements Xtream.
  return data
}
function updateProfileUI(){const name=profile?.name||'Profil',initial=(name[0]||'S').toUpperCase();$('#profileAvatar').textContent=initial;$('#profileLabel').textContent=name;$('#settingsAvatar').textContent=initial;$('#settingsName').textContent=name;$('#settingsServer').textContent=profile?.server||'Mode démo';$('#connectionLabel').textContent=profile?.demo?'Mode démo':'Connecté'}
function openApp(){setHidden($('#auth'),true);setHidden($('#app'),false);if(profile?.demo)data=demo;updateProfileUI();applySettings();renderProfiles();renderHome();showPage('home',false)}
function empty(t){return `<div class="empty">${esc(t)}</div>`}
function isFavorite(type,id){return favorites.some(f=>f.type===type&&String(f.id)===String(id))}
function toggleFavorite(type,item){const id=itemId(type,item),idx=favorites.findIndex(f=>f.type===type&&String(f.id)===id);if(idx>=0){favorites.splice(idx,1);toast('Retiré de Ma liste')}else{favorites.unshift({type,id,item});favorites=favorites.slice(0,400);toast('Ajouté à Ma liste')}store.set('favorites',favorites);renderFavorites();buzz()}
function getProgress(type,item,episode){const p=positions[keyOf(type,item,episode)];return p?.duration?Math.min(100,Math.round(p.time/p.duration*100)):null}
function mediaCard(item,type,opts={}){const id=itemId(type,item),img=imageOf(item,type),fav=isFavorite(type,id),progress=opts.progress??null;return `<article class="media-card" data-type="${type}" data-id="${id}"><div class="open-card" role="button" tabindex="0"><div class="media-thumb" style="background-image:url('${esc(img)}')">${type==='live'?'<span class="card-badge live">DIRECT</span>':item.rating?`<span class="card-badge">★ ${esc(item.rating)}</span>`:''}<button type="button" class="favorite-mini ${fav?'active':''}" data-fav="1">${icon('i-heart')}</button></div><h3>${esc(titleOf(item))}</h3><p>${type==='live'?'En direct':type==='movies'?'Film':'Série'}</p>${progress!==null?`<div class="progress-bar"><i style="width:${Math.max(3,progress)}%"></i></div>`:''}</div></article>`}
function bindCards(root){if(!root)return;root.onclick=e=>{const card=e.target.closest('.media-card');if(!card)return;const type=card.dataset.type,item=findItem(type,card.dataset.id);if(!item)return;if(e.target.closest('[data-fav]')){e.preventDefault();e.stopPropagation();toggleFavorite(type,item);e.target.closest('[data-fav]').classList.toggle('active',isFavorite(type,itemId(type,item)));return}openDetails(item,type)}}
function findItem(type,id){const list=type==='live'?data.live:type==='movies'?data.movies:data.series;return list.find(x=>String(type==='series'?x.series_id:x.stream_id)===String(id))}
function makeHeroItems(){return [...data.movies.slice(0,4).map(item=>({item,type:'movies'})),...data.series.slice(0,3).map(item=>({item,type:'series'})),...data.live.slice(0,2).map(item=>({item,type:'live'}))]}
function renderHero(index=0){if(!heroItems.length)return;heroIndex=(index+heroItems.length)%heroItems.length;const {item,type}=heroItems[heroIndex];currentSelection={item,type};$('#heroTitle').textContent=titleOf(item);$('#heroDescription').textContent=item.plot||item.description||'Disponible dans ton catalogue.';$('#heroType').textContent=type==='live'?'EN DIRECT':type==='movies'?'FILM':'SÉRIE';$('#heroMeta').textContent=[item.genre,item.releaseDate||item.releasedate,item.rating?'★ '+item.rating:''].filter(Boolean).join(' • ')||'Sélection du moment';const img=imageOf(item,type);$('#heroImage').style.backgroundImage=img?`url('${img}')`:'linear-gradient(145deg,#1b2e64,#2b1756)';$$('#heroDots button').forEach((b,i)=>b.classList.toggle('active',i===heroIndex))}
function startHero(){clearInterval(heroTimer);heroTimer=setInterval(()=>renderHero(heroIndex+1),9000)}
function renderHome(){heroItems=makeHeroItems();$('#heroDots').innerHTML=heroItems.map((_,i)=>`<button data-hero="${i}" class="${i===0?'active':''}"></button>`).join('');if(heroItems.length)renderHero(0);startHero();$('#liveTotal').textContent=`${data.live.length} chaîne${data.live.length>1?'s':''}`;$('#movieTotal').textContent=`${data.movies.length} film${data.movies.length>1?'s':''}`;$('#seriesTotal').textContent=`${data.series.length} série${data.series.length>1?'s':''}`;$('#liveRail').innerHTML=data.live.slice(0,12).map(x=>mediaCard(x,'live')).join('')||empty('Aucune chaîne');$('#movieRail').innerHTML=data.movies.slice(0,12).map(x=>mediaCard(x,'movies')).join('')||empty('Aucun film');$('#seriesRail').innerHTML=data.series.slice(0,12).map(x=>mediaCard(x,'series')).join('')||empty('Aucune série');bindCards($('#liveRail'));bindCards($('#movieRail'));bindCards($('#seriesRail'));renderContinue();renderFavorites()}
function renderContinue(){const list=watchHistory.slice(0,12);setHidden($('#continueSection'),!list.length);$('#continueRail').innerHTML=list.map(h=>mediaCard(h.item,h.type,{progress:getProgress(h.type,h.item,h.episode)})).join('');bindCards($('#continueRail'))}
function renderFavorites(){const grid=$('#favoritesGrid');grid.innerHTML=favorites.map(f=>mediaCard(f.item,f.type)).join('')||empty('Ta liste est vide. Ajoute un cœur sur un contenu.');bindCards(grid)}
function setActiveNav(page){$$('.nav-item').forEach(b=>b.classList.toggle('active',b.dataset.page===page))}
function showPage(page,push=true){currentPage=page;$$('.page').forEach(p=>p.classList.remove('active'));if(page==='home'){$('#homePage').classList.add('active');setActiveNav('home')}else if(page==='favorites'){$('#favoritesPage').classList.add('active');renderFavorites();setActiveNav('favorites')}else if(page==='settings'){$('#settingsPage').classList.add('active');renderProfiles();setActiveNav('settings')}else{openCatalog(page);setActiveNav(page==='live'?'live':'')}if(push)pushState('page',page);window.scrollTo({top:0,behavior:'smooth'})}
function openCatalog(type){currentCatalogType=type;$('#catalogPage').classList.add('active');const cfg=type==='live'?['Direct',data.live,data.liveCats]:type==='movies'?['Films',data.movies,data.movieCats]:['Séries',data.series,data.seriesCats];catalog=cfg[1];visible=50;$('#catalogTitle').textContent=cfg[0];$('#catalogCount').textContent=`${catalog.length} contenu${catalog.length>1?'s':''}`;$('#categoryBar').innerHTML=`<button class="category-chip active" data-cat="all">Tout</button>`+cfg[2].map(c=>`<button class="category-chip" data-cat="${c.category_id}">${esc(c.category_name)}</button>`).join('');renderCatalog()}
function renderCatalog(){const grid=$('#catalogGrid');grid.className='catalog-grid '+(currentCatalogType==='live'?'':'poster-grid');grid.innerHTML=catalog.slice(0,visible).map(x=>mediaCard(x,currentCatalogType)).join('')||empty('Aucun contenu dans cette catégorie.');bindCards(grid);setHidden($('#loadMore'),visible>=catalog.length)}
function pushState(kind,value){try{history.pushState({kind,value},'')}catch{}}
function closeTopLayer(){if(!$('#castSheet').classList.contains('hidden')){closeCastSheet();return true}if(!$('#playerOverlay').classList.contains('hidden')){closePlayer();return true}if(!$('#detailsSheet').classList.contains('hidden')){closeDetails();return true}if(!$('#searchOverlay').classList.contains('hidden')){closeSearch();return true}if(currentPage!=='home'){showPage('home',false);return true}return false}
function openSearch(){setHidden($('#searchOverlay'),false);$('#searchInput').value='';$('#searchResults').innerHTML='';setHidden($('#searchHint'),false);setTimeout(()=>$('#searchInput').focus(),120);pushState('overlay','search')}
function closeSearch(){setHidden($('#searchOverlay'),true);$('#searchInput').blur()}
function doSearch(q){const s=q.trim().toLowerCase();if(s.length<2){$('#searchResults').innerHTML='';setHidden($('#searchHint'),false);return}const res=[...data.live.map(item=>({item,type:'live'})),...data.movies.map(item=>({item,type:'movies'})),...data.series.map(item=>({item,type:'series'}))].filter(x=>titleOf(x.item).toLowerCase().includes(s)).slice(0,100);setHidden($('#searchHint'),true);const grid=$('#searchResults');grid.innerHTML=res.map(x=>mediaCard(x.item,x.type)).join('')||empty('Aucun résultat');bindCards(grid)}
async function openDetails(item,type){currentSelection={item,type};currentEpisode=null;$('#detailType').textContent=type==='live'?'EN DIRECT':type==='movies'?'FILM':'SÉRIE';$('#detailTitle').textContent=titleOf(item);$('#detailMeta').textContent=[item.genre,item.releaseDate||item.releasedate,item.rating?'★ '+item.rating:''].filter(Boolean).join(' • ');$('#detailPlot').textContent=item.plot||item.description||'Aucune description disponible.';const img=imageOf(item,type);$('#detailBackdrop').style.backgroundImage=img?`url('${img}')`:'linear-gradient(145deg,#25305c,#5a246b)';$('#detailFavorite').classList.toggle('active',isFavorite(type,itemId(type,item)));$('#episodesBlock').innerHTML='';setHidden($('#detailsSheet'),false);pushState('overlay','details');if(type==='series'){if(profile.demo){renderEpisodes(item,[{id:1,title:'Épisode de démonstration',container_extension:'mp4',info:{duration:'02:00',movie_image:img}}]);return}$('#episodesBlock').innerHTML='<div class="empty">Chargement des épisodes…</div>';try{const info=await api('get_series_info',{series_id:item.series_id});renderEpisodes(item,Object.values(info.episodes||{}).flat())}catch{$('#episodesBlock').innerHTML='<div class="empty">Impossible de charger les épisodes.</div>'}}}
function renderEpisodes(item,eps){$('#episodesBlock').innerHTML=eps.length?'<h2>Épisodes</h2>'+eps.map((e,i)=>`<button class="episode-row" data-ep="${i}"><div class="episode-image" style="background-image:url('${esc(e.info?.movie_image||imageOf(item,'series'))}')"></div><div><b>${esc(e.title||'Épisode '+(i+1))}</b><small>${esc(e.info?.duration||'')}</small></div>${icon('i-chevron')}</button>`).join(''):'<div class="empty">Aucun épisode disponible</div>';$('#episodesBlock').onclick=e=>{const row=e.target.closest('[data-ep]');if(!row)return;currentEpisode=eps[+row.dataset.ep];play(item,'series',currentEpisode)}}
function closeDetails(){setHidden($('#detailsSheet'),true);$('#detailSheet').style.transform=''}
function streamUrl(item,type,episode){if(profile.demo){if(type==='live')return'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';if(type==='movies')return'https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4';return'https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4'}if(type==='live')return`${profile.server}/live/${encodeURIComponent(profile.username)}/${encodeURIComponent(profile.password)}/${item.stream_id}.${settings.liveFormat||'m3u8'}`;if(type==='movies')return`${profile.server}/movie/${encodeURIComponent(profile.username)}/${encodeURIComponent(profile.password)}/${item.stream_id}.${item.container_extension||'mp4'}`;if(episode)return`${profile.server}/series/${encodeURIComponent(profile.username)}/${encodeURIComponent(profile.password)}/${episode.id}.${episode.container_extension||'mp4'}`;return''}
function addHistory(item,type,episode){const key=keyOf(type,item,episode);watchHistory=[{item,type,episode,key,date:Date.now()},...watchHistory.filter(h=>h.key!==key)].slice(0,50);store.set('history',watchHistory)}
function destroyPlayback(){if(hls){hls.destroy();hls=null}if(dash){dash.reset();dash=null}}
function showVideoError(){setHidden($('#videoLoader'),true);setHidden($('#videoError'),false);saveDiagnostic('webview_video_error',{attemptedMime:castMime(lastStreamUrl),hlsJs:!!window.Hls},true);setHidden($('#centerPlay'),true)}
function startPlayback(url){lastStreamUrl=url;setHidden($('#remoteCastOverlay'),true);const v=$('#video');destroyPlayback();v.pause();v.removeAttribute('src');v.load();setHidden($('#videoError'),true);setHidden($('#videoLoader'),false);setHidden($('#centerPlay'),true);const ready=()=>{setHidden($('#videoLoader'),true);if(settings.autoplay)v.play().catch(()=>setHidden($('#centerPlay'),false));resumePlayback(v)};const ext=(url.split('?')[0].split('.').pop()||'').toLowerCase();if((ext==='m3u8'||url.includes('.m3u8'))&&window.Hls?.isSupported()){hls=new Hls({enableWorker:true,lowLatencyMode:true,maxBufferLength:40});hls.loadSource(url);hls.attachMedia(v);hls.on(Hls.Events.MANIFEST_PARSED,ready);hls.on(Hls.Events.ERROR,(_,d)=>{if(d.fatal)showVideoError()})}else if(ext==='mpd'&&window.dashjs){dash=dashjs.MediaPlayer().create();dash.initialize(v,url,!!settings.autoplay);dash.on('streamInitialized',ready);dash.on('error',showVideoError)}else{v.src=url;v.oncanplay=ready;v.onerror=showVideoError;v.load()}}
function resumePlayback(v){if(!settings.resume||!currentSelection||currentSelection.type==='live')return;const p=positions[keyOf(currentSelection.type,currentSelection.item,currentEpisode)];if(!p||p.time<10||p.time>p.duration-12)return;const apply=()=>{try{v.currentTime=p.time;$('#resumeToast span').textContent=`Reprise à ${formatTime(p.time)}`;setHidden($('#resumeToast'),false);setTimeout(()=>setHidden($('#resumeToast'),true),2300)}catch{}};v.readyState>=1?apply():v.addEventListener('loadedmetadata',apply,{once:true})}
function savePosition(force=false){const v=$('#video');if(!settings.resume||!currentSelection||currentSelection.type==='live'||!isFinite(v.duration)||!v.duration)return;if(!force&&v.currentTime<4)return;positions[keyOf(currentSelection.type,currentSelection.item,currentEpisode)]={time:v.currentTime,duration:v.duration,updated:Date.now()};store.set('positions',positions)}
function formatTime(sec){sec=Math.floor(sec||0);const h=Math.floor(sec/3600),m=Math.floor(sec%3600/60),s=sec%60;return h?`${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`:`${m}:${String(s).padStart(2,'0')}`}
async function play(item,type,episode=null){const url=streamUrl(item,type,episode);if(!url){toast(type==='series'?'Choisis un épisode':'Flux indisponible');return}currentSelection={item,type};currentEpisode=episode;lastStreamUrl=url;addHistory(item,type,episode);closeDetails();if(launchNativePlayer(item,type,episode,url)){toast('Ouverture du lecteur natif…');return}$('#playingTitle').textContent=episode?.title||titleOf(item);$('#playingSubtitle').textContent=type==='live'?'En direct':type==='movies'?'Film':'Série';$('#playerNowTitle').textContent=episode?.title||titleOf(item);$('#playerNowMeta').textContent=[item.genre,item.rating?'★ '+item.rating:''].filter(Boolean).join(' • ');setHidden($('#liveBadge'),type!=='live');setHidden($('#epgSection'),type!=='live');setHidden($('#playerOverlay'),false);pushState('overlay','player');if(settings.external){openExternalPayload(nativePlayPayload(item,type,episode,url));return}if(castState==='connected'){try{const payload=currentCastPayload();if(castBridge())await castViaNative(payload);else await castViaWeb(payload);setHidden($('#remoteCastOverlay'),false)}catch{startPlayback(url)}}else startPlayback(url);if(type==='live')loadEpg(item)}
function closePlayer(){savePosition(true);destroyPlayback();const v=$('#video');v.pause();v.removeAttribute('src');v.load();setHidden($('#playerOverlay'),true);renderContinue()}
async function loadEpg(item){$('#epgList').innerHTML='<div class="empty">Chargement du programme…</div>';if(profile.demo){$('#epgList').innerHTML='<article class="epg-item"><time>Maintenant</time><b>Programme de démonstration</b><p>Le guide TV apparaîtra ici avec ton service.</p><div class="epg-progress"><i style="width:45%"></i></div></article>';return}try{const r=await api('get_short_epg',{stream_id:item.stream_id,limit:8}),list=r.epg_listings||[];$('#epgList').innerHTML=list.map((x,i)=>{const progress=i===0?epgProgress(x.start_timestamp,x.stop_timestamp):0;return`<article class="epg-item"><time>${fmtEpg(x.start_timestamp,x.stop_timestamp)}</time><b>${esc(decode64(x.title))}</b><p>${esc(decode64(x.description))}</p>${i===0?`<div class="epg-progress"><i style="width:${progress}%"></i></div>`:''}</article>`}).join('')||'<div class="empty">Programme indisponible</div>'}catch{$('#epgList').innerHTML='<div class="empty">Programme indisponible</div>'}}
function decode64(s){try{return decodeURIComponent(escape(atob(s||'')))}catch{return s||''}}
function fmtEpg(a,b){const f=x=>new Date(Number(x)*1000).toLocaleTimeString('fr-FR',{hour:'2-digit',minute:'2-digit'});return`${f(a)} – ${f(b)}`}
function epgProgress(a,b){const n=Date.now()/1000,start=+a,end=+b;return Math.max(0,Math.min(100,(n-start)/(end-start)*100))}
function applySettings(){$('#autoplaySetting').checked=!!settings.autoplay;$('#resumeSetting').checked=!!settings.resume;$('#externalSetting').checked=!!settings.external;$('#liveFormat').value=settings.liveFormat||'m3u8'}
function logout(){store.del('profile');profile=null;setHidden($('#app'),true);setHidden($('#auth'),false);renderProfiles();window.scrollTo(0,0)}
let rememberLoginTimer=null;
function rememberLoginForm(){
  clearTimeout(rememberLoginTimer);
  rememberLoginTimer=setTimeout(()=>{
    const remembered={
      name:$('#profileName')?.value.trim()||'Mon profil',
      server:normServer($('#server')?.value||''),
      username:$('#username')?.value.trim()||'',
      password:$('#password')?.value||''
    };
    if(!remembered.server&&!remembered.username&&!remembered.password)return;
    store.set('lastCredentials',remembered);
    try{window.NativeAndroid?.saveSecret?.('lastCredentials',JSON.stringify(remembered))}catch{}
  },180)
}
['profileName','server','username','password'].forEach(id=>$('#'+id)?.addEventListener('input',rememberLoginForm));
$('#loginForm').onsubmit=e=>{e.preventDefault();rememberLoginForm();connectWith({name:$('#profileName').value.trim()||'Mon profil',server:normServer($('#server').value),username:$('#username').value.trim(),password:$('#password').value,demo:false})};
$('#togglePassword').onclick=()=>{$('#password').type=$('#password').type==='password'?'text':'password'};
$('#demoBtn').onclick=()=>{profile={name:'Démo',server:'',username:'',password:'',demo:true};store.set('profile',profile);openApp()};
$('#savedProfiles').onclick=e=>{const b=e.target.closest('[data-connect]');if(b)connectWith(profiles[+b.dataset.connect])};
$('#deleteAllProfiles').onclick=()=>{profiles=[];store.set('profiles',profiles);renderProfiles();toast('Profils effacés')};
$('#profileManager').onclick=e=>{const b=e.target.closest('[data-delete]');if(!b)return;profiles.splice(+b.dataset.delete,1);store.set('profiles',profiles);renderProfiles();toast('Profil supprimé')};
$$('[data-open]').forEach(b=>b.onclick=()=>showPage(b.dataset.open));
$$('.nav-item').forEach(b=>b.onclick=()=>showPage(b.dataset.page));
$('#navSearch').onclick=$('#openSearch').onclick=$('#catalogSearch').onclick=openSearch;
$('#searchBack').onclick=closeSearch;$('#clearSearch').onclick=()=>{$('#searchInput').value='';doSearch('');$('#searchInput').focus()};$('#searchInput').oninput=e=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>doSearch(e.target.value),120)};
$('#catalogBack').onclick=()=>showPage('home');
$('#categoryBar').onclick=e=>{const c=e.target.closest('.category-chip');if(!c)return;$$('.category-chip').forEach(x=>x.classList.toggle('active',x===c));const all=currentCatalogType==='live'?data.live:currentCatalogType==='movies'?data.movies:data.series;catalog=c.dataset.cat==='all'?all:all.filter(x=>String(x.category_id)===c.dataset.cat);visible=50;$('#catalogCount').textContent=`${catalog.length} contenu${catalog.length>1?'s':''}`;renderCatalog()};
$('#heroDots').onclick=e=>{const b=e.target.closest('[data-hero]');if(b){renderHero(+b.dataset.hero);startHero()}};$('#heroPlay').onclick=()=>currentSelection&&play(currentSelection.item,currentSelection.type);$('#heroDetails').onclick=()=>currentSelection&&openDetails(currentSelection.item,currentSelection.type);
$('#closeDetails').onclick=$('#sheetBackdrop').onclick=closeDetails;$('#detailPlay').onclick=()=>currentSelection&&play(currentSelection.item,currentSelection.type,currentEpisode);$('#detailFavorite').onclick=()=>{if(!currentSelection)return;toggleFavorite(currentSelection.type,currentSelection.item);$('#detailFavorite').classList.toggle('active',isFavorite(currentSelection.type,itemId(currentSelection.type,currentSelection.item)))};
$('#detailSheet').addEventListener('pointerdown',e=>{if(e.target.closest('button,input,select'))return;dragStartY=e.clientY});$('#detailSheet').addEventListener('pointermove',e=>{if(!dragStartY)return;const d=Math.max(0,e.clientY-dragStartY);if(d<180)$('#detailSheet').style.transform=`translateY(${d}px)`});$('#detailSheet').addEventListener('pointerup',e=>{if(!dragStartY)return;const d=e.clientY-dragStartY;dragStartY=0;d>110?closeDetails():$('#detailSheet').style.transform=''});
$('#closePlayer').onclick=closePlayer;$('#centerPlay').onclick=()=>$('#video').play();$('#togglePlay').onclick=toggleTransport;$('#rewind').onclick=()=>seekTransport(-10);$('#forward').onclick=()=>seekTransport(10);$('#pipButton').onclick=async()=>{try{if(document.pictureInPictureEnabled)await $('#video').requestPictureInPicture();else throw Error()}catch{toast('Image dans l’image indisponible')}};$('#externalButton').onclick=$('#openExternalError').onclick=openExternalPlayer;$('#downloadDiagnostic').onclick=()=>saveDiagnostic('manual_after_playback_error',{},true);$('#playerFavorite').onclick=()=>currentSelection&&toggleFavorite(currentSelection.type,currentSelection.item);
$('#headerCastButton').onclick=$('#settingsCastButton').onclick=$('#playerCastButton').onclick=$('#castToolButton').onclick=openCastSheet;$('#screenMirrorButton').onclick=$('#castScreenButton').onclick=openAndroidCastSettings;$('#closeCast').onclick=$('#castBackdrop').onclick=closeCastSheet;$('#castDirectButton').onclick=startCast;$('#castShareButton').onclick=shareToTvApp;$('#stopCastButton').onclick=$('#remoteStopButton').onclick=stopCasting;
$('#video').addEventListener('play',()=>{setHidden($('#centerPlay'),true);$('#togglePlay').innerHTML=icon('i-pause')});$('#video').addEventListener('pause',()=>{setHidden($('#centerPlay'),false);$('#togglePlay').innerHTML=icon('i-play')});$('#video').addEventListener('timeupdate',()=>savePosition(false));$('#video').addEventListener('ended',()=>savePosition(true));
$('#syncButton').onclick=$('#refreshCatalog').onclick=async()=>{if(profile?.demo)return toast('Mode démo');try{await loadCatalog();renderHome();if(['live','movies','series'].includes(currentPage))showPage(currentPage,false);toast('Catalogue actualisé')}catch{toast('Actualisation impossible')}};
$('#profileButton').onclick=()=>showPage('settings');$('#changeProfile').onclick=()=>{setHidden($('#app'),true);setHidden($('#auth'),false);renderProfiles()};$('#logoutButton').onclick=logout;$('#clearHistory').onclick=()=>{watchHistory=[];store.set('history',watchHistory);renderContinue();toast('Historique effacé')};$('#clearPositions').onclick=()=>{positions={};store.set('positions',positions);toast('Reprises réinitialisées')};
[['autoplaySetting','autoplay'],['resumeSetting','resume'],['externalSetting','external']].forEach(([id,key])=>$('#'+id).onchange=e=>{settings[key]=e.target.checked;store.set('settings',settings)});$('#liveFormat').onchange=e=>{settings.liveFormat=e.target.value;store.set('settings',settings)};
window.addEventListener('scroll',()=>{if($('#catalogPage').classList.contains('active')&&innerHeight+scrollY>document.body.offsetHeight-500&&visible<catalog.length){visible+=50;renderCatalog()}});
window.addEventListener('popstate',()=>closeTopLayer());window.addEventListener('online',()=>setHidden($('#offlineBanner'),true));window.addEventListener('offline',()=>setHidden($('#offlineBanner'),false));setHidden($('#offlineBanner'),navigator.onLine);
renderProfiles();applySettings();initCastSdk();updateCastPanel();
function prefillLoginForm(){
  let saved=store.get('lastCredentials',null);
  if(!saved){
    try{const raw=window.NativeAndroid?.loadSecret?.('lastCredentials');if(raw)saved=JSON.parse(raw)}catch{}
  }
  const source=profile&&!profile.demo?profile:saved;
  if(!source)return;
  if($('#profileName'))$('#profileName').value=source.name||'Mon profil';
  if($('#server'))$('#server').value=source.server||'';
  if($('#username'))$('#username').value=source.username||'';
  if($('#password'))$('#password').value=source.password||'';
}
async function bootstrapApp(){
  try{clearTimeout(window.__streamboxBootFallback)}catch{}
  const splash=$('#splash');
  splash?.classList.add('hide');
  setTimeout(()=>setHidden(splash,true),420);

  if(!profile){
    setHidden($('#auth'),false);
    prefillLoginForm();
    renderProfiles();
    return;
  }

  if(profile.demo){
    data=demo;
    openApp();
    return;
  }

  // Auto-connexion : ouverture immédiate des menus, sans attendre le gros catalogue.
  data=blank();
  openApp();
  showStartupSync('Connexion automatique au serveur…');
  try{
    const info=await api('');
    if(String(info?.user_info?.auth)!=='1')throw Error('Identifiants expirés ou refusés');
    profile.expDate=info.user_info.exp_date||profile.expDate||null;
    profile.autoLogin=true;
    store.set('profile',profile);
    showStartupSync('Compte reconnu. Chargement des catégories…');
    await loadCatalog(progress=>showStartupSync(progress));
    renderHome();
    if(['live','movies','series'].includes(currentPage))showPage(currentPage,false);
    hideStartupSync();
    toast('Connexion automatique réussie');
  }catch(e){
    console.error('Auto-login',e);
    hideStartupSync();
    store.del('profile');
    profile=null;
    setHidden($('#app'),true);
    setHidden($('#auth'),false);
    prefillLoginForm();
    status('La connexion automatique a échoué. Les identifiants restent enregistrés pour réessayer.')
  }
}

// Le démarrage est lancé même si une fonction secondaire rencontre une erreur.
window.addEventListener('DOMContentLoaded',()=>setTimeout(bootstrapApp,250),{once:true});
window.addEventListener('error',event=>{
  console.error('Erreur application',event.error||event.message);
  const splash=$('#splash');
  if(splash&&!splash.classList.contains('hidden')){
    setHidden(splash,true);
    if(!profile)setHidden($('#auth'),false)
  }
});

if ('serviceWorker' in navigator && /^https?:$/.test(location.protocol)) {
  navigator.serviceWorker.register('service-worker.js',{updateViaCache:'none'})
    .then(reg=>reg.update().catch(()=>{}))
    .catch(()=>{});
}

window.addEventListener('error',e=>{if(String(e?.message||'').includes('Script error'))return;saveDiagnostic('javascript_error',{message:e.message,filename:e.filename,lineno:e.lineno,colno:e.colno,stack:e.error?.stack||null},false)});
window.addEventListener('unhandledrejection',e=>saveDiagnostic('unhandled_promise_rejection',{reason:String(e.reason?.stack||e.reason||'unknown')},false));
