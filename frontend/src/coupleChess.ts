export type TileType = 'blank' | 'lucky' | 'trap'
export type PlayerRole = 'male' | 'female'
export type GameMode = 'board' | 'card' | 'mine' | 'dice'
export type ThemeMode = 'board' | 'card' | 'mineTruth' | 'mineDare' | 'mineTheme' | 'diceAction' | 'diceBody'
export type MineTileType = 'bomb' | 'truth' | 'dare' | 'blank' | 'theme'
export type MineTaskChoice = 'truth' | 'dare' | 'theme'
export type ThemeAudience = 'common' | 'male' | 'female'
export type TaskExecutor = 'female' | 'male' | 'both'
export type ChessView = 'home' | 'game' | 'card' | 'mine' | 'dice' | 'themes'

export interface MineTile { type: MineTileType; revealed: boolean }
export interface Player { id: number; name: string; color: string; role: PlayerRole; step: number; themeId: string | null }
export interface TaskCard { text: string; executor: TaskExecutor; moveDelta: number }
export interface Theme { id: string; name: string; desc: string; audience: ThemeAudience; modes: ThemeMode[]; tasks: TaskCard[] }
export interface PathCoord { r: number; c: number }
export interface TaskEventData {
  type: 'collision' | 'lucky' | 'trap' | 'card' | 'mineTruth' | 'mineDare' | 'mineTheme'
  initiatorPlayerId: number
  executorPlayerId: number
  title: string
  subtitle: string
  task: string
  taskSourceId: string
  moveDelta: number
}
export type MineRevealResult = { type: 'blank' } | { type: 'bomb' } | { type: 'task'; task: TaskEventData }

const GRID = 7
const TILES = 49
const MINE_SIZE = 36
const MODES = new Set<ThemeMode>(['board', 'card', 'mineTruth', 'mineDare', 'mineTheme', 'diceAction', 'diceBody'])
const MINE_TYPES = new Set<MineTileType>(['bomb', 'truth', 'dare', 'blank', 'theme'])
const ALL_MODES: ThemeMode[] = ['board', 'card', 'mineTruth', 'mineDare', 'mineTheme', 'diceAction', 'diceBody']
export const STORAGE_KEY = 'wynn-couple-chess-v1'

function task(text: string, executor: TaskExecutor = 'both', moveDelta = 0): TaskCard {
  return { text, executor, moveDelta }
}

const romance: TaskCard[] = [
  task('对视十秒，说出对方今天最好看的一个细节', 'both', 1),
  task('额头相抵，一起慢慢数到二十', 'both', 1),
  task('把对方的手握在掌心，轻轻摩挲三十秒', 'both', 1),
  task('给对方一个额头吻，再拥抱十秒', 'both', 2),
  task('用对方喜欢的称呼喊一次名字', 'both', 1),
  task('靠在对方肩上，安静待二十秒', 'both'),
  task('面对面说一句今天想对对方说、还没说出口的话', 'both', 1),
  task('轻轻碰一下鼻尖，然后一起笑出来', 'both'),
  task('牵手走到窗边，一起看十秒外面', 'both', 1),
  task('给对方一个侧脸吻', 'both', 1),
  task('把对方拉进怀里，轻声说“我在”', 'both', 2),
  task('用指尖画对方的眉毛，再夸一句', 'both'),
]

const questions: TaskCard[] = [
  task('说出第一次觉得对方很可爱的瞬间'),
  task('分享一件只想告诉对方的小事'),
  task('说出对方做过的、让你最安心的一件事'),
  task('如果今晚只能做一件事，你希望两人一起做什么'),
  task('说出一个对方可能不知道的小习惯'),
  task('回忆一次一起笑得停不下来的时候'),
  task('说出你最喜欢对方声音的哪一种'),
  task('如果给今天的相处打分，你会打几分，为什么'),
  task('说出一个想和对方一起完成的小约定'),
  task('描述对方生气和开心时，你分别最先注意到什么'),
]

