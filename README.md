# Wynn Yale Yox

个人技术、影像、AI 创作与小游戏网站。视觉来自雾中山野视频、个人签名和动态毛茸茸小猫吉祥物。

## 技术栈

- Vue 3 + TypeScript + Vite + Vue Router
- Spring Boot 4.1.1 + Spring AI 2.0.1 + Spring Security + JPA
- MySQL 8.4；本地默认使用 H2
- 阿里云 OSS 私有 Bucket 签名上传
- Docker Compose + Nginx，按 2 核 2G 服务器限制配置

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

本地默认登录账号为 `wynn`，默认密码只用于开发。部署前必须通过环境变量设置 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD`。

## 阿里云部署

```bash
cp .env.example .env
# 填写强密码、OSS 与 AI 服务配置
cd backend && mvn -DskipTests package && cd ..
docker compose up -d --build
```

媒体文件不写入 MySQL。管理后台向后端申请短期 OSS PUT 签名，浏览器再直传私有 Bucket。

## AI 服务

AI 工作室只有站点所有者登录后可访问。当前任务接口、服务商选择和记录存储已完成；千问、DeepSeek 与中转站的真实请求需要在提供接口文档后补充各厂商适配参数。所有密钥均从环境变量读取，不进入前端或 Git。
