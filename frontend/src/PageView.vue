<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from './api'

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
const posts = [
  { slug:'reliable-agent', tag:'AI ENGINEERING', zh:'从一次对话到一个可靠的 Agent', en:'From a Conversation to a Reliable Agent', date:'2026.06.04' },
  { slug:'model-routing', tag:'SPRING AI', zh:'多模型路由的简单实现', en:'A Simple Multi-model Router', date:'2026.05.26' },
  { slug:'context', tag:'JAVA', zh:'并发任务中的上下文传递', en:'Context Propagation in Concurrent Tasks', date:'2026.05.18' },
]
const provider = ref('qwen'), prompt = ref('薄雾笼罩的火山山脊，第一视角低空掠过，电影感，冷色调，自然光。')
async function generate() { busy.value=true;notice.value='';try{const r=await api<{status:string}>('/admin/ai/jobs',{method:'POST',body:JSON.stringify({provider:provider.value,type:'IMAGE',prompt:prompt.value})});notice.value=r.status}catch(e){notice.value=e instanceof Error?e.message:'Request failed'}finally{busy.value=false} }
const username=ref('wynn'), password=ref('')
async function login(){busy.value=true;notice.value='';try{await api('/auth/login',{method:'POST',body:JSON.stringify({username:username.value,password:password.value})});sessionStorage.setItem('wynn-auth','1');router.push(String(route.query.redirect||'/admin'))}catch{notice.value=text('账号或密码错误','Invalid credentials')}finally{busy.value=false}}
async function logout(){try{await api('/auth/logout',{method:'POST'})}catch{}sessionStorage.removeItem('wynn-auth');router.push('/')}

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
onMounted(()=>addEventListener('keydown',key));onBeforeUnmount(()=>{removeEventListener('keydown',key);clearInterval(timer.value)})
watch(()=>route.fullPath,()=>{mobileOpen.value=false;notice.value='';playing.value=false;soundOn.value=false;clearInterval(timer.value)})
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

  <template v-else-if="route.name==='blog'"><section class="page-head"><small>NOTES & EXPERIMENTS</small><h1>{{text('技术与思考','Technology & Thoughts')}}</h1><p>{{text('记录 Java、AI 工程与产品实践，也记录那些仍在形成中的判断。','Notes on Java, AI engineering, products, and ideas still taking shape.')}}</p></section><section class="blog-grid"><RouterLink class="feature" :to="`/blog/${posts[0].slug}`"><div><small>{{posts[0].tag}}</small><h2>{{text(posts[0].zh,posts[0].en)}}</h2><span>{{posts[0].date}} · 12 MIN</span></div></RouterLink><div class="post-list"><RouterLink v-for="(post,i) in posts.slice(1)" :key="post.slug" class="post" :to="`/blog/${post.slug}`"><div><small>{{post.tag}}</small><h2>{{text(post.zh,post.en)}}</h2><span>{{post.date}}</span></div><b>0{{i+1}}</b></RouterLink></div></section></template>

  <article v-else-if="route.name==='article'" class="article"><small>AI ENGINEERING · 2026.06.04</small><h1>{{text(posts[0].zh,posts[0].en)}}</h1><p class="lead">{{text('可靠不是让模型更聪明，而是让系统知道什么时候继续，什么时候停下来。','Reliability is not making a model smarter. It is teaching the system when to continue and when to stop.')}}</p><hr><div class="prose"><p>{{text('一个流畅的演示距离一个可长期运行的 Agent 仍有很远。真正困难的部分通常不是提示词，而是围绕模型建立清晰的状态边界。','A polished demo is still far from an agent that can run reliably. The hard part is rarely the prompt; it is the boundary around every state.')}}</p><blockquote>{{text('让每一步都有证据，让每一次失败都能回到可恢复的位置。','Give every step evidence and every failure a recoverable position.')}}</blockquote><h2>{{text('从状态开始设计','Start with state')}}</h2><pre>public sealed interface AgentState { /* waiting · running · completed */ }</pre></div></article>

  <template v-else-if="route.name==='gallery'"><section class="page-head"><small>VISUAL ARCHIVE</small><h1>{{text('影像收藏','Visual Archive')}}</h1><p>{{text('旅途中短暂出现的光、雾、风和地貌。','Light, fog, wind, and landforms found along the way.')}}</p></section><section class="gallery"><figure v-for="(name,i) in ['雾岭','穿越','风的方向','远山','无定','Fading Memory']" :key="name" :style="{backgroundPosition:`${20+i*12}% center`}"><figcaption>{{name}} · 2026</figcaption></figure></section></template>

  <section v-else-if="route.name==='studio'" class="studio"><aside><b>● {{text('仅本人可用','Owner only')}}</b><span class="on">✦ {{text('图片生成','Image')}}</span><span>▶ {{text('视频生成','Video')}}</span><span>{{text('生成历史','History')}}</span><span>{{text('服务商配置','Providers')}}</span></aside><div class="studio-main"><small>PRIVATE AI STUDIO</small><h1>{{text('把想法变成画面','Turn ideas into images')}}</h1><div class="providers"><button v-for="p in ['qwen','deepseek','relay']" :class="{on:provider===p}" @click="provider=p">{{p}}</button></div><div class="prompt"><textarea v-model="prompt"></textarea><footer><span>16:9 · HD · Realistic</span><button @click="generate" :disabled="busy">{{busy?text('生成中…','Generating…'):text('开始生成 ↗','Generate ↗')}}</button></footer></div><div class="result"><img src="/media/fluffy-kitten.png"><p>{{notice||text('生成结果将在这里出现','Your result will appear here')}}</p><small>{{text('完成后自动保存至 OSS','Saved to OSS when complete')}}</small></div></div></section>

  <section v-else-if="route.name==='about'" class="about"><div class="portrait">WY<span>PORTRAIT PLACEHOLDER</span></div><div class="about-copy"><small>ABOUT ME</small><img class="signature" src="/media/wynn-signature.png"><h1>{{text('在代码与生活之间，保留自由生长的空间。','Keep room to grow between code and life.')}}</h1><p>{{text('全栈开发者，关注 Java、Spring、AI 应用与真实产品的落地。','Full-stack developer focused on Java, Spring, applied AI, and products that reach real users.')}}</p><dl><div><dt>{{text('城市','City')}}</dt><dd>{{text('待补充','Coming soon')}}</dd></div><div><dt>{{text('邮箱','Email')}}</dt><dd>hello@example.com</dd></div><div><dt>{{text('代码','Code')}}</dt><dd>GitHub · Gitee ↗</dd></div><div><dt>{{text('更多','More')}}</dt><dd>{{text('闲鱼 · 微信','Xianyu · WeChat')}} ↗</dd></div></dl><button :class="['cat about-cat',{dragging:catDragging}]" :style="catStyle" :aria-label="text('拖动小猫','Drag kitten')" @pointerdown="startCatDrag" @pointermove="moveCat" @pointerup="endCatDrag" @pointercancel="endCatDrag" @click="catClick"><img src="/media/fluffy-kitten.png" draggable="false"></button></div></section>

  <template v-else-if="route.name==='games'"><section class="page-head"><small>SMALL WORLDS</small><h1>{{text('玩一会儿','Play for a while')}}</h1><p>{{text('三个轻量小游戏，随时开始，也随时离开。','Three small games. Start and leave whenever you like.')}}</p></section><section class="game-grid"><RouterLink v-for="game in games" :key="game.id" :to="`/games/${game.id}`" class="game-card"><b>{{game.no}}</b><strong>{{game.icon}}</strong><small>{{game.id}}</small><h2>{{text(game.zh,game.en)}}</h2><p>{{text(game.dzh,game.den)}}</p><span>{{text('开始游戏','Play')}} →</span></RouterLink></section></template>

  <section v-else-if="route.name==='game'" class="game-page"><div class="game-title"><RouterLink to="/games">← {{text('返回','Back')}}</RouterLink><b>{{text(games.find(g=>g.id===gameId)?.zh||'',games.find(g=>g.id===gameId)?.en||'')}}</b><span>SCORE {{score}}</span></div><div v-if="gameId==='flight'" class="flight" @click="startFlight"><img src="/media/hero.jpg"><span class="plane" :style="{left:planeX+'%'}">✈</span><button>{{playing?text('暂停','Pause'):text('点击开始','Click to start')}}</button></div><div v-else-if="gameId==='memory'" class="memory"><button v-for="(card,i) in memory" :key="i" :class="{open:card.open||card.done}" @click="flip(i)">{{card.open||card.done?card.value:'✦'}}</button></div><div v-else class="stack"><button v-for="(cell,i) in board" :key="i" @click="stackMove">{{cell||''}}</button><p>{{text('方向键或点击任意格移动','Use arrow keys or click any tile')}}</p></div></section>

  <article v-else-if="route.name==='privacy'||route.name==='terms'" class="legal-page"><small>WYNN YALE YOX · LEGAL</small><h1>{{route.name==='privacy'?text('隐私政策','Privacy Policy'):text('使用条款','Terms of Use')}}</h1><p class="legal-date">{{text('更新日期：2026 年 9 月 20 日','Updated: September 20, 2026')}}</p><template v-if="route.name==='privacy'"><h2>{{text('我们收集什么','What we collect')}}</h2><p>{{text('本站仅收集维持账户登录、内容管理和 AI 创作功能所必需的数据。访客浏览公开页面时，不要求提交个人信息。','This site only collects data needed for account login, content management, and private AI creation. Visitors do not need to submit personal information to view public pages.')}}</p><h2>{{text('数据如何使用','How data is used')}}</h2><p>{{text('数据仅用于提供本站功能、保障安全和排查故障。未经你的明确同意，不会出售或公开个人数据。','Data is used to operate the site, protect it, and diagnose problems. Personal data is not sold or disclosed without explicit consent.')}}</p><h2>{{text('存储与联系','Storage and contact')}}</h2><p>{{text('媒体文件可能存储于阿里云 OSS。需要查询或删除个人数据时，请通过关于页面所列联系方式联系站点所有者。','Media may be stored in Alibaba Cloud OSS. Contact the site owner through the About page to request access to or deletion of personal data.')}}</p></template><template v-else><h2>{{text('内容与访问','Content and access')}}</h2><p>{{text('公开内容仅供阅读和个人欣赏。管理后台与 AI 创作功能仅限站点所有者登录后使用。','Public content is provided for reading and personal enjoyment. The admin console and AI studio are reserved for the signed-in site owner.')}}</p><h2>{{text('知识产权','Intellectual property')}}</h2><p>{{text('除另有说明外，本站文字、图片、视频和品牌元素的权利归站点所有者所有。引用时请注明来源。','Unless stated otherwise, site text, images, video, and brand elements belong to the site owner. Please credit the source when quoting.')}}</p><h2>{{text('服务变更','Service changes')}}</h2><p>{{text('本站可能随时调整功能与内容；涉及重要规则的变更会在本页更新。','Features and content may change over time. Material rule changes will be reflected on this page.')}}</p></template></article>

  <section v-else-if="route.name==='login'" class="login"><form @submit.prevent="login"><img src="/media/wynn-mark.png"><h1>{{text('回到你的空间','Welcome back')}}</h1><p>{{text('个人管理后台与 AI 创作室','Private console and AI studio')}}</p><label>{{text('账号','Account')}}<input v-model="username"></label><label>{{text('密码','Password')}}<input v-model="password" type="password"></label><button :disabled="busy">{{text('安全登录','Sign in')}} →</button><small>{{notice||text('仅限站点所有者访问','Owner access only')}}</small></form></section>

  <section v-else-if="route.name==='admin'" class="admin"><aside><RouterLink class="brand" to="/"><img src="/media/wynn-mark.png"><span>WYNN CMS</span></RouterLink><b>⌂　{{text('总览','Overview')}}</b><span>▤　{{text('文章管理','Posts')}}</span><span>▧　{{text('媒体管理','Media')}}</span><span>✦　{{text('AI 生成记录','AI Jobs')}}</span><span>⚙　{{text('服务商配置','Providers')}}</span><button @click="logout">{{text('退出登录','Sign out')}}</button></aside><div class="admin-main"><header><div><small>PERSONAL CONSOLE</small><h1>{{text('下午好，Wynn','Good afternoon, Wynn')}}</h1></div><img src="/media/fluffy-kitten.png"></header><div class="stats"><article><span>{{text('已发布文章','Posts')}}</span><b>24</b></article><article><span>{{text('影像作品','Images')}}</span><b>86</b></article><article><span>{{text('AI 任务','AI jobs')}}</span><b>132</b></article><article><span>{{text('本月访问','Visits')}}</span><b>4.8k</b></article></div><div class="admin-panels"><article><h2>{{text('最近内容','Recent content')}}</h2><p v-for="post in posts" :key="post.slug"><b>{{text(post.zh,post.en)}}</b><span>{{post.tag}}</span></p></article><article><h2>{{text('AI 服务状态','AI providers')}}</h2><p><b>Qwen</b><i></i></p><p><b>DeepSeek</b><i></i></p><p><b>Relay</b><em>{{text('待配置','Pending')}}</em></p></article></div></div></section>
  <footer v-if="!['admin','login'].includes(String(route.name))" class="site-footer"><div class="footer-toys" aria-hidden="true"><img src="/media/cat-items/food.png" alt=""><img src="/media/cat-items/fish-two.png" alt=""><img src="/media/cat-items/paw.png" alt=""></div><div class="footer-grid"><div class="footer-brand"><div class="footer-logo"><img src="/media/wynn-mark.png" alt=""><span>WYNN YALE YOX</span></div><p>{{text('写代码，收集影像，也为偶然出现的灵感留一块柔软的地方。','Code, images, and a soft corner for ideas that arrive by chance.')}}</p></div><div><b>{{text('探索','Explore')}}</b><RouterLink to="/blog">{{text('技术博客','Tech blog')}}</RouterLink><RouterLink to="/gallery">{{text('影像收藏','Gallery')}}</RouterLink><RouterLink to="/games">{{text('小游戏','Games')}}</RouterLink></div><div><b>{{text('空间','Space')}}</b><RouterLink to="/about">{{text('关于我','About')}}</RouterLink><RouterLink to="/studio">{{text('AI 创作室','AI Studio')}}</RouterLink><a href="https://github.com/LonlyRunner" target="_blank" rel="noreferrer">GitHub ↗</a></div><div><b>{{text('协议','Legal')}}</b><RouterLink to="/privacy">{{text('隐私政策','Privacy')}}</RouterLink><RouterLink to="/terms">{{text('使用条款','Terms')}}</RouterLink><RouterLink to="/login">{{text('所有者登录','Owner login')}}</RouterLink></div></div><div class="footer-bottom"><span>© 2026 WYNN YALE YOX</span><span>{{text('随性而行，无拘无定。','Move freely, remain undefined.')}}</span></div></footer>
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
.footer-bottom { display: flex; justify-content: space-between; gap: 20px; padding-top: 22px; border-top: 1px solid #27343a; color: #63747c; font-size: 9px; letter-spacing: .16em; }
.legal-page { max-width: 850px; min-height: 70vh; margin: 0 auto; padding: 90px 6vw 120px; color: #11191d; }
.legal-page h1 { font: 500 clamp(48px, 6vw, 76px)/1 var(--serif); margin: 16px 0; }
.legal-date { color: #7a8a91; font-size: 11px; margin-bottom: 58px; }
.legal-page h2 { font: 28px var(--serif); margin: 42px 0 10px; }
.legal-page p:not(.legal-date) { color: #5d6c73; line-height: 1.9; }
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
