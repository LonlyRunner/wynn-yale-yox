<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { api, ApiError } from './api'

type Lang = 'zh' | 'en'
const route = useRoute(), router = useRouter()
const lang = ref<Lang>((localStorage.getItem('wynn-lang') as Lang) || 'zh')
const dark = computed(() => ['home','studio','games','game','login'].includes(String(route.name)))
const mobileOpen = ref(false), catTalk = ref(false), busy = ref(false), notice = ref('')
const heroVideo = ref<HTMLVideoElement>(), soundOn = ref(false)
const text = (zh: string, en: string) => lang.value === 'zh' ? zh : en
const toggleLang = () => { lang.value = lang.value === 'zh' ? 'en' : 'zh'; localStorage.setItem('wynn-lang', lang.value) }
const catHello = () => { catTalk.value = true; setTimeout(() => catTalk.value = false, 1800) }
async function toggleSound() {
  const video = heroVideo.value
  if (!video) return
  const next = !soundOn.value
  video.muted = !next
  if (next && video.paused) {
    try { await video.play() }
    catch { video.muted = true; return }
  }
  soundOn.value = next
}
const catOffsets = ref<Record<string, { x: number; y: number }>>({})
const catDragging = ref(false), catDragged = ref(false)
const catKey = computed(() => String(route.name || 'cat'))
const catStyle = computed(() => {
  const { x = 0, y = 0 } = catOffsets.value[catKey.value] || {}
  return { transform: `translate3d(${x}px, ${y}px, 0)` }
})
let catDrag: { pointerId: number; x: number; y: number; offsetX: number; offsetY: number; rect: DOMRect; bounds: DOMRect } | undefined
function startCatDrag(event: PointerEvent) {
  if (event.button !== 0) return
  const target = event.currentTarget as HTMLElement
  const offset = catOffsets.value[catKey.value] || { x: 0, y: 0 }
  const bounds = (target.closest('.hero, .about') as HTMLElement || document.documentElement).getBoundingClientRect()
  catDrag = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, offsetX: offset.x, offsetY: offset.y, rect: target.getBoundingClientRect(), bounds }
  catDragging.value = true
  catDragged.value = false
  target.setPointerCapture(event.pointerId)
}
function moveCat(event: PointerEvent) {
  if (!catDrag || event.pointerId !== catDrag.pointerId) return
  const dx = event.clientX - catDrag.x, dy = event.clientY - catDrag.y
  if (Math.hypot(dx, dy) > 4) catDragged.value = true
  const edge = 8
  const minX = Math.max(edge, catDrag.bounds.left + edge) - catDrag.rect.left
  const maxX = Math.min(innerWidth - edge, catDrag.bounds.right - edge) - catDrag.rect.right
  const minY = Math.max(edge, catDrag.bounds.top + edge) - catDrag.rect.top
  const maxY = Math.min(innerHeight - edge, catDrag.bounds.bottom - edge) - catDrag.rect.bottom
  catOffsets.value[catKey.value] = {
    x: catDrag.offsetX + Math.min(Math.max(dx, minX), maxX),
    y: catDrag.offsetY + Math.min(Math.max(dy, minY), maxY),
  }
}
function endCatDrag(event: PointerEvent) {
  if (!catDrag || event.pointerId !== catDrag.pointerId) return
  catDrag = undefined
  catDragging.value = false
}
function catClick() {
  if (catDragged.value) { catDragged.value = false; return }
  catHello()
}
type PostCard = { id?:number; slug:string; tag:string; zh:string; en:string; date:string; summaryZh?:string; summaryEn?:string; contentZh?:string; contentEn?:string; tags?:string; coverUrl?:string }
type GalleryItem = { id?:number; objectKey?:string; titleZh:string; titleEn:string; mediaType:string; url:string; promptZh?:string; promptEn?:string }
type AiJob = { id:number; provider:string; type:string; prompt:string; status:string; resultUrl:string; downloadUrl?:string; error:string; resolution?:string; duration?:number; createdAt?:string }
type Profile = { displayName:string; realName:string; cityZh:string; cityEn:string; email:string; github:string; gitee:string; xianyu:string; wechat:string; bioZh:string; bioEn:string }
type AdminPost = { id?:number; slug:string; titleZh:string; titleEn:string; category:string; tags:string; summaryZh:string; summaryEn:string; contentZh:string; contentEn:string; coverObjectKey:string; published:boolean }
type AdminComment = { id:number; postTitle:string; author:string; email:string; content:string; approved:boolean; createdAt:string }

const fallbackPosts:PostCard[] = [
  { slug:'reliable-agent', tag:'AI ENGINEERING', zh:'从一次对话到一个可靠的 Agent', en:'From a Conversation to a Reliable Agent', date:'2026.06.04' },
  { slug:'model-routing', tag:'SPRING AI', zh:'多模型路由的简单实现', en:'A Simple Multi-model Router', date:'2026.05.26' },
  { slug:'context', tag:'JAVA', zh:'并发任务中的上下文传递', en:'Context Propagation in Concurrent Tasks', date:'2026.05.18' },
]
const posts = ref<PostCard[]>(fallbackPosts)
const blogSearch = ref('')
const currentPost = computed(()=>posts.value.find(post=>post.slug===String(route.params.slug))||posts.value[0])
const comments = ref<Array<{id:number;author:string;content:string;createdAt:string}>>([])
const commentForm = ref({author:'',email:'',content:''}), commentNotice = ref('')
const profile = ref<Profile>({displayName:'Lonely__Runner',realName:'Wang Yuan',cityZh:'洛阳',cityEn:'Luoyang',email:'wyy048003@gamil.com',github:'https://github.com/LonlyRunner',gitee:'https://gitee.com/q7531',xianyu:'https://m.tb.cn/h.8uafqmy?tk=UTlBT9DjXYY',wechat:'WangYuan_0425_Taurus',bioZh:'全栈开发者与 AI 应用实践者，专注 Java、Spring 与智能产品，也用影像记录技术之外的灵感。',bioEn:'Full-stack developer and applied AI builder focused on Java, Spring, intelligent products, and visual stories beyond code.'})
const fallbackGallery:GalleryItem[] = [
  ['绯色旅人','Crimson Traveler','fantasy-red-01.png','古风少女回眸，黑色长发与红色飘带在风中飞扬，金色碎光，浅景深，电影感，高细节幻想写实。','Ancient Chinese fantasy heroine looking back, long black hair and red ribbons flowing in the wind, golden particles, shallow depth of field, cinematic, highly detailed realism.'],
  ['花灯入梦','Lantern Dream','fantasy-peach-01.png','花灯夜色中的古风少女，柔软刺绣轻纱，暖金灯火与粉色花瓣，梦幻散景，电影人像。','Ancient-style woman among lanterns, soft embroidered gauze, warm golden lights and pink petals, dreamy bokeh, cinematic portrait.'],
  ['风起长歌','Song in the Wind','fantasy-red-02.png','红衣古风少女迎风而立，飞扬长发与透明丝绸，明亮逆光，金红色粒子，动态电影构图。','Ancient heroine in red standing in strong wind, flowing hair and translucent silk, bright backlight, gold-red particles, dynamic cinematic composition.'],
  ['琉璃碎光','Crystal Light','fantasy-silver-01.png','女性面部特写，被透明琉璃与冰晶包围，银白冷光、细腻皮肤、梦幻折射和极浅景深。','Close-up female portrait surrounded by transparent crystal and ice, silver light, delicate skin, dreamy refraction, extremely shallow depth of field.'],
  ['夜蓝华章','Midnight Blue','fantasy-blue.png','深蓝华服古风女子，精致金属发饰与蓝宝石，夜色庭院，低调电影光，高贵神秘氛围。','Ancient woman in deep-blue robes, ornate metallic hair jewelry and sapphires, nocturnal courtyard, low-key cinematic lighting, elegant mysterious mood.'],
  ['春灯微醺','Spring Lanterns','fantasy-peach-02.png','暖色花灯中的古风女子，闭眼侧脸，粉色花瓣穿过前景，柔焦、金色轮廓光、浪漫氛围。','Ancient woman with eyes closed among warm lanterns, pink petals crossing the foreground, soft focus, golden rim light, romantic atmosphere.'],
  ['回望','Looking Back','fantasy-red-03.png','红色薄纱环绕的古风女子回望镜头，风吹长发，象牙白背景，金色闪光，轻盈动态感。','Ancient woman looking back through flowing red gauze, windblown hair, ivory background, golden sparkles, airy sense of motion.'],
  ['灯影','Lantern Glow','fantasy-peach-03.png','花灯阁楼里的古风女子，宽袖刺绣长裙，前景花瓣虚化，暖金色照明，广角电影构图。','Ancient woman in a lantern pavilion, wide embroidered sleeves, blurred foreground petals, warm golden lighting, wide cinematic composition.'],
  ['旅者','Traveler','profile-anime-01.png','清爽青年动漫肖像，白衬衫与深色马甲，肩披制服外套，蓝天山景，温柔日光，精致插画。','Clean anime portrait of a young man in a white shirt and dark vest with a jacket over one shoulder, blue sky and mountains, gentle daylight, polished illustration.'],
  ['黑金侧影','Black & Gold','profile-anime-02.png','黑金西装青年半身肖像，米色植物背景，柔和暖光，成熟优雅，精致日系插画。','Half-length portrait of a young man in a black-and-gold suit, beige botanical background, soft warm light, mature elegance, refined anime illustration.'],
  ['小小绅士','Little Gentleman','profile-chibi-01.png','可爱 Q 版短发男孩，白衬衫、深色马甲和条纹领带，白色背景，柔和腮红，干净线稿。','Cute chibi boy with short dark hair, white shirt, dark vest and striped tie, white background, soft blush, clean line art.'],
  ['静坐片刻','A Quiet Moment','profile-chibi-02.png','可爱 Q 版男孩抱膝静坐，黑色休闲西装，暖棕眼睛，奶油白背景，柔软治愈插画。','Cute chibi boy sitting with knees hugged, black casual suit, warm brown eyes, cream background, soft comforting illustration.'],
  ['粉色飞行员','Pink Aviator','avatar-pink.jpg','粉色双马尾 Q 版少女，金色护目镜与棕色制服，明亮表情，奶油背景，可爱动漫头像。','Pink twin-tail chibi girl with golden goggles and brown uniform, cheerful expression, cream background, cute anime avatar.'],
  ['白色回忆','White Memory','avatar-white.jpg','灰白单色动漫少女抱膝坐着，长发与黑色蝴蝶结，朦胧柔光，安静忧郁的头像构图。','Monochrome anime girl sitting with knees hugged, long pale hair and black ribbons, hazy soft light, quiet melancholic avatar composition.']
].map(([titleZh,titleEn,file,promptZh,promptEn])=>({titleZh,titleEn,mediaType:'IMAGE',url:`/media/user-gallery/${file}`,promptZh,promptEn}))
const galleryItems = ref<GalleryItem[]>(fallbackGallery)
const flippedGallery=ref<string|number>(), copiedGallery=ref<string|number>()
const galleryKey=(item:GalleryItem,index:number)=>item.id||item.objectKey||item.url||index
function flipGallery(item:GalleryItem,index:number){const key=galleryKey(item,index);flippedGallery.value=flippedGallery.value===key?undefined:key}
async function copyGalleryPrompt(item:GalleryItem,index:number){const value=text(item.promptZh||'',item.promptEn||'');if(!value)return;try{await navigator.clipboard.writeText(value)}catch{const area=document.createElement('textarea');area.value=value;area.style.position='fixed';area.style.opacity='0';document.body.appendChild(area);area.select();document.execCommand('copy');area.remove()}copiedGallery.value=galleryKey(item,index);setTimeout(()=>copiedGallery.value=undefined,1500)}

