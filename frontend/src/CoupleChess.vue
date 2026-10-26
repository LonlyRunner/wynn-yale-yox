<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  acceptTask, calculateNewPosition, canStart, checkTile, createThemeId, drawCard, loadState, mineTask,
  rejectTask, revealMine, rollDice, saveState, startMatch, themesFor, type ChessState, type GameMode,
  type MineTaskChoice, type TaskCard, type TaskEventData, type Theme, type ThemeAudience, type ThemeMode,
} from './coupleChess'

const emit = defineEmits<{ back: [] }>()
const state = ref<ChessState>(loadState())
const notice = ref('')
const taskOpen = ref<TaskEventData | null>(null)
const flipped = ref(false)
const winner = ref<number | null>(null)
const bombOpen = ref(false)
const rolling = ref(false)
const moving = ref(false)
const diceFace = ref<number | null>(null)
const editing = ref<string | null>(null)
const creating = ref(false)
const draftName = ref('新的相处主题')
const draftDesc = ref('我们自己写的小任务')
const draftAudience = ref<ThemeAudience>('common')
const draftModes = ref<ThemeMode[]>(['board', 'card'])
const draftTask = ref('')
const importing = ref(false)
const importText = ref('')
const importMode = ref<'append' | 'replace'>('append')
const diceRolling = ref(false)
const diceAction = ref<number | null>(null)
const diceBody = ref<number | null>(null)
const diceShown = ref(false)
let moveTimer = 0

const modes: { id: GameMode; title: string; desc: string }[] = [
  { id: 'board', title: '飞行棋', desc: '掷骰走棋，落到幸运、陷阱或相遇时翻卡' },
  { id: 'card', title: '任务卡', desc: '轮流抽一张卡，不用走棋' },
  { id: 'mine', title: '翻格', desc: '翻开真心话、小冒险、主题或空白' },
  { id: 'dice', title: '双骰', desc: '一颗动作、一颗位置，组成今晚的相处' },
]
const allModes: { id: ThemeMode; label: string }[] = [
  { id: 'board', label: '飞行棋' }, { id: 'card', label: '抽卡' }, { id: 'mineTruth', label: '真心话' },
  { id: 'mineDare', label: '小冒险' }, { id: 'mineTheme', label: '翻格主题' }, { id: 'diceAction', label: '动作骰' }, { id: 'diceBody', label: '位置骰' },
]
const audienceLabel: Record<ThemeAudience, string> = { common: '双方', male: '男方', female: '女方' }
const active = computed(() => state.value.players[state.value.turn])
const ready = computed(() => canStart(state.value))
const editingTheme = computed(() => state.value.themes.find(item => item.id === editing.value) || null)
const boardCells = computed(() => {
  const lookup = new Map(state.value.pathCoords.map((coord, index) => [`${coord.r},${coord.c}`, index]))
  return Array.from({ length: 49 }, (_, cell) => {
    const index = lookup.get(`${Math.floor(cell / 7)},${cell % 7}`) ?? 0
    return { index, type: state.value.boardMap[index], start: index === 0, end: index === 48 }
  })
})
const mineLeft = computed(() => state.value.mineBoard.filter(tile => !tile.revealed).length)

watch(state, value => saveState(value), { deep: true })
onBeforeUnmount(() => window.clearTimeout(moveTimer))

