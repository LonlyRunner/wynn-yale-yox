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
  <header v-if="route.name!=='admin'" class="topbar"><RouterLink class="brand" to="/"><img src="/media/wynn-mark.png"><span>WYNN YALE YOX</span></RouterLink><nav :class="{open:mobileOpen}"><RouterLink to="/">{{text('首页','Home')}}</RouterLink><RouterLink to="/blog">{{text('博客','Blog')}}</RouterLink><RouterLink to="/gallery">{{text('影像','Gallery')}}</RouterLink><RouterLink to="/studio">{{text('AI 创作','AI Studio')}}</RouterLink><RouterLink to="/games">{{text('游戏','Games')}}</RouterLink><RouterLink to="/about">{{text('关于','About')}}</RouterLink></nav><div class="nav-actions"><button class="pill" @click="toggleLang">{{lang==='zh'?'中 / EN':'EN / 中'}}</button><button class="menu" @click="mobileOpen=!mobileOpen">☰</button></div></header>

  <section v-if="route.name==='home'" class="hero"><video ref="heroVideo" autoplay :muted="!soundOn" loop playsinline poster="/media/hero.jpg"><source src="/media/hero.mp4" type="video/mp4"></video><div class="shade"></div><div class="hero-copy"><small>PERSONAL DIGITAL GARDEN · 2026</small><h1>Wynn<br>Yale Yox</h1><i></i><p>{{text('随性而行，无拘无定。','Move freely, remain undefined.')}}</p></div><button class="sound-toggle" :class="{on:soundOn}" :aria-label="soundOn?text('关闭音乐','Mute music'):text('打开音乐','Play music')" :title="soundOn?text('关闭音乐','Mute music'):text('打开音乐','Play music')" @click="toggleSound"><svg v-if="soundOn" viewBox="0 0 24 24" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/></svg><svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="m15 9 6 6M21 9l-6 6"/></svg></button><button :class="['cat home-cat',{dragging:catDragging}]" :style="catStyle" :aria-label="text('拖动小猫','Drag kitten')" @pointerdown="startCatDrag" @pointermove="moveCat" @pointerup="endCatDrag" @pointercancel="endCatDrag" @click="catClick"><img src="/media/fluffy-kitten.png" draggable="false"></button><span v-if="catTalk" class="bubble" :style="catStyle">{{text('喵～欢迎回来 ✦','Meow — welcome back ✦')}}</span><span class="scroll">—　{{text('向下探索','Explore')}}</span></section>

  <template v-else-if="route.name==='blog'"><section class="page-head"><small>NOTES & EXPERIMENTS</small><h1>{{text('技术与思考','Technology & Thoughts')}}</h1><p>{{text('记录 Java、AI 工程与产品实践，也记录那些仍在形成中的判断。','Notes on Java, AI engineering, products, and ideas still taking shape.')}}</p></section><section class="blog-grid"><RouterLink class="feature" :to="`/blog/${posts[0].slug}`"><div><small>{{posts[0].tag}}</small><h2>{{text(posts[0].zh,posts[0].en)}}</h2><span>{{posts[0].date}} · 12 MIN</span></div></RouterLink><div class="post-list"><RouterLink v-for="(post,i) in posts.slice(1)" :key="post.slug" class="post" :to="`/blog/${post.slug}`"><div><small>{{post.tag}}</small><h2>{{text(post.zh,post.en)}}</h2><span>{{post.date}}</span></div><b>0{{i+1}}</b></RouterLink></div></section></template>

  <article v-else-if="route.name==='article'" class="article"><small>AI ENGINEERING · 2026.06.04</small><h1>{{text(posts[0].zh,posts[0].en)}}</h1><p class="lead">{{text('可靠不是让模型更聪明，而是让系统知道什么时候继续，什么时候停下来。','Reliability is not making a model smarter. It is teaching the system when to continue and when to stop.')}}</p><hr><div class="prose"><p>{{text('一个流畅的演示距离一个可长期运行的 Agent 仍有很远。真正困难的部分通常不是提示词，而是围绕模型建立清晰的状态边界。','A polished demo is still far from an agent that can run reliably. The hard part is rarely the prompt; it is the boundary around every state.')}}</p><blockquote>{{text('让每一步都有证据，让每一次失败都能回到可恢复的位置。','Give every step evidence and every failure a recoverable position.')}}</blockquote><h2>{{text('从状态开始设计','Start with state')}}</h2><pre>public sealed interface AgentState { /* waiting · running · completed */ }</pre></div></article>

  <template v-else-if="route.name==='gallery'"><section class="page-head"><small>VISUAL ARCHIVE</small><h1>{{text('影像收藏','Visual Archive')}}</h1><p>{{text('旅途中短暂出现的光、雾、风和地貌。','Light, fog, wind, and landforms found along the way.')}}</p></section><section class="gallery"><figure v-for="(name,i) in ['雾岭','穿越','风的方向','远山','无定','Fading Memory']" :key="name" :style="{backgroundPosition:`${20+i*12}% center`}"><figcaption>{{name}} · 2026</figcaption></figure></section></template>

  <section v-else-if="route.name==='studio'" class="studio"><aside><b>● {{text('仅本人可用','Owner only')}}</b><span class="on">✦ {{text('图片生成','Image')}}</span><span>▶ {{text('视频生成','Video')}}</span><span>{{text('生成历史','History')}}</span><span>{{text('服务商配置','Providers')}}</span></aside><div class="studio-main"><small>PRIVATE AI STUDIO</small><h1>{{text('把想法变成画面','Turn ideas into images')}}</h1><div class="providers"><button v-for="p in ['qwen','deepseek','relay']" :class="{on:provider===p}" @click="provider=p">{{p}}</button></div><div class="prompt"><textarea v-model="prompt"></textarea><footer><span>16:9 · HD · Realistic</span><button @click="generate" :disabled="busy">{{busy?text('生成中…','Generating…'):text('开始生成 ↗','Generate ↗')}}</button></footer></div><div class="result"><img src="/media/fluffy-kitten.png"><p>{{notice||text('生成结果将在这里出现','Your result will appear here')}}</p><small>{{text('完成后自动保存至 OSS','Saved to OSS when complete')}}</small></div></div></section>

  <section v-else-if="route.name==='about'" class="about"><div class="portrait">WY<span>PORTRAIT PLACEHOLDER</span></div><div class="about-copy"><small>ABOUT ME</small><img class="signature" src="/media/wynn-signature.png"><h1>{{text('在代码与生活之间，保留自由生长的空间。','Keep room to grow between code and life.')}}</h1><p>{{text('全栈开发者，关注 Java、Spring、AI 应用与真实产品的落地。','Full-stack developer focused on Java, Spring, applied AI, and products that reach real users.')}}</p><dl><div><dt>{{text('城市','City')}}</dt><dd>{{text('待补充','Coming soon')}}</dd></div><div><dt>{{text('邮箱','Email')}}</dt><dd>hello@example.com</dd></div><div><dt>{{text('代码','Code')}}</dt><dd>GitHub · Gitee ↗</dd></div><div><dt>{{text('更多','More')}}</dt><dd>{{text('闲鱼 · 微信','Xianyu · WeChat')}} ↗</dd></div></dl><button :class="['cat about-cat',{dragging:catDragging}]" :style="catStyle" :aria-label="text('拖动小猫','Drag kitten')" @pointerdown="startCatDrag" @pointermove="moveCat" @pointerup="endCatDrag" @pointercancel="endCatDrag" @click="catClick"><img src="/media/fluffy-kitten.png" draggable="false"></button></div></section>

  <template v-else-if="route.name==='games'"><section class="page-head"><small>SMALL WORLDS</small><h1>{{text('玩一会儿','Play for a while')}}</h1><p>{{text('三个轻量小游戏，随时开始，也随时离开。','Three small games. Start and leave whenever you like.')}}</p></section><section class="game-grid"><RouterLink v-for="game in games" :key="game.id" :to="`/games/${game.id}`" class="game-card"><b>{{game.no}}</b><strong>{{game.icon}}</strong><small>{{game.id}}</small><h2>{{text(game.zh,game.en)}}</h2><p>{{text(game.dzh,game.den)}}</p><span>{{text('开始游戏','Play')}} →</span></RouterLink></section></template>

  <section v-else-if="route.name==='game'" class="game-page"><div class="game-title"><RouterLink to="/games">← {{text('返回','Back')}}</RouterLink><b>{{text(games.find(g=>g.id===gameId)?.zh||'',games.find(g=>g.id===gameId)?.en||'')}}</b><span>SCORE {{score}}</span></div><div v-if="gameId==='flight'" class="flight" @click="startFlight"><img src="/media/hero.jpg"><span class="plane" :style="{left:planeX+'%'}">✈</span><button>{{playing?text('暂停','Pause'):text('点击开始','Click to start')}}</button></div><div v-else-if="gameId==='memory'" class="memory"><button v-for="(card,i) in memory" :key="i" :class="{open:card.open||card.done}" @click="flip(i)">{{card.open||card.done?card.value:'✦'}}</button></div><div v-else class="stack"><button v-for="(cell,i) in board" :key="i" @click="stackMove">{{cell||''}}</button><p>{{text('方向键或点击任意格移动','Use arrow keys or click any tile')}}</p></div></section>

  <section v-else-if="route.name==='login'" class="login"><form @submit.prevent="login"><img src="/media/wynn-mark.png"><h1>{{text('回到你的空间','Welcome back')}}</h1><p>{{text('个人管理后台与 AI 创作室','Private console and AI studio')}}</p><label>{{text('账号','Account')}}<input v-model="username"></label><label>{{text('密码','Password')}}<input v-model="password" type="password"></label><button :disabled="busy">{{text('安全登录','Sign in')}} →</button><small>{{notice||text('仅限站点所有者访问','Owner access only')}}</small></form></section>

  <section v-else-if="route.name==='admin'" class="admin"><aside><RouterLink class="brand" to="/"><img src="/media/wynn-mark.png"><span>WYNN CMS</span></RouterLink><b>⌂　{{text('总览','Overview')}}</b><span>▤　{{text('文章管理','Posts')}}</span><span>▧　{{text('媒体管理','Media')}}</span><span>✦　{{text('AI 生成记录','AI Jobs')}}</span><span>⚙　{{text('服务商配置','Providers')}}</span><button @click="logout">{{text('退出登录','Sign out')}}</button></aside><div class="admin-main"><header><div><small>PERSONAL CONSOLE</small><h1>{{text('下午好，Wynn','Good afternoon, Wynn')}}</h1></div><img src="/media/fluffy-kitten.png"></header><div class="stats"><article><span>{{text('已发布文章','Posts')}}</span><b>24</b></article><article><span>{{text('影像作品','Images')}}</span><b>86</b></article><article><span>{{text('AI 任务','AI jobs')}}</span><b>132</b></article><article><span>{{text('本月访问','Visits')}}</span><b>4.8k</b></article></div><div class="admin-panels"><article><h2>{{text('最近内容','Recent content')}}</h2><p v-for="post in posts" :key="post.slug"><b>{{text(post.zh,post.en)}}</b><span>{{post.tag}}</span></p></article><article><h2>{{text('AI 服务状态','AI providers')}}</h2><p><b>Qwen</b><i></i></p><p><b>DeepSeek</b><i></i></p><p><b>Relay</b><em>{{text('待配置','Pending')}}</em></p></article></div></div></section>
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
@media(max-width:760px) { .sound-toggle { top: 16px; right: 18px; width: 40px; height: 40px; } }
</style>
