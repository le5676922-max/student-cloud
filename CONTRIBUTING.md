# 贡献指南

欢迎修复可复现问题、补充测试和改进文档。提交前请先阅读 [README](README.md)、[快速开始](docs/QUICKSTART.md) 和 [安全说明](SECURITY.md)。

## 提交问题

普通问题请说明操作步骤、预期结果、实际结果、版本 / 提交号、浏览器及部署方式，并附脱敏日志。不要上传真实数据库、用户文件、密钥或完整认证请求头。

安全漏洞请按 [SECURITY.md](SECURITY.md) 处理，不公开复现细节。

## 开发约定

- 保持前端 Vue 3、后端 Spring Boot 的现有模块边界；小范围问题优先小范围修复。
- 不修改已经在数据库执行过的 Flyway 迁移；结构升级新增迁移文件。
- 权限在服务端检查，尤其注意工作空间隔离、目录密码和文件操作上下文。
- 重要异步流程覆盖取消、重复提交、页面 / 账号切换和晚响应，不只验证成功路径。
- 外部模型、SMTP、存储和数据库集成测试必须显式启用，并使用隔离数据；普通测试不需要付费密钥。
- 保留上游 MIT 声明和各组件许可。引入资源前确认许可，不将“网上可以访问”视为再分发授权。

## 提交前检查

环境要求与安装命令以 [QUICKSTART.md](docs/QUICKSTART.md) 为准。

```bash
# 在仓库根目录执行后端验证。
mvn -f backend/pom.xml verify

# 在 frontend 目录执行前端验证。
cd frontend
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test:unit
pnpm lint
pnpm build
```

在 Pull Request 中注明修改目的、相关 Issue、实际执行的测试，以及未验证的环境。没有运行的真实服务测试要明确说明，不以模拟测试代替线上可用性证明。

运行数据、日志、审计临时文件、`node_modules`、`target`、`dist` 和真实秘密文件均不应进入提交。