function patch(next: ChessState) { state.value = next }
function selectMode(mode: GameMode) { patch({ ...state.value, gameMode: mode, players: state.value.players.map(player => ({ ...player, themeId: null })) }); notice.value = '' }
function selectTheme(playerId: number, themeId: string) { patch({ ...state.value, players: state.value.players.map(player => player.id === playerId ? { ...player, themeId } : player) }) }
function playerThemes(playerId: number) {
  const player = state.value.players[playerId]
  if (state.value.gameMode === 'dice') return themesFor(state.value, player.role, playerId === 0 ? 'diceAction' : 'diceBody')
  if (state.value.gameMode === 'mine') return themesFor(state.value, player.role, 'mineTheme')
  return themesFor(state.value, player.role, state.value.gameMode)
}
function begin() {
  if (!ready.value) { notice.value = '先为双方选好这一模式可用的主题'; return }
  winner.value = null; diceFace.value = null; diceShown.value = false
  patch(startMatch(state.value))
}
function showTask(task: TaskEventData | null) {
  if (!task) { notice.value = '这个主题里还没有任务'; return }
  flipped.value = false; taskOpen.value = task
}
function endTurn() { patch({ ...state.value, turn: state.value.turn === 0 ? 1 : 0 }) }
function finishTask(accepted: boolean) {
  if (!taskOpen.value) return
  patch(accepted ? acceptTask(state.value, taskOpen.value) : rejectTask(state.value, taskOpen.value))
  taskOpen.value = null
}
function rollBoard() {
  if (rolling.value || moving.value || diceFace.value) return
  rolling.value = true
  const face = rollDice()
  window.setTimeout(() => { diceFace.value = face; rolling.value = false; walk(face) }, 700)
}
function walk(steps: number) {
  moving.value = true
  let moved = 0
  const stepOnce = () => {
    const player = state.value.players[state.value.turn]
    const players = state.value.players.map(item => item.id === player.id ? { ...item, step: calculateNewPosition(item.step, 1) } : item)
    patch({ ...state.value, players })
    moved += 1
    if (moved < steps) { moveTimer = window.setTimeout(stepOnce, 220); return }
    moveTimer = window.setTimeout(() => {
      const landing = state.value.players[state.value.turn].step
      const result = checkTile(state.value, landing)
      if (result === 'win') winner.value = state.value.turn
      else if (result) showTask(result)
      else endTurn()
      diceFace.value = null; moving.value = false
    }, 220)
  }
  moveTimer = window.setTimeout(stepOnce, 220)
}
function openMine(index: number) {
  if (taskOpen.value || bombOpen.value) return
  const revealed = revealMine(state.value, index)
  patch(revealed.state)
  if (!revealed.result || revealed.result.type === 'blank') { if (revealed.result) endTurn(); return }
  if (revealed.result.type === 'bomb') { bombOpen.value = true; return }
  showTask(revealed.result.task)
}
function chooseBomb(choice: MineTaskChoice) {
  const other = state.value.players[state.value.turn === 0 ? 1 : 0]
  bombOpen.value = false
  showTask(mineTask(state.value, other.id, choice, active.value.id))
}
function rollWords() {
  const actionTheme = state.value.themes.find(item => item.id === state.value.players[0].themeId)
  const bodyTheme = state.value.themes.find(item => item.id === state.value.players[1].themeId)
  if (!actionTheme?.tasks.length || !bodyTheme?.tasks.length || diceRolling.value) return
  diceRolling.value = true; diceShown.value = false
  window.setTimeout(() => {
    diceAction.value = Math.floor(Math.random() * actionTheme.tasks.length)
    diceBody.value = Math.floor(Math.random() * bodyTheme.tasks.length)
    diceRolling.value = false; diceShown.value = true
  }, 700)
}
function actionWord() { const theme = state.value.themes.find(item => item.id === state.value.players[0].themeId); return diceAction.value == null ? '' : theme?.tasks[diceAction.value]?.text || '' }
function bodyWord() { const theme = state.value.themes.find(item => item.id === state.value.players[1].themeId); return diceBody.value == null ? '' : theme?.tasks[diceBody.value]?.text || '' }
function goHome() { patch({ ...state.value, view: 'home' }); taskOpen.value = null; bombOpen.value = false; winner.value = null }
function resetAll() { localStorage.removeItem('wynn-couple-chess-v1'); state.value = loadState(); notice.value = '已清空本机进度和自建主题' }

