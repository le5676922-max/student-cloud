# 学生云盘

面向学生学习资料管理的前后端分离云盘项目，包含文件管理、多存储接入、分享、三角色权限控制和“小芽”AI Agent。

本仓库基于开源项目 ZFile 二次开发。原项目的版权和 MIT 许可声明保留在 [LICENSE](LICENSE)；论文或其他说明中应区分原有功能与个人完成的工作。后端、前端沿用原工程目录名以便追溯，应用代码的 Java 包名为 `im.tlgxy.cloud`。

## 目录

| 路径 | 用途 |
| --- | --- |
| `zfile-main/` | Spring Boot 3 / Java 21 后端 |
| `zfile-vue-main/` | Vue 3 / Vite 前端 |
| `database/` | 手工重建数据库脚本；不能用于正常升级 |
| `scripts/` | 本地验证和运行脚本 |
| `项目功能说明.md` | 功能与 Agent 介绍 |
| `项目测试报告.md` | 已验证内容与已知限制 |
| `Ubuntu服务器部署指南.md` | Ubuntu 上线步骤 |

## 本地构建

后端需要 JDK 21 和 Maven；前端需要 Node.js `>=20.19.0` 与 pnpm `11.19.0`。在 PowerShell 中运行 `.\scripts\verify.ps1 -InstallDependencies` 可以执行后端测试、打包、前端类型检查、单元测试和构建。详细操作见 [前后端启动说明](前后端启动说明.md)。

后端默认使用 MySQL `student_cloud`。启动前自行设置环境变量 `STUDENT_CLOUD_DB_PASSWORD`，必要时设置 `STUDENT_CLOUD_DB_USER`；不要把真实密码写入 `application.properties` 或提交仓库。也可使用项目的 `local` profile 在隔离目录运行 SQLite 演示。第三方网盘和 AI 模型密钥按需从环境变量配置；未配置的集成不可用，但不影响基础文件管理。

仓库不包含数据库、用户上传文件、构建产物、运行日志或本机凭据。发布到生产环境前，请查看 [部署指南](Ubuntu服务器部署指南.md) 并执行对应验收。
