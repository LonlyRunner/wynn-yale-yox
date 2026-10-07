# Wynn Yale Yox

个人技术、影像、AI 创作与小游戏网站。视觉来自雾中山野视频、个人签名和可拖拽的动态毛茸茸小猫。

## 技术栈

- Vue 3 + TypeScript + Vite + Vue Router
- Spring Boot 4.1.1 + Spring AI 2.0.1 + Spring Security + JPA
- MySQL 8.4；本地默认使用 H2
- 阿里云 OSS 私有 Bucket 签名上传与短期访问链接
- Docker Compose + Nginx，按 2 核 2G 服务器限制配置

## 已实现

- 中英双语、首页静音循环视频、音乐歌单、图片展示、个人资料和小游戏
- 游戏页保留鹈鹕骑行、代码记忆和技术栈 2048；3D 全息卡可在 No.001、粉金护目镜 No.002 和液态玻璃猫 No.003 之间切换，支持旋转、翻面、调节彩箔及下载画面，资源随前端部署
- Markdown 博客、搜索、分类标签、草稿发布、评论审核、RSS 和 Word/PDF/Markdown 文档解析
- 公开 AI 图片/视频创作；访客各试用一次，管理员不限次数
- 图片和视频可分别选择模型并记住选择；图片自动模式失败时回退千问，手动模式固定使用所选模型；视频支持比例、分辨率和时长
- AI 记录、生成结果和相册均保存到 OSS，生成图片与视频可直接下载
- 影像卡片支持翻转、查看和复制提示词；上传图片后由视觉模型自动提取特征并生成双语提示词
- 静态图片使用 WebP；OSS 原图由后端按 480/960/1200 像素生成并缓存预览图，浏览器缓存七天
- 点击小猫打开“团子”聊天助手，默认使用 DeepSeek，并可切换通义千问
- 聊天助手会检索知识库与已发布博客，将相关内容和人格设定注入模型上下文
- 管理后台可维护文章、媒体、评论、AI 任务和 RAG 知识条目，知识库支持文档导入
- 游客留言板 `/guestbook` 支持文字、图片预览与文件下载；可选联系方式仅站主可见，后台可删除留言及 OSS 附件
- 随心记支持最多 8 张图片，缩略图展示、点击放大和删除；草稿图片不在公开接口中展示
- 后台总览与“服务与游客访问”可独立开关 AI 图片/视频创作和团子聊天；设置保存到数据库，关闭后仍可读取历史结果
- 游客访问数据包含今日/累计 PV、UV、近 7/30/90 天 ECharts 趋势、热门页面、设备和来源域名；匿名 Cookie 去重，不记录 IP 或来源网址参数，管理员访问不计入

## 本地运行

前端：

```bash
cd frontend
npm install
npm run dev
```

后端：

```bash
cd backend
mvn spring-boot:run
```

复制 `.env.example` 为 `.env` 并填写配置。管理员账号由 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD` 决定，密钥只保存在 `.env`，不要提交到 Git。

## 阿里云部署

```bash
cp .env.example .env
# 填写强密码、OSS 与 AI 服务配置
cd backend && mvn -DskipTests package && cd ..
docker compose up -d --build
```

媒体文件不写入 MySQL。管理后台向后端申请短期 OSS PUT 签名，浏览器再直传私有 Bucket。数据库只保存文章、评论、任务记录和 OSS Object Key。

留言板无需登录，文字和附件会直接公开。每条最多 3000 字、4 个附件，单个附件不超过 5 MB、合计不超过 16 MB；同一会话两次发布至少间隔 15 秒。附件通过后端校验后保存在 OSS 的 `guestbook/` 前缀下，仅 JPEG/PNG/GIF/WebP 可内嵌预览，其他文件以附件下载。联系方式仅由 `/api/admin/guestbook` 返回；公开接口不会返回该字段。后台“游客留言”支持分页查看和删除，删除前会提示确认，并清理该留言对应的 OSS 文件。

在后台“随心记”选择图片后点击保存：先保存文字，再上传图片到 OSS 的 `journals/` 前缀。支持 JPG/PNG/GIF/WebP，每张不超过 5 MB、每批合计不超过 16 MB。图片失败时保留已保存文字和待上传文件，重试不会重复创建文章。删除图片或整篇随心记会清理对应 OSS 原图。访问统计从此功能上线后开始累计，UV 是浏览器标识数，同一人在不同设备访问会计为不同访客。

## AI 服务

AI 工作室公开可见。匿名浏览器可生成一张图片和一段视频；站点所有者登录后不限制次数。中转服务使用 OpenAI 兼容的图片与视频接口，千问作为图片回退服务。所有 API Key 均由后端环境变量读取，不进入浏览器代码或 Git。

创作模型菜单从 `/api/ai/models` 读取已配置线路的模型。`RELAY_IMAGE_MODEL`、`RELAY_VIDEO_MODEL` 指定默认模型；`RELAY_IMAGE_MODELS`、`RELAY_VIDEO_MODELS` 使用逗号分隔其他可选模型，需确认支持该中转的 `/images/generations`、`/videos/generations` 与视频查询接口。配置千问后，还可直接选择 `QWEN_IMAGE_MODEL`。只有图片的“自动选择”会回退；手动选择失败后不会换模型。后端在占用访客额度前校验模型及图片/视频类型，生成记录保存实际使用的模型；旧记录没有模型字段时继续展示原来的服务线路。

默认中转创作模型仅包含已确认支持的 `grok-imagine-image-2.0` 和 `grok-imagine-video-1.5`。配置 OSS 后，Grok 生图使用 `b64_json` 返回图片数据，校验格式后直接保存到 OSS，避免服务器无法连接临时图片域名而丢失结果。图片自动模式也会在结果下载或保存失败时尝试千问；手动模式仍保留原模型并报告失败。

团子聊天默认使用 `DEEPSEEK_CHAT_MODEL=deepseek-v3.2`。未单独填写 `DEEPSEEK_BASE_URL` 和 `DEEPSEEK_API_KEY` 时，会复用百炼兼容接口与 `QWEN_API_KEY`；模型下拉菜单还提供 `QWEN_CHAT_MODEL=qwen-plus`。如果改用 DeepSeek 官方或其他兼容服务，只需在 `.env` 中填写对应的 DeepSeek 地址、密钥和模型名。

可通过 `CHAT_RELAY_BASE_URL`、`CHAT_RELAY_API_KEY` 和 `CHAT_RELAY_MODELS` 为团子增加独立的 OpenAI 兼容中转服务。模型列表使用逗号分隔；前端会把这些模型加入同一个切换菜单，后端会校验模型必须在配置列表中。

如需接入第二枚中转密钥，可配置 `CHAT_SECONDARY_RELAY_BASE_URL`、`CHAT_SECONDARY_RELAY_API_KEY` 和 `CHAT_SECONDARY_RELAY_MODELS`。两条线路的模型会合并进团子与鹈鹕小游戏的同一份菜单。

鹈鹕小游戏复用团子的同一份模型列表。选择模型后，后端请求该模型生成 SVG，校验 XML、危险元素和绘图元素数量后再返回；成功结果会按模型缓存到本次服务进程中。