function openCreate() { creating.value = true; draftName.value = '新的相处主题'; draftDesc.value = '我们自己写的小任务'; draftAudience.value = 'common'; draftModes.value = ['board', 'card'] }
function saveCreate() {
  if (!draftName.value.trim() || !draftModes.value.length) return
  const theme: Theme = { id: createThemeId(state.value.themes), name: draftName.value.trim(), desc: draftDesc.value.trim(), audience: draftAudience.value, modes: [...draftModes.value], tasks: [] }
  patch({ ...state.value, themes: [...state.value.themes, theme] })
  creating.value = false; editing.value = theme.id
}
function toggleDraftMode(mode: ThemeMode) { draftModes.value = draftModes.value.includes(mode) ? draftModes.value.filter(item => item !== mode) : [...draftModes.value, mode] }
function updateTheme(patchTheme: Partial<Theme>) {
  if (!editing.value) return
  patch({ ...state.value, themes: state.value.themes.map(item => item.id === editing.value ? { ...item, ...patchTheme } : item) })
}
function toggleThemeMode(mode: ThemeMode) {
  const theme = editingTheme.value
  if (!theme || theme.id.startsWith('soft-') || theme.id.startsWith('little-') || theme.id.startsWith('cozy-') || theme.id.startsWith('gentle-')) return
  const modes = theme.modes.includes(mode) ? theme.modes.filter(item => item !== mode) : [...theme.modes, mode]
  if (modes.length) updateTheme({ modes })
}
function addDraftTask() {
  const theme = editingTheme.value
  const text = draftTask.value.trim()
  if (!theme || !text) return
  const card: TaskCard = { text, executor: 'both', moveDelta: 0 }
  if (theme.tasks.some(item => item.text === text)) { draftTask.value = ''; return }
  updateTheme({ tasks: [...theme.tasks, card] }); draftTask.value = ''
}
function removeTask(index: number) {
  const theme = editingTheme.value
  if (!theme) return
  updateTheme({ tasks: theme.tasks.filter((_, taskIndex) => taskIndex !== index) })
}
function runImport() {
  const theme = editingTheme.value
  if (!theme) return
  const tasks = importText.value.split(/\r?\n/).map(line => line.trim()).filter(Boolean).map(text => ({ text, executor: 'both' as const, moveDelta: 0 }))
  const base = importMode.value === 'replace' ? [] : theme.tasks
  const merged = [...base]
  tasks.forEach(card => { if (!merged.some(item => item.text === card.text)) merged.push(card) })
  updateTheme({ tasks: merged }); importing.value = false; importText.value = ''
}
const preset = (id: string) => DEFAULT_PRESET.has(id)
const DEFAULT_PRESET = new Set(['soft-date', 'little-questions', 'cozy-dares', 'gentle-actions', 'gentle-places'])
function removeTheme(id: string) {
  if (preset(id)) return
  patch({ ...state.value, themes: state.value.themes.filter(item => item.id !== id), players: state.value.players.map(player => player.themeId === id ? { ...player, themeId: null } : player) })
  if (editing.value === id) editing.value = null
}
function pieceStyle(id: number) {
  const player = state.value.players[id]
  const coord = state.value.pathCoords[player.step]
  const same = state.value.players.filter(item => item.step === player.step)
  const offset = same.length > 1 ? (same.findIndex(item => item.id === id) === 0 ? -6 : 6) : 0
  return { top: `${(coord.r / 7) * 100}%`, left: `${(coord.c / 7) * 100}%`, transform: `translate(${offset}px, ${offset}px)` }
}
</script>