const creatorMode=ref<'IMAGE'|'VIDEO'>('IMAGE'), prompt = ref('薄雾笼罩的火山山脊，第一视角低空掠过，电影感，冷色调，自然光。')
const aspectRatio=ref('16:9'), resolution=ref('720p'), duration=ref(5)
const quota=ref({owner:false,imageRemaining:true,videoRemaining:true}), currentJob=ref<AiJob>(), aiHistory=ref<AiJob[]>([])
let aiPollTimer:number|undefined
const quotaText=computed(()=>quota.value.owner?text('管理员不限次数','Unlimited owner access'):text(`访客额度：图片 ${quota.value.imageRemaining?'1':'0'} 次 · 视频 ${quota.value.videoRemaining?'1':'0'} 次`,`Guest allowance: ${quota.value.imageRemaining?'1':'0'} image · ${quota.value.videoRemaining?'1':'0'} video`))
async function loadQuota(){try{quota.value=await api('/ai/quota')}catch{}}
function aiError(error:unknown){if(error instanceof ApiError){try{const body=JSON.parse(error.message);return body.detail||body.message||error.message}catch{return error.message}}return error instanceof Error?error.message:text('生成失败','Generation failed')}
async function pollJob(id:number){clearInterval(aiPollTimer);aiPollTimer=window.setInterval(async()=>{try{const job=await api<AiJob>(`/ai/jobs/${id}`);currentJob.value=job;if(['COMPLETED','FAILED'].includes(job.status)){clearInterval(aiPollTimer);busy.value=false;notice.value=job.error||job.status;await loadQuota()}}catch(error){clearInterval(aiPollTimer);busy.value=false;notice.value=aiError(error)}},4000)}
async function generate(){busy.value=true;notice.value='';currentJob.value=undefined;try{const job=await api<AiJob>('/ai/jobs',{method:'POST',body:JSON.stringify({type:creatorMode.value,prompt:prompt.value,aspectRatio:aspectRatio.value,resolution:resolution.value,duration:duration.value})});currentJob.value=job;aiHistory.value.unshift(job);notice.value=job.error||job.status;if(job.status==='PROCESSING')pollJob(job.id);else{busy.value=false;await loadQuota()}}catch(error){busy.value=false;notice.value=aiError(error);await loadQuota()}}

const username=ref('wynnyaleyox'), password=ref('')
async function login(){busy.value=true;notice.value='';try{await api('/auth/login',{method:'POST',body:JSON.stringify({username:username.value,password:password.value})});sessionStorage.setItem('wynn-auth','1');router.push(String(route.query.redirect||'/admin'))}catch(error){notice.value=error instanceof ApiError&&error.status===0?text('登录服务未启动，请先启动后端','Login service is unavailable. Start the backend first.'):error instanceof ApiError&&(error.status===401||error.status===403)?text('账号或密码错误','Invalid credentials'):text('登录失败，请稍后重试','Sign-in failed. Try again shortly.')}finally{busy.value=false}}
async function logout(){try{await api('/auth/logout',{method:'POST'})}catch{}sessionStorage.removeItem('wynn-auth');router.push('/')}

function markdown(value:string|undefined){return DOMPurify.sanitize(String(marked.parse(value||'')))}
async function loadPosts(query=''){try{const data=await api<Array<Record<string,unknown>>>(`/public/posts${query?`?q=${encodeURIComponent(query)}`:''}`);posts.value=data.map(p=>({id:Number(p.id),slug:String(p.slug),tag:String(p.category||'JOURNAL'),zh:String(p.titleZh),en:String(p.titleEn),date:String(p.createdAt||'').slice(0,10).replaceAll('-','.'),summaryZh:String(p.summaryZh||''),summaryEn:String(p.summaryEn||''),contentZh:String(p.contentZh||''),contentEn:String(p.contentEn||''),tags:String(p.tags||''),coverUrl:String(p.coverUrl||'')}))}catch{}}
async function searchPosts(){await loadPosts(blogSearch.value.trim())}
async function loadArticle(){if(route.name!=='article')return;await loadPosts();try{comments.value=await api(`/public/posts/${route.params.slug}/comments`)}catch{comments.value=[]}}
async function submitComment(){commentNotice.value='';try{await api(`/public/posts/${route.params.slug}/comments`,{method:'POST',body:JSON.stringify(commentForm.value)});commentForm.value={author:'',email:'',content:''};commentNotice.value=text('评论已提交，审核后显示。','Comment submitted for review.')}catch(error){commentNotice.value=aiError(error)}}
async function loadPublic(){loadPosts();api<Profile>('/public/profile').then(v=>profile.value=v).catch(()=>{});api<GalleryItem[]>('/public/media').then(v=>{if(v.length)galleryItems.value=v.map((item,index)=>{const fallback=fallbackGallery.find(local=>item.objectKey?.endsWith(local.url.split('/').pop()||''))||fallbackGallery[index];return{...item,promptZh:item.promptZh||fallback?.promptZh,promptEn:item.promptEn||fallback?.promptEn}})}).catch(()=>{});if(route.name==='studio')loadQuota();if(route.name==='article')loadArticle()}

