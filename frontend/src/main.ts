import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import './style.css'
import App from './App.vue'
import PageView from './PageView.vue'
import { randomUUID } from './api'

const routes = [
  { path: '/', name: 'home', component: PageView },
  { path: '/blog', name: 'blog', component: PageView },
  { path: '/blog/:slug', name: 'article', component: PageView },
  { path: '/journal', name: 'journal', component: PageView },
  { path: '/gallery', name: 'gallery', component: PageView },
  { path: '/share', name: 'share', component: PageView },
  { path: '/guestbook', name: 'guestbook', component: PageView },
  { path: '/studio', name: 'studio', component: PageView },
  { path: '/about', name: 'about', component: PageView },
  { path: '/privacy', name: 'privacy', component: PageView },
  { path: '/terms', name: 'terms', component: PageView },
  { path: '/games', name: 'games', component: PageView },
  { path: '/games/:game', name: 'game', component: PageView },
  { path: '/login', name: 'login', component: PageView },
  { path: '/admin', name: 'admin', component: PageView, meta: { private: true } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
router.beforeEach(async to => {
  if (!to.meta.private) return true
  try {
    const response = await fetch('/api/auth/me', { credentials: 'include' })
    const state = response.ok ? await response.json() : { authenticated: false }
    if (state.authenticated) return true
  } catch { /* redirect below */ }
  sessionStorage.removeItem('wynn-auth')
  return { name: 'login', query: { redirect: to.fullPath } }
})
// Count public page navigation, excluding admin and login; never send query strings.
let visitQueue = Promise.resolve(), firstVisit = true
router.afterEach((to, _from, failure) => {
  if (failure || ['admin','login'].includes(String(to.name))) return
  const referrer = firstVisit ? document.referrer.slice(0,2000) : ''; firstVisit = false
  const body = JSON.stringify({eventId:randomUUID(),path:to.path,referrer})
  visitQueue = visitQueue.then(async () => { await fetch('/api/public/visits',{method:'POST',credentials:'include',headers:{'Content-Type':'application/json'},body}) }).catch(()=>{})
})
createApp(App).use(router).mount('#app')