<template>
  <div class="couple-chess">
    <div class="couple-glow couple-glow-a"></div><div class="couple-glow couple-glow-b"></div>
    <header class="couple-bar">
      <button type="button" @click="state.view === 'home' ? emit('back') : goHome()">← {{ state.view === 'home' ? '返回游戏列表' : '返回准备' }}</button>
      <b>{{ active.name }}的回合</b>
      <button type="button" @click="patch({ ...state, view: 'themes' })">主题</button>
    </header>

    <section v-if="state.view === 'home'" class="couple-home">
      <div class="couple-stack">
        <article v-for="(mode, index) in modes" :key="mode.id" :class="{ on: state.gameMode === mode.id }" :style="{ '--stack': index }" @click="selectMode(mode.id)">
          <small>0{{ index + 1 }}</small><h2>{{ mode.title }}</h2><p>{{ mode.desc }}</p>
        </article>
      </div>
      <div class="couple-picker">
        <div v-for="player in state.players" :key="player.id">
          <b :style="{ color: player.color }">{{ state.gameMode === 'dice' ? (player.id === 0 ? '动作骰' : '位置骰') : player.name }}</b>
          <div class="couple-theme-row">
            <button v-for="theme in playerThemes(player.id)" :key="theme.id" type="button" :class="{ on: player.themeId === theme.id }" @click="selectTheme(player.id, theme.id)">{{ theme.name }}</button>
          </div>
        </div>
        <p>{{ notice || '选好双方主题后再开始。任务都是对视、拥抱、真心话这一类相处。' }}</p>
        <button class="couple-start" type="button" :disabled="!ready" @click="begin">开始</button>
      </div>
    </section>

    <section v-else-if="state.view === 'game'" class="couple-play">
      <div class="couple-board">
        <button v-for="cell in boardCells" :key="cell.index" type="button" :class="['tile', cell.type, { start: cell.start, end: cell.end }]">{{ cell.start ? '起' : cell.end ? '终' : cell.type === 'lucky' ? '幸' : cell.type === 'trap' ? '陷' : '' }}</button>
        <span v-for="player in state.players" :key="player.id" class="piece" :class="{ on: player.id === state.turn }" :style="pieceStyle(player.id)"><i :style="{ background: player.color }">{{ player.id === 0 ? '男' : '女' }}</i></span>
      </div>
      <button class="couple-die" type="button" :disabled="rolling || moving" @click="rollBoard">{{ rolling ? '…' : diceFace || '掷' }}</button>
    </section>

    <section v-else-if="state.view === 'card'" class="couple-card-view">
      <article class="couple-sheet">
        <small>当前主题</small>
        <h2>{{ state.themes.find(item => item.id === active.themeId)?.name }}</h2>
        <p>{{ active.name }}抽取一张相处卡。完成后换对方。</p>
        <button type="button" @click="showTask(drawCard(state))">抽一张</button>
      </article>
    </section>

    <section v-else-if="state.view === 'mine'" class="couple-play">
      <p class="couple-left">还剩 {{ mineLeft }} 格 · {{ active.name }}翻开</p>
      <div class="couple-mine">
        <button v-for="(tile, index) in state.mineBoard" :key="index" type="button" :class="{ open: tile.revealed, [tile.type]: tile.revealed }" @click="openMine(index)">{{ tile.revealed ? ({ bomb: '停', truth: '问', dare: '做', blank: '空', theme: '题' }[tile.type]) : '' }}</button>
      </div>
    </section>

    <section v-else-if="state.view === 'dice'" class="couple-card-view">
      <div class="couple-dice-pair" :class="{ rolling: diceRolling }">
        <article><small>动作</small><b>{{ diceShown ? actionWord() : '？' }}</b></article>
        <article><small>位置</small><b>{{ diceShown ? bodyWord() : '？' }}</b></article>
      </div>
      <p v-if="diceShown">{{ active.name }}：{{ actionWord() }} · {{ bodyWord() }}</p>
      <div class="couple-actions"><button type="button" @click="rollWords">{{ diceRolling ? '摇晃中' : '摇一次' }}</button><button type="button" :disabled="!diceShown" @click="endTurn(); diceShown = false">换对方</button></div>
    </section>

    <section v-else class="couple-themes">
      <div class="couple-theme-list">
        <article v-for="theme in state.themes" :key="theme.id" class="couple-sheet" @click="editing = theme.id">
          <small>{{ audienceLabel[theme.audience] }} · {{ theme.tasks.length }} 条</small>
          <h2>{{ theme.name }}</h2><p>{{ theme.desc }}</p>
          <button v-if="!preset(theme.id)" type="button" @click.stop="removeTheme(theme.id)">删除</button>
        </article>
        <button class="couple-sheet couple-add" type="button" @click="openCreate">＋ 新建主题</button>
      </div>
      <form v-if="editingTheme" class="couple-editor" @submit.prevent="addDraftTask">
        <h2>{{ editingTheme.name }}</h2>
        <label>名称<input :value="editingTheme.name" :disabled="preset(editingTheme.id)" @input="updateTheme({ name: ($event.target as HTMLInputElement).value })"></label>
        <label>说明<input :value="editingTheme.desc" :disabled="preset(editingTheme.id)" @input="updateTheme({ desc: ($event.target as HTMLInputElement).value })"></label>
        <div class="couple-theme-row">
          <button v-for="mode in allModes" :key="mode.id" type="button" :class="{ on: editingTheme.modes.includes(mode.id) }" :disabled="preset(editingTheme.id)" @click="toggleThemeMode(mode.id)">{{ mode.label }}</button>
        </div>
        <ul><li v-for="(card, index) in editingTheme.tasks" :key="`${card.text}-${index}`"><span>{{ card.text }}</span><button v-if="!preset(editingTheme.id)" type="button" @click="removeTask(index)">移除</button></li></ul>
        <div v-if="!preset(editingTheme.id)" class="couple-actions"><input v-model="draftTask" placeholder="写一条非露骨的相处任务"><button type="submit">添加</button><button type="button" @click="importing = true">批量导入</button></div>
        <p v-else>内置主题保持原样，可以另建主题写入自己的任务。</p>
      </form>
    </section>

    <div v-if="taskOpen" class="couple-mask" @click.self="flipped = true">
      <article :class="['couple-flip', { flipped }]">
        <button class="face front" type="button" @click="flipped = true"><small>{{ taskOpen.title }}</small><b>翻开</b><span>{{ taskOpen.subtitle }}</span></button>
        <div class="face back"><h2>{{ taskOpen.task }}</h2><p>由{{ taskOpen.executorPlayerId === 0 ? '男方' : '女方' }}完成</p><div class="couple-actions"><button type="button" @click="finishTask(true)">完成</button><button type="button" @click="finishTask(false)">{{ taskOpen.type === 'collision' ? '回到起点' : taskOpen.type === 'lucky' || taskOpen.type === 'trap' ? '后退 1–3 格' : '跳过' }}</button></div></div>
      </article>
    </div>
    <div v-if="bombOpen" class="couple-mask">
      <article class="couple-sheet couple-modal"><h2>翻到暂停格</h2><p>请为对方选一种相处。</p><div class="couple-actions"><button type="button" @click="chooseBomb('truth')">真心话</button><button type="button" @click="chooseBomb('dare')">小冒险</button><button type="button" @click="chooseBomb('theme')">对方主题</button></div></article>
    </div>
    <div v-if="winner !== null" class="couple-mask">
      <article class="couple-sheet couple-modal"><h2>{{ state.players[winner].name }}先到终点</h2><p>这一局的棋盘会重新洗牌。</p><button type="button" @click="winner = null; goHome()">再来一局</button></article>
    </div>
    <div v-if="creating" class="couple-mask">
      <form class="couple-sheet couple-modal" @submit.prevent="saveCreate">
        <h2>新建主题</h2>
        <label>名称<input v-model="draftName" required></label>
        <label>说明<input v-model="draftDesc"></label>
        <label>适用<select v-model="draftAudience"><option value="common">双方</option><option value="male">男方</option><option value="female">女方</option></select></label>
        <div class="couple-theme-row"><button v-for="mode in allModes" :key="mode.id" type="button" :class="{ on: draftModes.includes(mode.id) }" @click="toggleDraftMode(mode.id)">{{ mode.label }}</button></div>
        <div class="couple-actions"><button type="submit">创建</button><button type="button" @click="creating = false">取消</button></div>
      </form>
    </div>
    <div v-if="importing" class="couple-mask">
      <form class="couple-sheet couple-modal" @submit.prevent="runImport">
        <h2>一行一条导入</h2>
        <textarea v-model="importText" rows="8" placeholder="对视十秒&#10;拥抱一下"></textarea>
        <label><input v-model="importMode" type="radio" value="append">追加</label>
        <label><input v-model="importMode" type="radio" value="replace">替换</label>
        <div class="couple-actions"><button type="submit">导入</button><button type="button" @click="importing = false">取消</button></div>
      </form>
    </div>
    <button v-if="state.view === 'themes'" class="couple-reset" type="button" @click="resetAll">清空本机进度</button>
  </div>
</template>
