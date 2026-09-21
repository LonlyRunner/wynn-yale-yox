你在 Vue 搜索框里快速输入“spring”，页面先显示了 `spring` 的结果，随后却被较早返回的 `spr` 请求覆盖；聊天流式输出偶尔出现半个汉字，登录过期后有的接口跳转登录页，有的只显示“网络错误”。这些问题看似来自不同组件，根源却都是前后端边界没有统一：请求类型、取消规则、流式协议和错误格式各写一套。本文从一次旧响应覆盖新响应开始，建立 Vue 3 到后端的执行链路，实现带泛型、取消和 SSE 解码的最小请求模块，并给出验证竞态、缓存和鉴权的方法。

## 1. 从一个实际问题开始

用户连续输入三个关键词，浏览器发出三个请求。网络返回顺序无法保证，如果每个响应都直接写入同一个列表，最早的请求可能最后返回，页面就会显示过期数据。

同样地，SSE 的一个中文字符可能被拆到两个网络数据块中。如果每次 `read()` 后直接转字符串，边界处就可能出现乱码。代码没有语法错误，但忽略了网络是分片、并发且会失败的。

## 2. 先建立整体认识

```text
组件状态
→ TypeScript 请求函数
→ fetch 与 HTTP
→ 后端鉴权/业务处理
→ 状态码和响应体
→ 解码、校验、更新响应式状态
→ Vue 重新渲染界面
```

TypeScript 负责开发阶段的类型提示，HTTP 状态码表达协议结果，业务错误码表达稳定业务语义，Vue 只在确认响应仍有效时更新页面。类型不能验证网络返回的真实内容，信任边界仍需运行时校验。

## 3. 必须掌握的核心概念

### 3.1 泛型解决“调用方知道返回什么”

`request<T>` 让每个调用位置声明期望类型，减少手写断言。但 `as T` 不会在运行时检查字段，所以重要接口可使用 Schema 库或手写守卫验证。

### 3.2 401 与业务失败不是一回事

401 表示未认证，403 表示已经识别身份但无权限，400 通常表示请求不合法，5xx 表示服务端或上游失败。统一请求层应解析错误并携带 Trace ID，组件不应各自猜测“网络失败”。

### 3.3 取消旧请求可以消除搜索竞态

`AbortController` 能中止不再需要的 fetch。即使服务端已经处理请求，前端也不会让旧响应覆盖新状态。提交类请求还需要后端幂等，取消不能撤销已经发生的业务操作。

### 3.4 SSE 是服务端到浏览器的文本事件流

SSE 适合模型 token 等单向推送。`TextDecoder` 必须使用 `{ stream: true }` 保存未完成的多字节字符；解析器还要保留没有结束的事件片段。

## 4. 一个最小可运行示例

下面的 TypeScript 模块包含统一 JSON 请求、可取消搜索和基于 fetch 的 SSE 读取，可直接放入 Vue 3 项目的 `src/api.ts`。

```ts
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly traceId?: string,
  ) { super(message) }
}

export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...init.headers },
    ...init,
  })
  const body = await response.json().catch(() => ({}))
  if (response.status === 401) {
    location.assign(`/login?redirect=${encodeURIComponent(location.pathname)}`)
    throw new ApiError(401, 'UNAUTHENTICATED', '登录已过期')
  }
  if (!response.ok) {
    throw new ApiError(response.status, body.code ?? 'REQUEST_FAILED', body.message ?? '请求失败', body.traceId)
  }
  return body as T
}

let searchController: AbortController | undefined
export async function searchPosts(keyword: string) {
  searchController?.abort()
  searchController = new AbortController()
  return request<Array<{ id: number; title: string }>>(
    `/api/posts?q=${encodeURIComponent(keyword)}`,
    { signal: searchController.signal },
  )
}

export async function readSse(
  url: string,
  onData: (data: string) => void,
  signal?: AbortSignal,
) {
  const response = await fetch(url, { credentials: 'include', signal })
  if (!response.ok || !response.body) throw new Error(`SSE failed: ${response.status}`)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() ?? ''
    for (const event of events) {
      const line = event.split('\n').find(item => item.startsWith('data:'))
      if (line) onData(line.slice(5).trimStart())
    }
  }
  buffer += decoder.decode()
}
```

搜索关键词从组件进入 `searchPosts`，旧 Controller 先被取消，再创建新请求。真正改变页面状态的应是组件 `await` 成功后的赋值。SSE 数据从 `ReadableStream` 进入 decoder，完整事件以两个换行分隔；未完成部分保存在 `buffer` 等待下一块。

## 5. 逐步完成实际操作

1. 把请求函数放入独立模块，所有组件复用同一错误处理和凭证策略。
2. 搜索框加入 200～300ms 防抖，并在新搜索时取消旧请求。
3. 组件卸载时调用 `AbortController.abort()`，避免继续更新已销毁页面。
4. 后端 SSE 设置正确的 `text/event-stream`，定期发送心跳并检测客户端断开。
5. 给静态哈希资源设置长期缓存，HTML 使用短缓存；图片使用现代格式和懒加载。

## 6. 如何验证

在浏览器开发者工具中开启网络限速，快速输入多个关键词，最终列表应始终对应最后一次输入，旧请求显示为 canceled。让服务端逐字发送中文，确认没有乱码。将会话 Cookie 删除后请求受保护接口，应统一跳转登录页，并在服务端日志中通过 Trace ID 找到同一次失败。

性能优化先记录 TTFB、FCP、LCP、CLS、INP、资源瀑布和主线程长任务。改变图片、缓存或代码分割后复测同一环境，才能确认优化有效。

## 7. 常见问题与排错

- **旧搜索结果覆盖新结果**：确认旧请求被取消，或使用递增序号只接收最后一次响应。
- **AbortError 被当成红色报错**：取消是预期控制流，应在组件中单独忽略。
- **SSE 中文乱码**：检查 `TextDecoder` 是否使用流模式，以及末尾是否调用一次无参数 `decode()`。
- **代理后 SSE 一次性返回**：关闭代理缓冲，确认响应头和心跳正常。
- **页面显示“网络失败”但后端有响应**：统一解析非 2xx 响应，显示业务 message 和 Trace ID。
- **首屏仍然慢**：先看资源瀑布；路由级代码分割、响应式图片、缓存和减少首屏 JS 通常优先级更高。

前后端契约不仅是字段列表，还包括状态码、取消、顺序、缓存和流式边界。把这些规则集中到请求层，组件才能专注于界面状态。
