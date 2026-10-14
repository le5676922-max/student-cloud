# 小芽接入 DeepSeek Flash

小芽由后端调用 DeepSeek，浏览器不应持有 API Key。模型默认使用官方模型名 `deepseek-flash`，接口为 `https://api.deepseek.com/chat/completions`。模型名依据 [DeepSeek 官方文档](https://api-docs.deepseek.com/zh-cn/)，不再使用旧别名。

## Windows 本地配置

在仓库根目录的 PowerShell 运行：

```powershell
.\backend\scripts\ai\configure-deepseek.ps1
```

按提示输入密钥，输入过程不回显。脚本保存当前 Windows 用户级环境变量并更新当前 PowerShell 会话。不要把密钥写进项目源码、前端的 `VITE_*` 变量或 GitHub 仓库。

重新构建后端并重启后生效。`scripts/start-local.ps1` 启动前会读取此脚本保存的用户级密钥，避免桌面终端继承旧密钥；该启动脚本使用独立的 SQLite 本地演示配置，不是 MySQL 生产启动方式。通过 IDE 启动时需要重启 IDE，或在运行配置中引用正确的私密环境变量。

## 后端环境变量

| 变量 | 默认值 / 用途 |
| --- | --- |
| `DEEPSEEK_API_KEY` | 必填；优先于兼容变量 `TLGXY_CLOUD_AI_API_KEY` |
| `TLGXY_CLOUD_AI_ENABLED` | `true`，是否启用对话 |
| `TLGXY_CLOUD_AI_BASE_URL` | `https://api.deepseek.com` |
| `TLGXY_CLOUD_AI_MODEL` | `deepseek-flash` |
| `TLGXY_CLOUD_AI_THINKING_ENABLED` | `false`，DeepSeek 默认关闭深度思考，减少交互等待 |
| `TLGXY_CLOUD_AI_MAX_TOKENS` | `2048`，每次模型请求输出上限，允许 128–8192 |
| `TLGXY_CLOUD_AI_TIMEOUT_SECONDS` | `45`，单次模型请求超时秒数 |

`thinking` 扩展仅在模型名以 `deepseek-` 开头时发送，不影响其他普通 OpenAI 兼容模型。默认关闭深度思考不影响流式输出和工具调用；需要时可以显式启用。整轮生成还受后端对话总预算限制。

## Ubuntu / Docker 注意事项

服务器不会自动继承本机 Windows 环境变量，必须另行配置密钥。可在容器启动时通过仅服务器管理员可读的私密 `env_file` 注入；真实配置文件不要提交 Git。不要直接在含明文密钥的命令行上启动进程。

以下仅为环境变量模板，不是完整 Docker Compose 部署文件：

```dotenv
DEEPSEEK_API_KEY=替换为服务器专用密钥
TLGXY_CLOUD_AI_MODEL=deepseek-flash
TLGXY_CLOUD_AI_THINKING_ENABLED=false
TLGXY_CLOUD_AI_MAX_TOKENS=2048
```

反向代理需要关闭 SSE 缓冲并允许足够的读取时间。建议使用 HTTPS；当前前端请求 ID 使用 `crypto.randomUUID()`，普通公网 IP 的 HTTP 页面不能假定该 API 可用。前后端容器编排、数据库地址和数据持久化仍需要另行配置，本文件不表示 Docker 部署已验收。

## 验证方式与费用边界

常规 `mvn verify` 只运行本地模拟模型测试；真实模型测试默认跳过，CI 不需要付费密钥。

仅在明确希望调用真实付费 API 时，在 `backend` 目录执行：

```powershell
$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
$env:STUDENT_CLOUD_LIVE_AI_TEST = 'true'
try {
    mvn '-Dtest=AiDeepSeekLiveTest' test
} finally {
    Remove-Item Env:STUDENT_CLOUD_LIVE_AI_TEST -ErrorAction SilentlyContinue
}
```

该测试使用项目真实的 `AiAgentService`、HTTP 客户端与流式解析器，校验普通流式回答，以及模型调用只读工具后汇总结果。账号和知识数据都是测试样例，不连接业务数据库、不读取或上传用户文件；它不是浏览器端、真实账号权限或阿里云端到端验收。测试固定使用官方 Flash 模型，每次请求最多输出 256 token，工具测试最多两次模型请求，不会自动重试。

若密钥曾被公开或分享给其他人，应在服务商控制台撤销并更换；更换后重启后端。服务端日志与测试报告不得打印密钥。
