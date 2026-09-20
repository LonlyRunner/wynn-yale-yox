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
- Markdown 博客、搜索、分类标签、草稿发布、评论审核、RSS 和 Word/PDF/Markdown 文档解析
- 公开 AI 图片/视频创作；访客各试用一次，管理员不限次数
- 图片优先调用中转服务，失败时回退千问；视频任务支持比例、分辨率和时长
- AI 记录、生成结果和相册均保存到 OSS；管理后台可维护文章、媒体、评论和 AI 任务

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

## AI 服务

AI 工作室公开可见。匿名浏览器可生成一张图片和一段视频；站点所有者登录后不限制次数。中转服务使用 OpenAI 兼容的图片与视频接口，千问作为图片回退服务。所有 API Key 均由后端环境变量读取，不进入浏览器代码或 Git。