const adminTab=ref<'overview'|'posts'|'media'|'ai'|'comments'>('overview'), adminPosts=ref<AdminPost[]>([]), adminComments=ref<AdminComment[]>([]), adminJobs=ref<AiJob[]>([])
const emptyPost=():AdminPost=>({slug:'',titleZh:'',titleEn:'',category:'TECH',tags:'',summaryZh:'',summaryEn:'',contentZh:'',contentEn:'',coverObjectKey:'',published:false})
const postForm=ref<AdminPost>(emptyPost()), adminNotice=ref('')
async function loadAdmin(){if(!sessionStorage.getItem('wynn-auth'))return;try{[adminPosts.value,adminComments.value,adminJobs.value]=await Promise.all([api<AdminPost[]>('/admin/posts'),api<AdminComment[]>('/admin/comments'),api<AiJob[]>('/admin/ai/jobs')])}catch{}}
function editPost(post:AdminPost){postForm.value={...post};adminTab.value='posts'}
function resetPost(){postForm.value=emptyPost();adminNotice.value=''}
async function savePost(){adminNotice.value='';try{const method=postForm.value.id?'PUT':'POST',path=postForm.value.id?`/admin/posts/${postForm.value.id}`:'/admin/posts';await api(path,{method,body:JSON.stringify(postForm.value)});adminNotice.value=text('文章已保存','Post saved');resetPost();await loadAdmin()}catch(error){adminNotice.value=aiError(error)}}
async function removePost(id?:number){if(!id)return;await api(`/admin/posts/${id}`,{method:'DELETE'});await loadAdmin()}
async function importDocument(event:Event){const file=(event.target as HTMLInputElement).files?.[0];if(!file)return;const form=new FormData();form.append('file',file);try{const result=await api<{title:string;content:string}>('/admin/posts/import',{method:'POST',body:form,headers:{}});postForm.value.titleZh=postForm.value.titleZh||result.title;postForm.value.titleEn=postForm.value.titleEn||result.title;postForm.value.contentZh=result.content;adminNotice.value=text('文档已解析，可继续编辑','Document parsed and ready to edit')}catch(error){adminNotice.value=aiError(error)}}
async function approveComment(id:number){await api(`/admin/comments/${id}/approve`,{method:'POST'});await loadAdmin()}
async function removeComment(id:number){await api(`/admin/comments/${id}`,{method:'DELETE'});await loadAdmin()}
async function removeJob(id:number){await api(`/admin/ai/jobs/${id}`,{method:'DELETE'});await loadAdmin()}
async function uploadGallery(event:Event){const files=Array.from((event.target as HTMLInputElement).files||[]);adminNotice.value=text('正在上传并由 AI 提取画面特征…','Uploading and extracting visual features with AI…');for(const [index,file] of files.entries()){const key=`gallery/${Date.now()}-${index}-${file.name.replace(/[^a-zA-Z0-9._-]/g,'-')}`;const signed=await api<{url:string}>('/admin/oss/upload-url',{method:'POST',body:JSON.stringify({objectKey:key,contentType:file.type})});await fetch(signed.url,{method:'PUT',headers:{'Content-Type':file.type},body:file});await api('/admin/media',{method:'POST',body:JSON.stringify({objectKey:key,titleZh:file.name.replace(/\.[^.]+$/,''),titleEn:file.name.replace(/\.[^.]+$/,''),mediaType:'IMAGE',sortOrder:galleryItems.value.length+index})})}adminNotice.value=text('上传完成，AI 提示词已生成','Upload complete; AI prompts generated');await loadPublic()}

const games=[
  {id:'flight',no:'01',icon:'✈',zh:'云海穿行',en:'Cloud Flight',dzh:'穿过雾与山脊，保持飞行。',den:'Glide through mist and mountain ridges.'},
  {id:'memory',no:'02',icon:'⌘',zh:'代码记忆',en:'Code Match',dzh:'翻开技术图标，找出配对。',den:'Flip tech icons and find every pair.'},
  {id:'stack',no:'03',icon:'▦',zh:'技术栈 2048',en:'Stack 2048',dzh:'合并技术栈，抵达 2048。',den:'Merge the stack and reach 2048.'},
]
const gameId=computed(()=>String(route.params.game||'')),playing=ref(false),score=ref(0),planeX=ref(50),timer=ref<number>()
const memory=ref(['Vue','Java','AI','SQL','Vue','Java','AI','SQL'].sort(()=>Math.random()-.5).map((value,index)=>({value,index,open:false,done:false}))),openCards=ref<number[]>([])
function flip(index:number){const card=memory.value[index];if(card.open||card.done||openCards.value.length===2)return;card.open=true;openCards.value.push(index);if(openCards.value.length===2){const[a,b]=openCards.value;if(memory.value[a].value===memory.value[b].value){memory.value[a].done=memory.value[b].done=true;score.value+=20;openCards.value=[]}else setTimeout(()=>{memory.value[a].open=memory.value[b].open=false;openCards.value=[]},650)}}
const board=ref([2,0,0,0,0,2,0,0,0,0,0,0,0,0,0,0])
function stackMove(){const values=board.value.filter(Boolean);for(let i=0;i<values.length-1;i++)if(values[i]===values[i+1]){values[i]*=2;score.value+=values[i];values.splice(i+1,1)}while(values.length<16)values.push(0);const empty=values.map((v,i)=>v?-1:i).filter(i=>i>=0);if(empty.length)values[empty[Math.floor(Math.random()*empty.length)]]=Math.random()>.8?4:2;board.value=values}
function key(event:KeyboardEvent){if(gameId.value==='flight'){if(event.key==='ArrowLeft')planeX.value=Math.max(8,planeX.value-5);if(event.key==='ArrowRight')planeX.value=Math.min(92,planeX.value+5)}if(gameId.value==='stack'&&event.key.startsWith('Arrow')){event.preventDefault();stackMove()}}
function startFlight(){playing.value=!playing.value;if(playing.value)timer.value=window.setInterval(()=>score.value++,120);else clearInterval(timer.value)}
onMounted(()=>{addEventListener('keydown',key);loadPublic();if(route.name==='admin')loadAdmin()});onBeforeUnmount(()=>{removeEventListener('keydown',key);clearInterval(timer.value);clearInterval(aiPollTimer)})
watch(()=>route.fullPath,()=>{mobileOpen.value=false;notice.value='';playing.value=false;soundOn.value=false;clearInterval(timer.value);clearInterval(aiPollTimer);loadPublic();if(route.name==='admin')loadAdmin()})
</script>