const dares: TaskCard[] = [
  task('给对方捶肩或按摩手掌三十秒', 'both', 1),
  task('用夸张的语气夸对方三句，不许重复'),
  task('学对方一个招牌动作，让对方来打分', 'both'),
  task('闭眼，让对方把你领着走五步', 'both', 1),
  task('一起拍一张只露半张脸的合影，存在自己手机里'),
  task('给对方起一个今晚专用的昵称，并用它说完下一句话'),
  task('十秒内说出对方的三个优点', 'both', 1),
  task('跟着对方做一个拉伸动作，坚持十五秒', 'both'),
  task('用手机备忘录写一句情话，递给对方看', 'both', 1),
  task('选择一首歌，只哼副歌给对方听'),
]

const actions: TaskCard[] = [
  task('轻抚'), task('握手'), task('拥抱'), task('对视'), task('依偎'), task('轻吻额头'), task('碰鼻尖'), task('十指相扣'),
]
const places: TaskCard[] = [
  task('手背'), task('发梢'), task('肩膀'), task('手心'), task('脸颊'), task('额头'), task('手腕'), task('锁骨外侧衣领'),
]

function theme(id: string, name: string, desc: string, audience: ThemeAudience, modes: ThemeMode[], tasks: TaskCard[]): Theme {
  return { id, name, desc, audience, modes, tasks }
}

export const DEFAULT_THEMES: Theme[] = [
  theme('soft-date', '散步与对视', '适合飞行棋的轻柔相处', 'common', ['board', 'card', 'mineTheme'], romance),
  theme('little-questions', '小事真心话', '只聊日常与感受', 'common', ['card', 'mineTruth', 'mineTheme'], questions),
  theme('cozy-dares', '待在一起的小冒险', '拥抱、夸奖和合影', 'common', ['board', 'card', 'mineDare', 'mineTheme'], dares),
  theme('gentle-actions', '轻柔动作', '给双骰子的上骰', 'common', ['diceAction'], actions),
  theme('gentle-places', '可以停留的位置', '给双骰子的下骰', 'common', ['diceBody'], places),
]

const DEFAULT_IDS = new Set(DEFAULT_THEMES.map(item => item.id))

export function generateSpiralPath(): PathCoord[] {
  const path: PathCoord[] = []
  let r = 0, c = 0, dr = 0, dc = 1
  const visited = Array.from({ length: GRID }, () => Array(GRID).fill(false))
  for (let i = 0; i < TILES; i++) {
    path[i] = { r, c }
    visited[r][c] = true
    const nr = r + dr, nc = c + dc
    if (nr >= 0 && nr < GRID && nc >= 0 && nc < GRID && !visited[nr][nc]) { r = nr; c = nc }
    else {
      if (dr === 0 && dc === 1) { dr = 1; dc = 0 }
      else if (dr === 1 && dc === 0) { dr = 0; dc = -1 }
      else if (dr === 0 && dc === -1) { dr = -1; dc = 0 }
      else { dr = 0; dc = 1 }
      r += dr; c += dc
    }
  }
  return path
}

function shuffle<T>(items: T[]) {
  const copy = [...items]
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]]
  }
  return copy
}

export function generateBoardMap(): TileType[] {
  const board = Array<TileType>(TILES).fill('blank')
  const indexes = shuffle(Array.from({ length: TILES - 2 }, (_, i) => i + 1))
  indexes.slice(0, 16).forEach(index => { board[index] = 'lucky' })
  indexes.slice(16, 32).forEach(index => { board[index] = 'trap' })
  return board
}

export function generateMineBoard(): MineTile[] {
  const types: MineTileType[] = [
    ...Array<MineTileType>(7).fill('bomb'),
    ...Array<MineTileType>(8).fill('truth'),
    ...Array<MineTileType>(8).fill('dare'),
    ...Array<MineTileType>(5).fill('blank'),
    ...Array<MineTileType>(8).fill('theme'),
  ]
  return shuffle(types).slice(0, MINE_SIZE).map(type => ({ type, revealed: false }))
}

export function calculateNewPosition(current: number, steps: number) {
  const target = current + steps
  return target >= 48 ? 48 - (target - 48) : target
}

export function rollDice() { return Math.floor(Math.random() * 6) + 1 }

