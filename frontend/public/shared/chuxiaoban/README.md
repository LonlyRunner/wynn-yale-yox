# 厨小伴

微信小程序与 H5 双端的 AI 厨房助手 MVP。

## 本地开发

双击项目根目录的 `启动项目.cmd`，即可自动启动 Docker 依赖、后端与 H5 前端，并打开浏览器。首次运行前请确认 Docker Desktop 已安装，且 `.env` 已填写 AI 服务配置。脚本会检测端口占用；若 8080/5173 被其他服务占用，会自动切换到 8081/5174，并在窗口中显示实际地址。

1. 复制 `.env.example` 并设置本地秘密信息。
2. `docker compose up -d` 启动 PostgreSQL、Redis 和 MinIO。
3. 后端：进入 `apps/server`，运行 `mvn spring-boot:run`。
4. 前端：进入 `apps/web-mini`，运行 `npm install`，再运行 `npm run dev:h5` 或 `npm run dev:mp-weixin`。

接口契约见 `api/openapi.yaml`，AI 输出约束见 `schemas/ai`，设计决策见 `docs`。
