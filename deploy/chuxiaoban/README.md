# 厨小伴生产部署

访问入口：主站 `/chuxiaoban/`，分享页链接直接打开 H5。

源码来自 `frontend/public/shared/chuxiaoban/chuxiaoban-project-2026-10-29.zip`。
`prepare-build.py` 只适配构建副本的 base/router/static 路径，原项目保持不变。

独立运行目录 `/opt/chuxiaoban`，Docker Compose 项目名 `chuxiaoban`。
PostgreSQL、Redis、MinIO、Java 21 后端在私有网络，只有 Web 映射到回环与 Docker 主网桥 `18081`。
主站 Nginx 转发 `/chuxiaoban/`，不占用主站 `/api/`，不复用主站数据库。
数据保存在 Compose 命名卷，部署不删除数据卷。

`.env` 仅保存服务器，600 权限。AI 与管理员配置沿用本地项目，数据库/存储/签名密钥独立随机生成。
真实短信/微信校验未实现，反代阻止开发版账号登录和绑定接口；游客模式可用。
MinIO 的预签名图片链接通过同域只读代理转发，不公开管理控制台和存储密钥。

因上游镜像撤回，首次部署从本机正在使用的 MinIO 镜像导入并固定本地 tag：
- `chuxiaoban-minio:cached-14cea493`（来源 minio/minio:latest，SHA256 14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e）
- `chuxiaoban-mc:cached-a7fe349e`（来源 minio/mc:latest，SHA256 a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727）
它们不依赖已失效的公网 latest 下载。备份时保留镜像归档及数据卷。

更新：手动运行 `deploy-chuxiaoban.yml`，或修改源码 ZIP / 本目录触发专用工作流。
服务器预置环境文件和这两个镜像后，工作流构建并上传 JAR/H5，执行 release.sh。
回退：保留上一份 web/JAR/部署配置，在停止本项目后恢复文件，再执行 release.sh；不要删除命名卷。
主站仍通过原 `deploy.yml` 发布。主站变更不重启厨小伴后端。