export const initialPlayers = (): Player[] => [
  { id: 0, name: '男方', color: '#f3b4c4', role: 'male', step: 0, themeId: null },
  { id: 1, name: '女方', color: '#e7a3b5', role: 'female', step: 0, themeId: null },
]

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object'
}

function clampMove(value: unknown) {
  const number = typeof value === 'number' ? value : 0
  return Math.max(-6, Math.min(6, Math.trunc(number)))
}

function normalizeTask(input: unknown): TaskCard | null {
  if (!isRecord(input) || typeof input.text !== 'string' || !input.text.trim()) return null
  const executor = input.executor === 'male' || input.executor === 'female' || input.executor === 'both' ? input.executor : 'both'
  return { text: input.text.trim(), executor, moveDelta: clampMove(input.moveDelta) }
}

function allowed(themeItem: Theme, role: PlayerRole, mode: ThemeMode) {
  return (themeItem.audience === 'common' || themeItem.audience === role) && themeItem.modes.includes(mode) && themeItem.tasks.length > 0
}

function normalizeThemes(input: unknown): Theme[] {
  const incoming = Array.isArray(input) ? input : []
  const parsed = [...incoming, ...DEFAULT_THEMES].map(item => {
    const record = isRecord(item) ? item : {}
    const id = typeof record.id === 'string' ? record.id : `theme_${Date.now()}`
    const preset = DEFAULT_THEMES.find(themeItem => themeItem.id === id)
    if (preset) return preset
    const modes = Array.isArray(record.modes) ? record.modes.filter((mode): mode is ThemeMode => MODES.has(mode as ThemeMode)) : []
    const audience = record.audience === 'male' || record.audience === 'female' || record.audience === 'common' ? record.audience : 'common'
    return {
      id,
      name: typeof record.name === 'string' && record.name.trim() ? record.name : '未命名主题',
      desc: typeof record.desc === 'string' ? record.desc : '',
      audience,
      modes: modes.length ? modes : ALL_MODES,
      tasks: Array.isArray(record.tasks) ? record.tasks.map(normalizeTask).filter((card): card is TaskCard => !!card) : [],
    } satisfies Theme
  })
  return parsed.reduce<Theme[]>((list, themeItem) => {
    const index = list.findIndex(item => item.id === themeItem.id)
    if (index >= 0) {
      if (DEFAULT_IDS.has(themeItem.id)) list[index] = themeItem
      return list
    }
    list.push(themeItem)
    return list
  }, [])
}

export interface ChessState {
  view: ChessView
  gameMode: GameMode
  turn: number
  players: Player[]
  themes: Theme[]
  boardMap: TileType[]
  pathCoords: PathCoord[]
  mineBoard: MineTile[]
}

export function freshState(): ChessState {
  return {
    view: 'home', gameMode: 'board', turn: 0, players: initialPlayers(), themes: DEFAULT_THEMES.map(item => ({ ...item, tasks: [...item.tasks] })),
    boardMap: generateBoardMap(), pathCoords: generateSpiralPath(), mineBoard: generateMineBoard(),
  }
}

export function loadState(): ChessState {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null') as unknown
    if (!isRecord(saved)) return freshState()
    const themes = normalizeThemes(saved.themes)
    const players = initialPlayers().map(base => {
      const found = Array.isArray(saved.players) ? saved.players.find(item => isRecord(item) && item.id === base.id) : undefined
      const record = isRecord(found) ? found : {}
      const themeId = typeof record.themeId === 'string' ? record.themeId : null
      const themeItem = themes.find(item => item.id === themeId)
      return {
        ...base,
        name: typeof record.name === 'string' && record.name.trim() ? record.name : base.name,
        step: typeof record.step === 'number' ? record.step : 0,
        themeId: themeItem && (themeItem.audience === 'common' || themeItem.audience === base.role) ? themeId : null,
      }
    })
    const view = saved.view === 'game' || saved.view === 'card' || saved.view === 'mine' || saved.view === 'dice' || saved.view === 'themes' ? saved.view : 'home'
    const gameMode = saved.gameMode === 'card' || saved.gameMode === 'mine' || saved.gameMode === 'dice' ? saved.gameMode : 'board'
    const mine = Array.isArray(saved.mineBoard) ? saved.mineBoard.flatMap(tile => {
      if (!isRecord(tile) || !MINE_TYPES.has(tile.type as MineTileType)) return []
      return [{ type: tile.type as MineTileType, revealed: !!tile.revealed }]
    }) : []
    return {
      view, gameMode, turn: saved.turn === 1 ? 1 : 0, players, themes,
      boardMap: Array.isArray(saved.boardMap) && saved.boardMap.length === TILES ? saved.boardMap as TileType[] : generateBoardMap(),
      pathCoords: generateSpiralPath(),
      mineBoard: mine.length === MINE_SIZE ? mine : generateMineBoard(),
    }
  } catch {
    return freshState()
  }
}

