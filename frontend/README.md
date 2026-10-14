# 学生云盘

学生云盘是一个面向学生个人学习场景的文件管理项目，支持统一管理本地存储、对象存储、WebDAV、FTP、SFTP 等多种存储源。

## 主要功能

- 文件浏览、上传、下载、移动和在线预览
- 多存储源统一管理
- 用户登录、权限控制和分享链接
- 图片、音视频、文本、PDF 与 Office 文件预览
- 响应式页面，支持电脑和手机访问

## 本地开发

前端开发地址：`http://127.0.0.1:5173`

后端接口地址：`http://127.0.0.1:8080`

首次安装地址：`http://127.0.0.1:5173/install`

需要 Node.js `>=22.13.0`（推荐 24 LTS）和 pnpm `11.19.0`。先按 [快速开始](../docs/QUICKSTART.md) 配置 MySQL 并启动后端，再从项目根目录执行：

```bash
cd frontend
# 按锁文件安装依赖，不用 npm install 替代。
pnpm install --frozen-lockfile
# 开发时固定端口，只监听本机；保持终端运行。
pnpm dev --host 127.0.0.1 --port 5173 --strictPort
```

前端默认代理本机 `8080` 后端。后端使用其它地址时，在启动 Vite 前设置 `CLOUD_API_TARGET`；详细环境变量及首次初始化步骤见快速开始。未初始化时创建自己的全局管理员账号，没有默认密码。

构建与检查：

```bash
pnpm typecheck
pnpm test:unit
pnpm lint
pnpm build
```

构建结果在 `dist/`。正式部署使用 Nginx，不能把 Vite 开发服务器作为公网生产服务。

## 技术栈

Vue 3、Vite、Element Plus、Tailwind CSS、Spring Boot、MyBatis-Plus、MySQL。

## 开源说明

本项目基于原开源在线网盘项目进行学习和个性化改造，保留原项目许可证与相关版权声明。