<template>
<main :class="['site',{dark}]">
  <header v-if="route.name!=='admin'" class="topbar">
    <RouterLink class="brand" to="/"><img src="/media/wynn-mark.png"><span>WYNN YALE YOX</span></RouterLink>
    <nav :class="{open:mobileOpen}">
      <RouterLink to="/"><img class="nav-charm" src="/media/cat-items/paw.png" alt="">{{text('首页','Home')}}</RouterLink>
      <RouterLink to="/blog"><img class="nav-charm" src="/media/cat-items/feather.png" alt="">{{text('博客','Blog')}}</RouterLink>
      <RouterLink to="/gallery"><img class="nav-charm" src="/media/cat-items/fish-one.png" alt="">{{text('影像','Gallery')}}</RouterLink>
      <RouterLink to="/studio"><img class="nav-charm" src="/media/cat-items/yarn.png" alt="">{{text('AI 创作','AI Studio')}}</RouterLink>
      <RouterLink to="/games"><img class="nav-charm" src="/media/cat-items/mouse.png" alt="">{{text('游戏','Games')}}</RouterLink>
      <RouterLink to="/about"><img class="nav-charm" src="/media/cat-items/collar.png" alt="">{{text('关于','About')}}</RouterLink>
    </nav>
    <div class="nav-actions"><button class="pill" @click="toggleLang">{{lang==='zh'?'中 / EN':'EN / 中'}}</button><button class="menu" @click="mobileOpen=!mobileOpen">☰</button></div>
  </header>

  <template v-if="route.name==='home'">
    <section class="hero"><video ref="heroVideo" autoplay :muted="!soundOn" loop playsinline poster="/media/hero.jpg"><source src="/media/hero.mp4" type="video/mp4"></video><div class="shade"></div><div class="hero-copy"><small>PERSONAL DIGITAL GARDEN · 2026</small><h1>Wynn<br>Yale Yox</h1><i></i><p>{{text('随性而行，无拘无定。','Move freely, remain undefined.')}}</p></div><button class="sound-toggle" :class="{on:soundOn}" :aria-label="soundOn?text('关闭音乐','Mute music'):text('打开音乐','Play music')" :title="soundOn?text('关闭音乐','Mute music'):text('打开音乐','Play music')" @click="toggleSound"><svg v-if="soundOn" viewBox="0 0 24 24" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="m15 9 6 6M21 9l-6 6"/></svg></button><button :class="['cat home-cat',{dragging:catDragging}]" :style="catStyle" :aria-label="text('拖动小猫','Drag kitten')" @pointerdown="startCatDrag" @pointermove="moveCat" @pointerup="endCatDrag" @pointercancel="endCatDrag" @click="catClick"><img src="/media/fluffy-kitten.png" draggable="false"></button><span v-if="catTalk" class="bubble" :style="catStyle">{{text('喵～欢迎回来 ✦','Meow — welcome back ✦')}}</span><span class="scroll">—　{{text('向下探索','Explore')}}</span></section>

    <section class="home-music">
      <img class="music-yarn" src="/media/cat-items/yarn.png" alt="">
      <div class="music-cover playlist-cover"><img src="/media/netease-playlist-cover.jpg" :alt="text('网易云歌单“薛”封面','Cover of NetEase playlist Xue')"><span>10 TRACKS</span></div>
      <div class="music-copy playlist-copy"><small>NETEASE CLOUD MUSIC · PLAYLIST</small><h2>薛</h2><p>{{text('Lonely__Runner 的网易云歌单。点击歌曲名称即可切换播放，会员歌曲的播放权限由网易云账号状态决定。','A NetEase Cloud Music playlist by Lonely__Runner. Select any title to switch tracks; member-only playback follows your NetEase account access.')}}</p><div class="playlist-meta"><span>10 {{text('首歌曲','tracks')}}</span><a href="https://music.163.com/playlist?id=17861500518" target="_blank" rel="noreferrer">{{text('在网易云打开','Open in NetEase')}} ↗</a></div><div class="netease-player"><iframe title="网易云音乐歌单：薛" src="https://music.163.com/outchain/player?type=0&id=17861500518&auto=0&height=430" width="100%" height="450" frameborder="0" loading="lazy" allow="autoplay"></iframe></div></div>
      <img class="music-feather" src="/media/cat-items/feather.png" alt="">
    </section>

    <section class="home-visuals">
      <header><div><small>SELECTED FRAMES</small><h2>{{text('从雾中拾取三帧','Three frames found in the mist')}}</h2></div><RouterLink to="/gallery">{{text('查看全部影像','View gallery')}} ↗</RouterLink></header>
      <div class="visual-grid"><figure><img src="/media/home-gallery/mist-field.jpg" :alt="text('雾岭','Misty ridge')"><figcaption><b>01</b><span>{{text('雾岭','Misty Ridge')}}<small>03.5 SEC</small></span></figcaption></figure><figure><img src="/media/home-gallery/mountain-air.jpg" :alt="text('山风','Mountain air')"><figcaption><b>02</b><span>{{text('山风','Mountain Air')}}<small>11.5 SEC</small></span></figcaption></figure><figure><img src="/media/home-gallery/fading-memory.jpg" :alt="text('渐隐记忆','Fading memory')"><figcaption><b>03</b><span>{{text('渐隐记忆','Fading Memory')}}<small>15.0 SEC</small></span></figcaption></figure></div>
      <img class="visual-mouse" src="/media/cat-items/mouse.png" alt="">
    </section>
  </template>

  <template v-else-if="route.name==='blog'"><section class="page-head"><small>NOTES & EXPERIMENTS</small><h1>{{text('技术与思考','Technology & Thoughts')}}</h1><p>{{text('记录 Java、AI 工程与产品实践，也记录那些仍在形成中的判断。','Notes on Java, AI engineering, products, and ideas still taking shape.')}}</p><form class="blog-search" @submit.prevent="searchPosts"><input v-model="blogSearch" :placeholder="text('搜索文章、标签或内容','Search posts, tags, or content')"><button>{{text('搜索','Search')}}</button><a href="/api/public/rss.xml" target="_blank">RSS ↗</a></form></section><section v-if="posts.length" class="blog-grid"><RouterLink class="feature code-feature" :to="`/blog/${posts[0].slug}`"><div class="code-title"><span><i></i><i></i><i></i></span><small>ReliableAgent.java</small></div><code><span><em>01</em><b>@Service</b></span><span><em>02</em><strong>public class</strong> ReliableAgent &#123;</span><span><em>03</em>　<strong>private final</strong> ChatClient client;</span><span><em>04</em></span><span><em>05</em>　<strong>public</strong> Answer run(Query query) &#123;</span><span><em>06</em>　　<strong>return</strong> client.prompt()</span><span><em>07</em>　　　.user(query.text())</span><span><em>08</em>　　　.call().entity(Answer.class);</span><span><em>09</em>　&#125;</span><span><em>10</em>&#125;</span></code><div class="code-post"><small>{{posts[0].tag}}</small><h2>{{text(posts[0].zh,posts[0].en)}}</h2><span>{{posts[0].date}} · 12 MIN　→</span></div></RouterLink><div class="post-list"><RouterLink v-for="(post,i) in posts.slice(1)" :key="post.slug" class="post" :to="`/blog/${post.slug}`"><div><small>{{post.tag}}</small><h2>{{text(post.zh,post.en)}}</h2><p>{{text(post.summaryZh||'',post.summaryEn||'')}}</p><span>{{post.date}}</span></div><b>0{{i+1}}</b></RouterLink></div></section><p v-else class="empty-state">{{text('没有找到文章','No posts found')}}</p></template>

  <article v-else-if="route.name==='article'&&currentPost" class="article"><small>{{currentPost.tag}} · {{currentPost.date}}</small><h1>{{text(currentPost.zh,currentPost.en)}}</h1><p class="lead">{{text(currentPost.summaryZh||'',currentPost.summaryEn||'')}}</p><hr><div class="prose" v-html="markdown(text(currentPost.contentZh||'',currentPost.contentEn||''))"></div><section class="comments"><h2>{{text('评论','Comments')}}</h2><article v-for="item in comments" :key="item.id"><b>{{item.author}}</b><p>{{item.content}}</p><small>{{String(item.createdAt).slice(0,10)}}</small></article><form @submit.prevent="submitComment"><input v-model="commentForm.author" required :placeholder="text('昵称','Name')"><input v-model="commentForm.email" type="email" :placeholder="text('邮箱（不会公开）','Email (private)')"><textarea v-model="commentForm.content" required :placeholder="text('写下评论，审核后显示','Write a comment; it appears after review')"></textarea><button>{{text('提交评论','Submit comment')}}</button><small>{{commentNotice}}</small></form></section></article>

  <template v-else-if="route.name==='gallery'"><section class="page-head"><small>VISUAL ARCHIVE</small><h1>{{text('影像收藏','Visual Archive')}}</h1><p>{{text('点击照片即可翻转，查看并复制它的创作提示词。','Tap a photo to flip it, then view and copy its creation prompt.')}}</p></section><section class="gallery user-gallery"><figure v-for="(item,i) in galleryItems" :key="item.id||item.url" :class="{portrait:i%5===4,flipped:flippedGallery===galleryKey(item,i)}" tabindex="0" @click="flipGallery(item,i)" @keyup.enter="flipGallery(item,i)"><div class="gallery-flip"><div class="gallery-face gallery-front"><img :src="item.url" :alt="text(item.titleZh,item.titleEn)" loading="lazy"><figcaption>{{text(item.titleZh,item.titleEn)}} · {{text('点击翻转','FLIP')}}</figcaption></div><div class="gallery-face gallery-back" @click.stop="flippedGallery=undefined"><small>IMAGE PROMPT · {{String(i+1).padStart(2,'0')}}</small><h2>{{text(item.titleZh,item.titleEn)}}</h2><p>{{text(item.promptZh||'正在生成提示词…',item.promptEn||'Generating prompt…')}}</p><button @click.stop="copyGalleryPrompt(item,i)">{{copiedGallery===galleryKey(item,i)?text('已复制 ✓','Copied ✓'):text('复制提示词','Copy prompt')}}</button><span>{{text('点击空白处返回照片','Tap elsewhere to return')}}</span></div></div></figure></section></template>

  <section v-else-if="route.name==='studio'" class="studio"><aside><b>● {{quota.owner?text('管理员模式','Owner mode'):text('公开试用','Public trial')}}</b><button :class="{on:creatorMode==='IMAGE'}" @click="creatorMode='IMAGE'">✦ {{text('图片生成','Image')}}</button><button :class="{on:creatorMode==='VIDEO'}" @click="creatorMode='VIDEO'">▶ {{text('视频生成','Video')}}</button><span>{{quotaText}}</span><RouterLink v-if="!quota.owner" to="/login?redirect=/studio">{{text('管理员登录 →','Owner sign in →')}}</RouterLink><span>{{text('结果自动保存至 OSS','Results saved to OSS')}}</span></aside><div class="studio-main"><small>PUBLIC AI STUDIO · RELAY + QWEN FALLBACK</small><h1>{{creatorMode==='IMAGE'?text('把想法变成画面','Turn ideas into images'):text('让画面开始流动','Bring a scene to life')}}</h1><div class="studio-options"><label>{{text('画面比例','Aspect ratio')}}<select v-model="aspectRatio"><option>16:9</option><option>9:16</option><option>1:1</option><option>4:3</option><option>3:4</option></select></label><label v-if="creatorMode==='VIDEO'">{{text('清晰度','Resolution')}}<select v-model="resolution"><option>480p</option><option>720p</option><option>1080p</option></select></label><label v-if="creatorMode==='VIDEO'">{{text('时长','Duration')}}<input v-model.number="duration" type="number" min="1" max="15"><span>s</span></label></div><div class="prompt"><textarea v-model="prompt" :placeholder="text('描述你想生成的内容','Describe what you want to create')"></textarea><footer><span>{{aspectRatio}} · {{creatorMode==='VIDEO'?`${resolution} · ${duration}s`:'AI IMAGE'}}</span><button @click="generate" :disabled="busy||(!quota.owner&&creatorMode==='IMAGE'&&!quota.imageRemaining)||(!quota.owner&&creatorMode==='VIDEO'&&!quota.videoRemaining)">{{busy?text('生成中…','Generating…'):text('开始生成 ↗','Generate ↗')}}</button></footer></div><div class="result generated-result"><img v-if="!currentJob?.resultUrl" src="/media/fluffy-kitten.png"><img v-else-if="currentJob.type==='IMAGE'" :src="currentJob.resultUrl" :alt="currentJob.prompt"><video v-else :src="currentJob.resultUrl" controls playsinline></video><p>{{notice||text('生成结果将在这里出现','Your result will appear here')}}</p><a v-if="currentJob?.resultUrl" class="download-result" :href="currentJob.downloadUrl||currentJob.resultUrl" download>{{currentJob.type==='VIDEO'?'▸':'↓'}} {{text(currentJob.type==='VIDEO'?'下载视频':'下载图片',currentJob.type==='VIDEO'?'Download video':'Download image')}}</a><small>{{text('访客各可试用一次；管理员登录后不限次数','Guests receive one image and one video; owner access is unlimited')}}</small></div></div></section>

  <section v-else-if="route.name==='about'" class="about"><div class="portrait profile-portrait"><img src="/media/profile-avatar.png" :alt="profile.displayName"><span>{{profile.displayName}}</span></div><div class="about-copy"><small>ABOUT {{profile.realName.toUpperCase()}}</small><img class="signature" src="/media/wynn-signature.png"><h1>{{text('在代码与生活之间，保留自由生长的空间。','Keep room to grow between code and life.')}}</h1><p>{{text(profile.bioZh,profile.bioEn)}}</p><dl><div><dt>{{text('城市','City')}}</dt><dd>{{text(profile.cityZh,profile.cityEn)}}</dd></div><div><dt>{{text('邮箱','Email')}}</dt><dd><a :href="`mailto:${profile.email}`">{{profile.email}}</a></dd></div><div><dt>{{text('代码','Code')}}</dt><dd><a :href="profile.github" target="_blank">GitHub ↗</a> · <a :href="profile.gitee" target="_blank">Gitee ↗</a></dd></div><div><dt>{{text('闲鱼','Xianyu')}}</dt><dd><a :href="profile.xianyu" target="_blank">Lonely__Runner ↗</a></dd></div><div><dt>{{text('微信','WeChat')}}</dt><dd>{{profile.wechat}}</dd></div></dl><button :class="['cat about-cat',{dragging:catDragging}]" :style="catStyle" :aria-label="text('拖动小猫','Drag kitten')" @pointerdown="startCatDrag" @pointermove="moveCat" @pointerup="endCatDrag" @pointercancel="endCatDrag" @click="catClick"><img src="/media/fluffy-kitten.png" draggable="false"></button></div></section>

  <template v-else-if="route.name==='games'"><section class="page-head"><small>SMALL WORLDS</small><h1>{{text('玩一会儿','Play for a while')}}</h1><p>{{text('三个轻量小游戏，随时开始，也随时离开。','Three small games. Start and leave whenever you like.')}}</p></section><section class="game-grid"><RouterLink v-for="game in games" :key="game.id" :to="`/games/${game.id}`" class="game-card"><b>{{game.no}}</b><strong>{{game.icon}}</strong><small>{{game.id}}</small><h2>{{text(game.zh,game.en)}}</h2><p>{{text(game.dzh,game.den)}}</p><span>{{text('开始游戏','Play')}} →</span></RouterLink></section></template>

  <section v-else-if="route.name==='game'" class="game-page"><div class="game-title"><RouterLink to="/games">← {{text('返回','Back')}}</RouterLink><b>{{text(games.find(g=>g.id===gameId)?.zh||'',games.find(g=>g.id===gameId)?.en||'')}}</b><span>SCORE {{score}}</span></div><div v-if="gameId==='flight'" class="flight" @click="startFlight"><img src="/media/hero.jpg"><span class="plane" :style="{left:planeX+'%'}">✈</span><button>{{playing?text('暂停','Pause'):text('点击开始','Click to start')}}</button></div><div v-else-if="gameId==='memory'" class="memory"><button v-for="(card,i) in memory" :key="i" :class="{open:card.open||card.done}" @click="flip(i)">{{card.open||card.done?card.value:'✦'}}</button></div><div v-else class="stack"><button v-for="(cell,i) in board" :key="i" @click="stackMove">{{cell||''}}</button><p>{{text('方向键或点击任意格移动','Use arrow keys or click any tile')}}</p></div></section>

  <article v-else-if="route.name==='privacy'||route.name==='terms'" class="legal-page"><small>WYNN YALE YOX · LEGAL</small><h1>{{route.name==='privacy'?text('隐私政策','Privacy Policy'):text('使用条款','Terms of Use')}}</h1><p class="legal-date">{{text('更新日期：2026 年 9 月 20 日','Updated: September 20, 2026')}}</p><template v-if="route.name==='privacy'"><h2>{{text('我们收集什么','What we collect')}}</h2><p>{{text('本站仅收集账户登录、评论审核、内容管理和 AI 创作所必需的数据。访客使用 AI 试用时，浏览器会保存匿名额度标识。','This site only collects data needed for login, comment review, content management, and AI creation. An anonymous allowance identifier is stored when a guest uses the AI trial.')}}</p><h2>{{text('数据如何使用','How data is used')}}</h2><p>{{text('数据仅用于提供本站功能、管理访客试用额度、保障安全和排查故障。未经你的明确同意，不会出售或公开个人数据。','Data is used to operate the site, manage guest allowances, protect it, and diagnose problems. Personal data is not sold or disclosed without explicit consent.')}}</p><h2>{{text('存储与联系','Storage and contact')}}</h2><p>{{text('媒体文件和 AI 生成结果存储于阿里云 OSS。需要查询或删除个人数据时，请通过关于页面所列联系方式联系站点所有者。','Media and AI results are stored in Alibaba Cloud OSS. Contact the site owner through the About page to request access to or deletion of personal data.')}}</p></template><template v-else><h2>{{text('内容与访问','Content and access')}}</h2><p>{{text('公开内容仅供阅读和个人欣赏。访客可免费试用一次图片生成和一次视频生成；管理员登录后不限制次数。','Public content is provided for reading and personal enjoyment. Guests may try one image and one video generation; signed-in owner access is unlimited.')}}</p><h2>{{text('知识产权','Intellectual property')}}</h2><p>{{text('除另有说明外，本站文字、图片、视频和品牌元素的权利归站点所有者所有。引用时请注明来源。','Unless stated otherwise, site text, images, video, and brand elements belong to the site owner. Please credit the source when quoting.')}}</p><h2>{{text('服务变更','Service changes')}}</h2><p>{{text('本站可能随时调整功能与内容；涉及重要规则的变更会在本页更新。','Features and content may change over time. Material rule changes will be reflected on this page.')}}</p></template></article>

  <section v-else-if="route.name==='login'" class="login"><form @submit.prevent="login"><img src="/media/wynn-mark.png"><h1>{{text('回到你的空间','Welcome back')}}</h1><p>{{text('个人内容管理后台','Personal content console')}}</p><label>{{text('账号','Account')}}<input v-model="username"></label><label>{{text('密码','Password')}}<input v-model="password" type="password"></label><button :disabled="busy">{{text('安全登录','Sign in')}} →</button><small>{{notice||text('仅限站点所有者访问','Owner access only')}}</small></form></section>

  <section v-else-if="route.name==='admin'" class="admin"><aside><RouterLink class="brand" to="/"><img src="/media/wynn-mark.png"><span>WYNN CMS</span></RouterLink><button :class="{active:adminTab==='overview'}" @click="adminTab='overview'">⌂　{{text('总览','Overview')}}</button><button :class="{active:adminTab==='posts'}" @click="adminTab='posts'">▤　{{text('文章管理','Posts')}}</button><button :class="{active:adminTab==='media'}" @click="adminTab='media'">▧　{{text('媒体管理','Media')}}</button><button :class="{active:adminTab==='ai'}" @click="adminTab='ai'">✦　{{text('AI 生成记录','AI Jobs')}}</button><button :class="{active:adminTab==='comments'}" @click="adminTab='comments'">◌　{{text('评论审核','Comments')}}</button><button class="signout" @click="logout">{{text('退出登录','Sign out')}}</button></aside><div class="admin-main"><header><div><small>PERSONAL CONSOLE</small><h1>{{text('你好，Lonely__Runner','Hello, Lonely__Runner')}}</h1></div><img src="/media/fluffy-kitten.png"></header><template v-if="adminTab==='overview'"><div class="stats"><article><span>{{text('文章','Posts')}}</span><b>{{adminPosts.length}}</b></article><article><span>{{text('影像作品','Images')}}</span><b>{{galleryItems.length}}</b></article><article><span>{{text('AI 任务','AI jobs')}}</span><b>{{adminJobs.length}}</b></article><article><span>{{text('待审评论','Pending comments')}}</span><b>{{adminComments.filter(c=>!c.approved).length}}</b></article></div><div class="admin-panels"><article><h2>{{text('最近内容','Recent content')}}</h2><p v-for="post in adminPosts.slice(0,5)" :key="post.slug"><b>{{post.titleZh}}</b><span>{{post.category}}</span></p></article><article><h2>{{text('AI 服务状态','AI providers')}}</h2><p><b>Qwen Image</b><i></i></p><p><b>Relay Image / Video</b><i></i></p><p><b>OSS Beijing</b><i></i></p></article></div></template><template v-else-if="adminTab==='posts'"><div class="admin-workspace"><form class="post-editor" @submit.prevent="savePost"><div class="editor-head"><h2>{{postForm.id?text('编辑文章','Edit post'):text('新建文章','New post')}}</h2><button type="button" @click="resetPost">＋ {{text('清空','Reset')}}</button></div><div class="form-grid"><label>Slug<input v-model="postForm.slug" required></label><label>{{text('分类','Category')}}<input v-model="postForm.category"></label><label>{{text('中文标题','Chinese title')}}<input v-model="postForm.titleZh" required></label><label>{{text('英文标题','English title')}}<input v-model="postForm.titleEn" required></label><label class="wide">{{text('标签（逗号分隔）','Tags')}}<input v-model="postForm.tags"></label><label class="wide">{{text('中文摘要','Chinese summary')}}<textarea v-model="postForm.summaryZh"></textarea></label><label class="wide">{{text('英文摘要','English summary')}}<textarea v-model="postForm.summaryEn"></textarea></label><label class="wide">{{text('中文 Markdown','Chinese Markdown')}}<textarea class="content-editor" v-model="postForm.contentZh"></textarea></label><label class="wide">{{text('英文 Markdown','English Markdown')}}<textarea class="content-editor" v-model="postForm.contentEn"></textarea></label><label>{{text('封面 OSS Key','Cover OSS key')}}<input v-model="postForm.coverObjectKey"></label><label class="check"><input v-model="postForm.published" type="checkbox">{{text('立即发布','Publish now')}}</label></div><div class="editor-actions"><label class="file-button">{{text('上传 Word / PDF / Markdown 自动解析','Import Word / PDF / Markdown')}}<input type="file" accept=".doc,.docx,.pdf,.md,.txt" @change="importDocument"></label><button>{{text('保存文章','Save post')}}</button><small>{{adminNotice}}</small></div></form><div class="admin-list"><article v-for="post in adminPosts" :key="post.id"><div><small>{{post.published?text('已发布','Published'):text('草稿','Draft')}}</small><h3>{{post.titleZh}}</h3><p>{{post.category}} · {{post.tags}}</p></div><button @click="editPost(post)">{{text('编辑','Edit')}}</button><button class="danger" @click="removePost(post.id)">{{text('删除','Delete')}}</button></article></div></div></template><template v-else-if="adminTab==='media'"><div class="admin-card"><h2>{{text('上传影像到 OSS','Upload images to OSS')}}</h2><p>{{text('支持一次选择多张图片，上传完成后自动加入影像页。','Choose multiple images; uploaded files appear in the gallery automatically.')}}</p><label class="file-button">{{text('选择图片','Choose images')}}<input type="file" accept="image/*" multiple @change="uploadGallery"></label><small>{{adminNotice}}</small><div class="media-admin-grid"><img v-for="item in galleryItems" :key="item.url" :src="item.url" :alt="item.titleZh"></div></div></template><template v-else-if="adminTab==='ai'"><div class="admin-list"><article v-for="job in adminJobs" :key="job.id"><div><small>{{job.type}} · {{job.provider}} · {{job.status}}</small><h3>{{job.prompt}}</h3><a v-if="job.resultUrl" :href="job.resultUrl" target="_blank">{{text('查看结果','View result')}} ↗</a><a v-if="job.downloadUrl" class="admin-download" :href="job.downloadUrl" download>↓ {{text('下载文件','Download')}}</a><p v-if="job.error">{{job.error}}</p></div><button class="danger" @click="removeJob(job.id)">{{text('删除','Delete')}}</button></article></div></template><template v-else><div class="admin-list"><article v-for="comment in adminComments" :key="comment.id"><div><small>{{comment.postTitle}} · {{comment.author}}</small><h3>{{comment.content}}</h3><p>{{comment.email}}</p></div><button v-if="!comment.approved" @click="approveComment(comment.id)">{{text('通过','Approve')}}</button><button class="danger" @click="removeComment(comment.id)">{{text('删除','Delete')}}</button></article></div></template></div></section>
  <footer v-if="!['admin','login'].includes(String(route.name))" class="site-footer"><div class="footer-toys" aria-hidden="true"><img src="/media/cat-items/food.png" alt=""><img src="/media/cat-items/fish-two.png" alt=""><img src="/media/cat-items/paw.png" alt=""></div><div class="footer-grid"><div class="footer-brand"><div class="footer-logo"><img src="/media/wynn-mark.png" alt=""><span>WYNN YALE YOX</span></div><p>{{text('写代码，收集影像，也为偶然出现的灵感留一块柔软的地方。','Code, images, and a soft corner for ideas that arrive by chance.')}}</p></div><div><b>{{text('探索','Explore')}}</b><RouterLink to="/blog">{{text('技术博客','Tech blog')}}</RouterLink><RouterLink to="/gallery">{{text('影像收藏','Gallery')}}</RouterLink><RouterLink to="/games">{{text('小游戏','Games')}}</RouterLink></div><div><b>{{text('空间','Space')}}</b><RouterLink to="/about">{{text('关于我','About')}}</RouterLink><RouterLink to="/studio">{{text('AI 创作室','AI Studio')}}</RouterLink><a href="https://github.com/LonlyRunner" target="_blank" rel="noreferrer">GitHub ↗</a></div><div><b>{{text('协议与管理','Legal & Admin')}}</b><RouterLink to="/privacy">{{text('隐私政策','Privacy')}}</RouterLink><RouterLink to="/terms">{{text('使用条款','Terms')}}</RouterLink><RouterLink class="footer-login" to="/login">♙ {{text('管理后台登录','Admin sign in')}} →</RouterLink></div></div><div class="footer-bottom"><span>© 2026 WYNN YALE YOX</span><span>{{text('随性而行，无拘无定。','Move freely, remain undefined.')}}</span></div></footer>
  <div v-if="catTalk" class="toast">{{text('喵～今天也要做点喜欢的事 ✦','Meow — make something you love today ✦')}}</div>