export function saveState(state: ChessState) {
  const { view, gameMode, turn, players, themes, boardMap, mineBoard } = state
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ view, gameMode, turn, players, themes, boardMap, mineBoard }))
}

function pickTask(themeItem?: Theme) {
  if (!themeItem?.tasks.length) return null
  return themeItem.tasks[Math.floor(Math.random() * themeItem.tasks.length)]
}

function executorId(card: TaskCard, fallback: number) {
  if (card.executor === 'female') return 1
  if (card.executor === 'male') return 0
  return fallback
}

function eventFrom(type: TaskEventData['type'], initiator: number, fallback: number, title: string, subtitle: string, card: TaskCard, source: string): TaskEventData {
  const move = type.startsWith('mine') ? 0 : card.moveDelta
  const extra = move === 0 ? '' : move > 0 ? `，完成后前进${move}格` : `，完成后后退${Math.abs(move)}格`
  return { type, initiatorPlayerId: initiator, executorPlayerId: executorId(card, fallback), title, subtitle: `${subtitle}${extra}`, task: card.text, taskSourceId: source, moveDelta: move }
}

export function canStart(state: ChessState) {
  const { players, themes, gameMode } = state
  const has = (player: Player, mode: ThemeMode) => {
    const themeItem = themes.find(item => item.id === player.themeId)
    return !!themeItem && allowed(themeItem, player.role, mode)
  }
  if (gameMode === 'card') return players.every(player => has(player, 'card'))
  if (gameMode === 'mine') return players.every(player => has(player, 'mineTheme')) && players.every(player => themes.some(item => allowed(item, player.role, 'mineTruth'))) && players.every(player => themes.some(item => allowed(item, player.role, 'mineDare')))
  if (gameMode === 'dice') {
    const action = themes.find(item => item.id === players[0].themeId)
    const body = themes.find(item => item.id === players[1].themeId)
    return !!action && !!body && allowed(action, players[0].role, 'diceAction') && allowed(body, players[1].role, 'diceBody')
  }
  return players.every(player => has(player, 'board'))
}

export function startMatch(state: ChessState): ChessState {
  const view = state.gameMode === 'dice' ? 'dice' : state.gameMode === 'card' ? 'card' : state.gameMode === 'mine' ? 'mine' : 'game'
  return { ...state, view, turn: Math.random() < 0.5 ? 0 : 1, players: state.players.map(player => ({ ...player, step: 0 })), boardMap: generateBoardMap(), pathCoords: generateSpiralPath(), mineBoard: generateMineBoard() }
}

export function checkTile(state: ChessState, landing: number): TaskEventData | 'win' | null {
  const active = state.players[state.turn]
  const other = state.players[state.turn === 0 ? 1 : 0]
  if (landing === 48) return 'win'
  if (landing !== 0 && landing === other.step) {
    const themeItem = state.themes.find(item => item.id === active.themeId)
    const card = pickTask(themeItem)
    return card ? eventFrom('collision', active.id, other.id, '刚好相遇', `任务来自「${themeItem?.name || ''}」`, card, active.themeId || '') : null
  }
  const tile = state.boardMap[landing]
  if (tile === 'lucky') {
    const themeItem = state.themes.find(item => item.id === active.themeId)
    const card = pickTask(themeItem)
    return card ? eventFrom('lucky', active.id, other.id, '幸运格', `任务来自「${themeItem?.name || ''}」`, card, active.themeId || '') : null
  }
  if (tile === 'trap') {
    const themeItem = state.themes.find(item => item.id === other.themeId)
    const card = pickTask(themeItem)
    return card ? eventFrom('trap', active.id, active.id, '小陷阱', `任务来自「${themeItem?.name || ''}」`, card, other.themeId || '') : null
  }
  return null
}

