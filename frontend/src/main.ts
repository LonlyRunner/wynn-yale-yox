import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import './style.css'
import App from './App.vue'
import PageView from './PageView.vue'

const routes = [
  { path: '/', name: 'home', component: PageView },
  { path: '/blog', name: 'blog', component: PageView },
  { path: '/blog/:slug', name: 'article', component: PageView },
  { path: '/gallery', name: 'gallery', component: PageView },
  { path: '/studio', name: 'studio', component: PageView, meta: { private: true } },
  { path: '/about', name: 'about', component: PageView },
  { path: '/games', name: 'games', component: PageView },
  { path: '/games/:game', name: 'game', component: PageView },
  { path: '/login', name: 'login', component: PageView },
  { path: '/admin', name: 'admin', component: PageView, meta: { private: true } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
router.beforeEach(to => to.meta.private && !sessionStorage.getItem('wynn-auth') ? { name: 'login', query: { redirect: to.fullPath } } : true)
createApp(App).use(router).mount('#app')
