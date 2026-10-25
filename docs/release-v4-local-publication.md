# 本地容器镜像发布

`scripts/release-v4.ps1` 只执行 Windows 本地检查、构建、推送和发布清单生成。
服务器目录、数据库初始化、HTTPS 和网站切换需要单独执行；脚本不会 SSH、停止服务器服务、删除目录或数据库，也不会替代用户完成 `/install`。

保留 `release-v4.ps1` 文件名是为了兼容既有命令。脚本支持指定版本，默认发布标签为 **v5.0.0**，不会修改源码中的版本号。发布前请确认 `backend/pom.xml` 与 `frontend/package.json` 中的版本号。

## 运行

在项目根目录 PowerShell 中执行，并将以下示例值替换为自己的镜像仓库地址、命名空间和用户名：

```powershell
$publish = @{
    Registry = 'registry.example.com'
    Namespace = 'student_cloud'
    RegistryUser = 'your-registry-user'
    Version = 'v5.0.0'
}
.\scripts\release-v4.ps1 @publish
```

参数：

| 参数 | 默认值或要求 |
| --- | --- |
| `Version` | 默认 `v5.0.0`；发布新版本时使用新标签 |
| `Registry` | 必填；仓库域名，可包含端口，不包含 `https://` |
| `Namespace` | 必填；镜像仓库命名空间 |
| `RegistryUser` | 必填；镜像仓库登录用户名 |

要求 Docker Desktop 的 Linux Engine 和 buildx 可用，安装 Git、Node >=22.13.0，以及 `frontend/package.json` 指定版本的 `pnpm.cmd`。脚本打印 Git 状态，但不会重置、还原或清理未提交改动。生产前端配置的 `baseUrl` 必须严格为 `""`。

脚本通过现有 Docker credential store 访问镜像仓库，不读取 Docker 凭证文件。出现认证错误会立即停止，用户须在自己的终端执行：

```powershell
docker login --username=$publish.RegistryUser $publish.Registry
```

密码只在 Docker 的隐藏提示中手动输入，不传给脚本，不写入聊天、命令参数、代码或日志。看到 `Login Succeeded` 后再继续；已有 checkpoint 时使用 `-Resume`。

## 阶段与产物

1. 检查 Docker Client/Server、buildx、Git 状态、前端生产地址和工具版本。
2. 检查两个远端标签。已有但不属于本脚本已记录发布的标签会阻止后续执行。
3. 后端按当前 `backend/Dockerfile` 构建、检查平台、打标签、推送，并比较推送结果与远端实际 digest。
4. 前端运行 `pnpm.cmd install --frozen-lockfile`、`typecheck`、`build`，记录本地 `dist/index.html` SHA256。
5. 在忽略的 `.codex-tmp/releases/<版本>/frontend-context-<随机ID>` 创建最小构建上下文，只复制该 dist、当前三个 Nginx 配置和 `Dockerfile.acr`。因此不会受源码目录 `.dockerignore` 忽略 dist 的影响，也不会覆盖源码配置。
6. 使用 `Dockerfile.acr` 构建前端、检查平台、推送并核验 digest。前端保持容器端口 8080 和 `/healthz`；现有 WebSocket、SSE、续传代理规则保留。
7. 再次核验两个远端 digest，生成 `manifest.json`。后续服务器部署必须继续比较容器内 index SHA256 与该清单值。

两个镜像都显式使用 `linux/amd64`、`--provenance=false --sbom=false --load`。前端始终 `--no-cache`，确保打包的就是已验证 dist。

所有发布状态均保存在 Git 忽略的 `.codex-tmp/releases/<版本>/`：

- `checkpoint.json`：安全恢复用的来源指纹、镜像本地 ID、已确认远端 digest 和前端 SHA256。
- `manifest.json`：发布版本、来源 commit/dirty 摘要与输入指纹、镜像地址和 digest、前端 index SHA256、UTC 时间。仅在两个镜像发布并最终核验后生成。
- 随机命名的前端构建上下文：保留供复核；脚本不删除用户文件。

这些文件不包含 Secret。来源指纹排除本地 Secret 文件、运行时目录和构建输出；其余构建输入逐文件 SHA256 用于防止恢复时误用旧镜像。

## 恢复与重建

```powershell
.\scripts\release-v4.ps1 @publish -Resume
.\scripts\release-v4.ps1 @publish -Rebuild
```

`-Resume` 要求 checkpoint 的版本、目标仓库、commit 和构建输入指纹匹配。跳过构建前还会检查本地镜像 ID 与平台，前端同时检查 dist index SHA256；跳过已推送阶段必须再次验证远端 digest。任何不一致均停止。

`-Rebuild` 与 `-Resume` 互斥，重新执行构建并禁止使用后端构建缓存。它只在两个远端标签都不存在时可用，不是覆盖已发布标签的开关。

如果推送实际成功但进程在写入 checkpoint 之前中断，脚本会将该标签视为未知的已有发布并停止，必须先核对实际发布记录。源码在部分镜像已发布后发生改变，也不得强行覆盖原标签；需调查并使用经确认的新版本标签。

每次推送前均重新检查远端标签。Docker CLI 不提供原子条件推送锁，同一版本不得同时启动多个发布任务。

此脚本已通过 PowerShell Parser 静态语法检查；真实构建、镜像推送与服务器验收以实际执行结果为准。
