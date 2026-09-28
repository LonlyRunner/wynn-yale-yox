import { reactive } from 'vue'
import { api } from './api'

export const siteServices = reactive({ aiCreationEnabled: true, catChatEnabled: true })
export async function refreshSiteServices() {
  try { Object.assign(siteServices, await api('/public/services')) } catch { /* Server still enforces the switches. */ }
}
