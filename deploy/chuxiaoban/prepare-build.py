from pathlib import Path
import sys, zipfile, json, shutil
root = Path(sys.argv[1]).resolve()
archive = Path(sys.argv[2]).resolve()
with zipfile.ZipFile(archive) as z:
    for item in z.infolist():
        target = (root / item.filename).resolve()
        if root not in target.parents: raise ValueError("Unsafe archive entry")
    z.extractall(root)
web = root / "chuxiaoban/apps/web-mini"
manifest = web / "src/manifest.json"
data = json.loads(manifest.read_text(encoding="utf-8"))
data["h5"]["router"]["base"] = "/chuxiaoban/"
manifest.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
vite = web / "vite.config.ts"
s = vite.read_text(encoding="utf-8").replace("plugins: [uni()]", "base: '/chuxiaoban/', plugins: [uni()]")
vite.write_text(s, encoding="utf-8")
# uni-app copies src/static; this project keeps its original assets at static/.
shutil.copytree(web / "static", web / "src/static", dirs_exist_ok=True)
# Dynamic CSS URLs are not rewritten by Vite's static import transform.
order_page = web / "src/pages/order/index.vue"
order_page.write_text(order_page.read_text(encoding="utf-8").replace("url(/static/", "url(/chuxiaoban/static/"), encoding="utf-8")

# Serialize guest initialization before protected page requests (including direct hash links).
http = web / "src/api/http.ts"
s = http.read_text(encoding="utf-8")
s = s.replace("export function request<T>(", "function sendRequest<T>(", 1)
s = s.replace("reject(new Error(body?.message || `请求失败 (${result.statusCode})`))", "reject(Object.assign(new Error(body?.message || `请求失败 (${result.statusCode})`), { status: result.statusCode }))", 1)
s += r'''

interface GuestSession { access_token: string; refresh_token: string; expires_in: number }
let sessionRequest: Promise<ApiResponse<GuestSession>> | undefined
function initializeSession(): Promise<ApiResponse<GuestSession>> {
  if (!sessionRequest) {
    const device = uni.getStorageSync('cxb_device_public_id') || randomId()
    uni.setStorageSync('cxb_device_public_id', device)
    sessionRequest = sendRequest<GuestSession>({ url: '/api/v1/sync/guest/init', method: 'POST', data: { device_public_id: device } })
      .then(response => {
        uni.setStorageSync('cxb_access_token', response.data.access_token)
        uni.setStorageSync('cxb_refresh_token', response.data.refresh_token)
        return response
      }).finally(() => { sessionRequest = undefined })
  }
  return sessionRequest
}
export async function request<T>(options: UniApp.RequestOptions): Promise<ApiResponse<T>> {
  const url = String(options.url)
  if (url === '/api/v1/sync/guest/init') return initializeSession() as unknown as Promise<ApiResponse<T>>
  const publicRequest = url === '/api/v1/sync/guest/refresh' || url === '/api/v1/auth/account/login'
  if (!publicRequest && !uni.getStorageSync('cxb_access_token')) await initializeSession()
  try { return await sendRequest<T>(options) }
  catch (error) {
    if (!publicRequest && (error as { status?: number }).status === 401) {
      await initializeSession()
      return sendRequest<T>(options)
    }
    throw error
  }
}
'''
http.write_text(s, encoding="utf-8")
print("Prepared deployment copy; original project files unchanged")