</main>
</template>

<style scoped>
.cat {
  cursor: grab;
  touch-action: none;
  user-select: none;
  will-change: transform;
}
.cat.dragging { cursor: grabbing; }
.cat img { pointer-events: none; }
.bubble { pointer-events: none; }
.sound-toggle {
  position: absolute;
  z-index: 3;
  top: 24px;
  right: 5vw;
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  color: white;
  background: rgb(6 17 23 / 38%);
  border: 1px solid rgb(255 255 255 / 45%);
  border-radius: 50%;
  cursor: pointer;
  backdrop-filter: blur(10px);
  transition: background .2s ease, transform .2s ease;
}
.sound-toggle:hover { transform: scale(1.06); background: rgb(6 17 23 / 58%); }
.sound-toggle.on { background: rgb(8 103 170 / 68%); }
.sound-toggle svg { width: 20px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.topbar nav a { display: flex; align-items: center; gap: 7px; white-space: nowrap; }
.nav-charm { width: 23px; height: 23px; object-fit: contain; filter: drop-shadow(0 3px 4px rgb(0 0 0 / 18%)); transition: transform .2s ease; }
.topbar nav a:hover .nav-charm, .topbar nav a.router-link-active .nav-charm { transform: translateY(-2px) rotate(-5deg) scale(1.08); }
.home-music { min-height: 690px; position: relative; overflow: hidden; display: grid; grid-template-columns: minmax(280px, 430px) minmax(320px, 640px); justify-content: center; align-items: center; gap: clamp(45px, 8vw, 120px); padding: 110px 7vw; background: radial-gradient(circle at 26% 45%, #173448 0, #0b171e 35%, #070c0f 74%); border-bottom: 1px solid #263138; }
.music-cover { position: relative; aspect-ratio: 1; padding: 18px; border: 1px solid rgb(255 255 255 / 25%); border-radius: 50%; box-shadow: 0 35px 80px rgb(0 0 0 / 45%); }
.music-cover:after { content: ""; position: absolute; inset: 42%; border-radius: 50%; background: #091117; border: 2px solid #86caec; box-shadow: 0 0 0 7px rgb(4 10 14 / 75%); }
.music-cover img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; filter: saturate(.72) contrast(1.05); }
.music-cover>span { position: absolute; z-index: 1; inset: 0; display: grid; place-content: center; color: white; font: 13px var(--serif); letter-spacing: .18em; }
.playlist-cover:after { display: none; }
.playlist-cover>span { inset: auto 26px 25px auto; display: block; padding: 8px 11px; color: #0b171e; background: rgb(255 255 255 / 88%); border-radius: 99px; font: 9px var(--sans); backdrop-filter: blur(8px); }
.music-copy { position: relative; z-index: 2; }
.music-copy>small, .home-visuals header small, .legal-page>small { color: var(--sky); font-size: 10px; letter-spacing: .3em; }
.music-copy h2, .home-visuals h2 { font: 500 clamp(45px, 5vw, 76px)/1 var(--serif); margin: 16px 0 24px; }
.music-copy p { max-width: 590px; color: #a6b5bd; line-height: 1.9; }
.waveform { height: 60px; display: flex; align-items: center; gap: 5px; margin: 35px 0 24px; }
.waveform span { width: 3px; border-radius: 9px; background: linear-gradient(var(--sky), #2f7da9); opacity: .52; transform: scaleY(.45); transition: transform .3s ease, opacity .3s ease; }
.waveform.playing span { opacity: 1; animation: soundWave .75s ease-in-out infinite alternate; animation-delay: calc(var(--i, 1) * -40ms); }
.waveform.playing span:nth-child(3n) { animation-duration: .52s; }
.waveform.playing span:nth-child(4n) { animation-duration: .93s; }
.music-controls { display: flex; align-items: center; gap: 22px; }
.music-controls button { display: flex; align-items: center; gap: 12px; padding: 9px 20px 9px 9px; border: 1px solid #6a8390; border-radius: 99px; color: white; background: transparent; cursor: pointer; }
.music-controls button b { width: 35px; height: 35px; display: grid; place-items: center; border-radius: 50%; color: #0b171e; background: white; }
.music-controls>span { color: #738791; font-size: 10px; letter-spacing: .18em; }
.playlist-meta { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin: 24px 0 14px; color: #7f939d; font-size: 10px; letter-spacing: .14em; }
.playlist-meta a { color: #c4d2d8; border-bottom: 1px solid #607985; padding-bottom: 5px; }
.netease-player { position: relative; overflow: hidden; min-height: 450px; background: #f5f5f5; border: 1px solid #334852; border-radius: 12px; box-shadow: 0 20px 45px rgb(0 0 0 / 28%); }
.netease-player iframe { display: block; border: 0; }
.music-yarn, .music-feather, .visual-mouse { position: absolute; pointer-events: none; filter: drop-shadow(0 18px 24px rgb(0 0 0 / 28%)); }
.music-yarn { width: 180px; left: -52px; top: 20px; opacity: .42; transform: rotate(18deg); }
.music-feather { width: 145px; right: -22px; bottom: -24px; opacity: .62; transform: rotate(-25deg); }
.home-visuals { position: relative; overflow: hidden; padding: 110px 6vw 135px; color: #0a1115; background: #f4f7f8; }
.home-visuals>header { display: flex; align-items: end; justify-content: space-between; gap: 30px; margin-bottom: 42px; }
.home-visuals h2 { max-width: 760px; margin-bottom: 0; }
.home-visuals>header>a { flex: none; border-bottom: 1px solid #718691; padding-bottom: 7px; font-size: 12px; }
.visual-grid { display: grid; grid-template-columns: 1.15fr 1fr .85fr; gap: 16px; align-items: stretch; }
.visual-grid figure { min-width: 0; margin: 0; position: relative; overflow: hidden; background: #c9d2d6; }
.visual-grid figure:nth-child(1) { aspect-ratio: .92; }
.visual-grid figure:nth-child(2) { aspect-ratio: .82; margin-top: 58px; }
.visual-grid figure:nth-child(3) { aspect-ratio: .74; margin-top: 118px; }
.visual-grid figure:after { content: ""; position: absolute; inset: 0; background: linear-gradient(transparent 48%, rgb(3 9 12 / 76%)); }
.visual-grid figure img { width: 100%; height: 100%; display: block; object-fit: cover; transition: transform .7s ease; }
.visual-grid figure:hover img { transform: scale(1.035); }
.visual-grid figcaption { position: absolute; z-index: 1; left: 20px; right: 20px; bottom: 18px; display: flex; align-items: end; gap: 15px; color: white; }
.visual-grid figcaption>b { font: 34px var(--serif); color: var(--sky); }
.visual-grid figcaption span { display: grid; gap: 4px; font: 18px var(--serif); }
.visual-grid figcaption small { color: #b5c4ca; font: 9px var(--sans); letter-spacing: .18em; }
.visual-mouse { width: 170px; right: -42px; bottom: 14px; transform: rotate(-8deg); }
.site-footer { position: relative; overflow: hidden; padding: 38px 6vw 28px; color: #c5d0d5; background: #070c0f; border-top: 1px solid #28343b; }
.footer-toys { height: 98px; display: flex; align-items: end; justify-content: center; gap: clamp(25px, 8vw, 120px); border-bottom: 1px solid #27343a; margin-bottom: 52px; }
.footer-toys img { width: 94px; max-height: 92px; object-fit: contain; filter: drop-shadow(0 12px 18px rgb(0 0 0 / 34%)); }
.footer-toys img:first-child { width: 106px; transform: rotate(-8deg) translateY(16px); }
.footer-toys img:nth-child(2) { width: 112px; transform: rotate(8deg) translateY(10px); }
.footer-toys img:last-child { width: 103px; transform: rotate(-5deg) translateY(18px); }
.footer-grid { display: grid; grid-template-columns: 2fr repeat(3, 1fr); gap: 50px; padding-bottom: 58px; }
.footer-logo { display: flex; align-items: center; gap: 13px; color: white; font: 600 15px var(--serif); letter-spacing: .12em; }
.footer-logo img { width: 44px; height: 44px; border-radius: 50%; }
.footer-brand p { max-width: 360px; color: #788991; font-size: 12px; line-height: 1.9; }
.footer-grid>div:not(.footer-brand) { display: flex; flex-direction: column; align-items: flex-start; gap: 13px; font-size: 12px; }
.footer-grid b { color: white; margin-bottom: 8px; font-size: 11px; letter-spacing: .18em; }
.footer-grid a { color: #81939c; transition: color .2s ease, transform .2s ease; }
.footer-grid a:hover { color: white; transform: translateX(3px); }
.footer-grid .footer-login { margin-top: 3px; padding: 9px 12px; color: #d9e9ef; border: 1px solid #40545e; border-radius: 999px; }
.footer-grid .footer-login:hover { color: #081116; background: #d9e9ef; border-color: #d9e9ef; }
.footer-bottom { display: flex; justify-content: space-between; gap: 20px; padding-top: 22px; border-top: 1px solid #27343a; color: #63747c; font-size: 9px; letter-spacing: .16em; }
.legal-page { max-width: 850px; min-height: 70vh; margin: 0 auto; padding: 90px 6vw 120px; color: #11191d; }
.legal-page h1 { font: 500 clamp(48px, 6vw, 76px)/1 var(--serif); margin: 16px 0; }
.legal-date { color: #7a8a91; font-size: 11px; margin-bottom: 58px; }
.legal-page h2 { font: 28px var(--serif); margin: 42px 0 10px; }
.legal-page p:not(.legal-date) { color: #5d6c73; line-height: 1.9; }
/* Warm, soft surfaces shared by the cat-inspired theme. */
.sound-toggle { color: #fff8ef; background: rgb(118 77 64 / 48%); border-color: rgb(255 237 226 / 68%); }
.sound-toggle:hover, .sound-toggle.on { background: rgb(190 116 101 / 78%); }
.home-music { color: #5a4039; background: radial-gradient(circle at 25% 42%, #fff7e9 0, #f7dfd1 38%, #ecc9bd 76%); border-color: #dfbbae; }
.music-cover { border-color: rgb(138 88 72 / 24%); box-shadow: 0 35px 70px rgb(116 73 58 / 22%); }
.music-copy>small, .home-visuals header small, .legal-page>small { color: #b96f64; }
.music-copy p { color: #80665e; }
.waveform span { background: linear-gradient(#d18d7f, #b86f64); }
.music-controls button { color: #684940; border-color: #bd8f80; background: rgb(255 249 243 / 45%); }
.music-controls button b { color: #fff8ef; background: #b97868; }
.music-controls>span, .playlist-meta { color: #8f7167; }
.playlist-meta a { color: #76554b; border-color: #b88b7c; }
.netease-player { background: #fffaf6; border-color: #d4a899; box-shadow: 0 20px 45px rgb(115 73 59 / 18%); }
.home-visuals { color: #503a34; background: linear-gradient(150deg,#fffaf3,#f9ece4); }
.home-visuals>header>a { border-color: #b98f81; }
.visual-grid figure { background: #ead2c6; border-radius: 24px; box-shadow: 0 18px 38px rgb(104 69 57 / 13%); }
.visual-grid figure:after { background: linear-gradient(transparent 48%, rgb(91 57 47 / 68%)); }
.visual-grid figcaption>b { color: #f5c9bd; }
.visual-grid figcaption small { color: #f2dcd3; }
.site-footer { color: #f1ddd4; background: linear-gradient(145deg,#76564c,#5f433b); border-color: #967064; }
.footer-toys { border-color: #9b7569; }
.footer-logo, .footer-grid b { color: #fff8ef; }
.footer-brand p, .footer-grid a { color: #dbc3b9; }
.footer-grid a:hover { color: #fff8ef; }
.footer-grid .footer-login { color: #6d4c42; background: #f8ded2; border-color: #f8ded2; box-shadow: 0 8px 20px rgb(55 28 22 / 16%); }
.footer-grid .footer-login:hover { color: #5d4037; background: #fff3eb; border-color: #fff3eb; }
.footer-bottom { color: #cfb2a7; border-color: #967064; }
.legal-page { color: #513b35; }
.legal-date, .legal-page p:not(.legal-date) { color: #806a62; }
@keyframes soundWave { from { transform: scaleY(.45); } to { transform: scaleY(1); } }
@media(max-width:960px) {
  .topbar nav { gap: 11px; }
  .topbar nav a { gap: 4px; }
  .nav-charm { width: 15px; height: 15px; }
  .home-music { grid-template-columns: minmax(250px, 340px) 1fr; gap: 48px; }
  .music-controls { flex-wrap: wrap; gap: 12px; }
  .music-controls>span { flex-basis: 100%; margin-left: 8px; }
  .footer-grid { grid-template-columns: 1.4fr repeat(3, 1fr); gap: 28px; }
}
@media(max-width:760px) {
  .sound-toggle { top: 16px; right: 18px; width: 40px; height: 40px; }
  .topbar nav a { gap: 12px; }
  .topbar nav .nav-charm { display: block; width: 30px; height: 30px; }
  .home-music { min-height: 0; grid-template-columns: 1fr; gap: 46px; padding: 85px 24px; }
  .music-cover { width: min(76vw, 320px); margin: auto; }
  .music-copy h2, .home-visuals h2 { font-size: 44px; }
  .music-yarn { width: 120px; }
  .music-feather { display: none; }
  .music-controls { align-items: flex-start; flex-direction: column; }
  .playlist-meta { align-items: flex-start; flex-direction: column; }
  .home-visuals { padding: 80px 20px 115px; }
  .home-visuals>header { align-items: flex-start; flex-direction: column; }
  .visual-grid { grid-template-columns: 1fr; }
  .visual-grid figure:nth-child(n) { aspect-ratio: 1.15; margin-top: 0; }
  .visual-mouse { width: 125px; right: -30px; }
  .site-footer { padding-left: 22px; padding-right: 22px; }
  .footer-toys { gap: 22px; }
  .footer-toys img, .footer-toys img:first-child, .footer-toys img:nth-child(2), .footer-toys img:last-child { width: 76px; }
  .footer-grid { grid-template-columns: 1fr 1fr; gap: 38px 25px; }
  .footer-brand { grid-column: 1/-1; }
  .footer-bottom { flex-direction: column; }
  .legal-page { padding: 65px 24px 90px; }
}
</style>