export function revealMine(state: ChessState, index: number): { state: ChessState; result: MineRevealResult | null } {
  const tile = state.mineBoard[index]
  const active = state.players[state.turn]
  if (!tile || tile.revealed) return { state, result: null }
  const next = { ...state, mineBoard: state.mineBoard.map((item, itemIndex) => itemIndex === index ? { ...item, revealed: true } : item) }
  if (tile.type === 'bomb' || tile.type === 'blank') return { state: next, result: { type: tile.type } }
  const choice: MineTaskChoice = tile.type === 'truth' ? 'truth' : tile.type === 'dare' ? 'dare' : 'theme'
  const task = mineTask(next, active.id, choice)
  return { state: next, result: task ? { type: 'task', task } : { type: 'blank' } }
}

export function mineTask(state: ChessState, playerId: number, choice: MineTaskChoice, selectorId?: number): TaskEventData | null {
  const player = state.players.find(item => item.id === playerId)
  const selector = state.players.find(item => item.id === selectorId)
  if (!player) return null
  const mode: ThemeMode = choice === 'truth' ? 'mineTruth' : choice === 'dare' ? 'mineDare' : 'mineTheme'
  const themeItem = choice === 'theme'
    ? state.themes.find(item => item.id === player.themeId && allowed(item, player.role, 'mineTheme'))
    : shuffle(state.themes.filter(item => allowed(item, player.role, mode)))[0]
  const card = pickTask(themeItem)
  if (!themeItem || !card) return null
  const prefix = selector ? `${selector.name}指定` : '翻开的格子'
  if (choice === 'truth') return eventFrom('mineTruth', player.id, player.id, '真心话', prefix, card, themeItem.id)
  if (choice === 'dare') return eventFrom('mineDare', player.id, player.id, '小冒险', prefix, card, themeItem.id)
  return eventFrom('mineTheme', player.id, player.id, '主题任务', `${prefix}，来自「${themeItem.name}」`, card, themeItem.id)
}

export function drawCard(state: ChessState): TaskEventData | null {
  const player = state.players[state.turn]
  const themeItem = state.themes.find(item => item.id === player.themeId)
  if (!themeItem || !allowed(themeItem, player.role, 'card')) return null
  const card = pickTask(themeItem)
  return card ? eventFrom('card', player.id, player.id, '任务卡', `任务来自「${themeItem.name}」`, card, themeItem.id) : null
}

export function acceptTask(state: ChessState, task: TaskEventData): ChessState {
  const players = task.moveDelta === 0 ? state.players : state.players.map(player => player.id === task.executorPlayerId ? { ...player, step: task.moveDelta > 0 ? calculateNewPosition(player.step, task.moveDelta) : Math.max(0, player.step + task.moveDelta) } : player)
  return { ...state, players, turn: state.turn === 0 ? 1 : 0 }
}

export function rejectTask(state: ChessState, task: TaskEventData): ChessState {
  const boardTask = task.type === 'collision' || task.type === 'lucky' || task.type === 'trap'
  const players = boardTask ? state.players.map(player => {
    if (player.id !== task.executorPlayerId) return player
    if (task.type === 'collision') return { ...player, step: 0 }
    return { ...player, step: Math.max(0, player.step - (Math.floor(Math.random() * 3) + 1)) }
  }) : state.players
  return { ...state, players, turn: state.turn === 0 ? 1 : 0 }
}

export function themesFor(state: ChessState, role: PlayerRole, mode: ThemeMode) {
  return state.themes.filter(item => (item.audience === 'common' || item.audience === role) && item.modes.includes(mode))
}

export function createThemeId(themes: Theme[]) {
  const base = crypto.randomUUID?.() || `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
  let id = `user_${base}`
  while (themes.some(item => item.id === id)) id = `user_${base.slice(0, 8)}${Math.random().toString(36).slice(2, 5)}`
  return id
}
