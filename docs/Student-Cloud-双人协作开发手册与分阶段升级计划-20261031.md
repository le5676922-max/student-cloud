# Student Cloud 双人协作开发手册与分阶段升级计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. 未确认设计和任务契约前，只编写文档，不实施。

**Goal:** 让两位开发者通过可审核的Git分支协作，依次交付可靠云盘、智能文件处理与RAG2.0、受授权Agent及按需生态扩展。

**Architecture:** 保持现有三个业务服务与Gateway，文件事实和授权归内容服务，身份归平台，知识投影与规划归智能服务。使用版本、操作收据和事件契约连接业务，不通过跨库读表实现集成。

**Tech Stack:** Java21、现有Spring Boot/Spring Cloud、MySQL8.4、Vue3/Vite、pnpm11.19.0；OCR、外部Embedding和Qdrant均为后续待验收依赖，不是当前已接通能力。

**Spec:** 本文第1、7–11节为本轮设计建议，第12–16节为任务与协作执行约定；两位开发者确认后，先冻结当期接口契约，再按任务实施。

> **先可靠，再智能，最后自动执行。**
>
> 开发者 A 主要负责文件底座与内容服务，开发者 B 主要负责智能检索与前端体验；每人负责完整可验收任务，相互评审。代码通过短期功能分支与 Pull Request 合作，不直接在 `main` 上共同开发。

| 项目 | 约定 |
| --- | --- |
| 编写日期 | 2026-10-31；首次启动协作建议安排在2026-11-01 |
| 状态 | **待两位开发者确认的开发方案，不是功能完成报告** |
| 仓库 | [le5676922-max/student-cloud](https://github.com/le5676922-max/student-cloud) |
| 当前本机项目 | `C:\Users\31717\Desktop\yun\student-cloud-github` |
| 当前开发后端 | `backend-independent`，不是历史 `backend` |
| 当前前端 | `frontend`，保留现有主题、登录动画与小芽形象 |
| 后端结构 | 平台服务、内容服务、智能服务 + Gateway，共4个 Java 进程 |
| 目标环境 | 两台阿里云 ECS，每台2核4GB；先隔离开发、验收，再决定上线 |
| 本文交付范围 | 业务路线、分工、Git流程、启动、接口与迁移协作、测试门禁、验收和风险 |
| 本次没有执行 | 创建/推送分支、提交代码、调整仓库权限、部署、修改业务代码或生产数据 |

**与此前计划的关系：**本文调整总体优先级。此前[右键格式转换计划](superpowers/plans/2026-11-01-business-upgrade-and-conversion.md)保留作功能细化参考，但其“明日优先转换”安排不再采用。新的顺序是：**可靠云盘底座 → 智能文件处理引擎与 RAG 2.0 → Agent → 可选业务生态**。格式转换纳入受控文件处理工具，不先于权限、容量和恢复能力上线。

## 阅读顺序

- **只想开始合作**：先读第1、3、4、5、6节。
- **开发者 A**：重点读第7、11、12、14节。
- **开发者 B**：重点读第8、9、11、13、14节。
- **明天怎么做**：直接看第15节，然后按第4节准备分支。
- **准备合并或交付**：检查第5、13、14、16节。

### Global Constraints（全局约束）

- 保留现有前端主题与三个业务服务，不因本计划重新拆分或重画整站。
- 文件不覆盖、内容授权即时复核、容量预留可核对；数据库实体与秘密不直接返回界面或模型。
- `main`通过PR更新；两人各用自己的账号与私有环境，不共享秘密、不自动部署。
- 既有迁移不修改；新增依赖、模型调用、资料外发和生产切换须在各自范围内明确授权。
- 已有能力增量升级；未运行测试不记通过，模拟/协议测试不冒充真实厂商与模型验收。

### Review Focus（高风险评审重点）

- 大文件合并与版本同时存在时的峰值磁盘、额度竞争及不覆盖：T01–T04。
- 文件更新、删除、撤权与迟到索引/答案之间的竞态：T06–T09。
- 多种任务消费者误领取、旧租约仍有文件副作用：T04、T06、T11。
- 两人修改相同迁移、契约和锁文件，或误上传未提交秘密：T00及每个PR。
- 任务取消、部分成功和发布不明被误报为“已回滚/可重试”：T04、T10、T11。

---

## 1. 最先明确的决定

### 1.1 需要 GitHub 分支吗？

**需要功能分支，但不必先到 GitHub 网页手工创建。**

通常先在本机创建分支，提交后首次推送，远端自然出现同名分支：

```powershell
# 必须先完成第4节的基线交接；本段只是分支创建示例。
git switch -c feature/upload-large-files
git push -u origin feature/upload-large-files
```

分清三个动作：

| 动作 | 作用 | 不会做什么 |
| --- | --- | --- |
| `git clone` | 首次取得仓库和提交历史 | 不会拿到另一台电脑未提交的文件 |
| `git push` | 将本地提交上传到远端分支 | 不自动合并 `main`，不等于网站部署 |
| PR 合并 | 评审后把分支成果集成到 `main` | 不自动证明运行正确，也不默认授权部署 |

### 1.2 推荐的分支方案

| 方案 | 适用情况 | 本项目建议 |
| --- | --- | --- |
| `main` + 短期功能分支 | 两人、小步集成，维护成本低 | **采用** |
| `main` + `develop` + 功能分支 | 同时维护多个发布批次 | 当前不额外引入，避免两条长期分支反复同步 |
| 两人共用一个长期开发分支 | 容易互相覆盖，评审边界不清 | 不采用 |

推荐流程与 GitHub 的分支式协作方式一致：分支开发、提交推送、PR评审再合并。[GitHub flow](https://docs.github.com/en/get-started/using-github/github-flow)

### 1.3 产品定位

Student Cloud 分为三个协作层次，而不是互不关联的页面集合：

1. **底层：可靠的多云文件管理。** 文件身份、版本、容量、恢复和操作记录可信。
2. **中层：智能文件处理与知识检索。** 文件可解析、可追溯、可更新，检索严格遵守权限。
3. **上层：真实任务 Agent 工作空间。** 小芽调用明确授权的工具，执行前可预览，失败可核查。

贯穿式验收案例：上传一份实验指导书 → 识别类型并解析 → 用户确认归入课程 → 有权成员可以多文档问答 → 小芽提出实验计划与资料整理方案 → 用户确认后保存 → 文件更新或撤权后，旧检索内容不再被使用。

## 2. 当前基线与真实欠账

### 2.1 不从零重建已有功能

| 能力 | 当前事实 | 升级方向 |
| --- | --- | --- |
| 微服务 | 3业务服务 + Gateway；库按服务隔离 | 保留边界，不重复拆分 |
| 文件上传 | 有本地上传、4MiB分片与持久会话；主文件处理仍受50MiB限制 | 大文件端到端流式和预算升级，不只改常量 |
| 续传暂存 | 当前单账号100MiB、全局1GiB；合并存在额外临时文件 | 新预算考虑分块、合并、发布及历史版本同时占用 |
| 回收与分享 | LOCAL已有7天回收及恢复、必须登录的单文件分享 | 补版本历史、恢复策略和统一操作收据；不重造回收站 |
| OSS/S3/MinIO | 已有有限读取与不覆盖写入能力 | 逐能力验收；远程生命周期不默认与LOCAL等同 |
| 容量 | 已有成员配额与扫描、续传暂存预留 | 增加持久容量账本和校准，不能把现有扫描称为完整账本 |
| 课程 | 已有共享课程、邀请、人数/到期/撤销、持续上传资格审批 | 加资料版本与索引生命周期，不重建课程系统 |
| 知识检索 | 本人LOCAL资料提取、词法匹配、来源摘要与权限复核已有基础 | OCR、全文索引、Embedding、混合检索、多文档引用 |
| Memory | 明确保存的本人记忆、开关、召回及外发同意边界已有基础 | 保留控制权，不自动收集全量聊天 |
| Agent | 已有规则/受限模型计划、预览确认、持久任务、幂等执行和部分/不确定结果 | 扩充工具、审批与恢复，复用现有链路 |
| 反馈 | 已有“没有解决”→整理→审核发布/撤回 | 补知识内容和评测，私人来源不自动共享 |
| CI | 已有独立后端和前端自动校验 | 补当前微服务MySQL端到端、索引隔离和大文件测试 |

### 2.2 不能忽略的当前状态

以下是文档编写时、主分支上传前的检查快照，不是永久不变的仓库状态。本文随当前代码完成提交上传后，新成员以实际远端SHA为准；已完成交接且工作区干净时，跳过第4.2节，不重复创建基线交接分支。

- 当前本机在 `main`，存在大量未提交改动，包含之前的业务修复、注释与知识相关工作。**GitHub上的 `main` 不等于本机所有最新文件。**
- 本地还存在 `feature/learning-library`；不要把它随意复用作新一轮共享分支。
- 当前独立项目CI文件为 `.github/workflows/independent-verify.yml`；历史 `verify.yml` 是手工单后端流程，不能把它的MySQL/Redis覆盖算到新后端。
- 现有 `CONTRIBUTING.md`、旧快速开始及 `scripts/start-local.ps1`、`scripts/verify.ps1` 仍有历史 `backend` 指向；明天需要校正入口，不能直接让同伴按旧脚本启动。
- 最近记录的完整后端测试曾在SMTP重置邮件用例出现预期200、实际503；需要定位并重测。注释阶段编译成功，不表示这一失败已经解决。
- 只读远端检查显示两条现有分支的 `protected:false`，规则集为空；本计划**未设置**分支保护。保护详情端点另有权限限制，不能据此声称已审计所有组织策略。

这些属于协作启动时必须核对的检查点；本文件不把历史测试结果自动延续为新基线。

## 3. 两人分工：按可交付任务分，不按页面硬切

用 A/B 标识责任人，开始协作时替换为真实姓名与 GitHub 用户名。

| 范围 | 主要负责人 | 配合/评审人 | 交付边界 |
| --- | --- | --- | --- |
| 文件传输、版本、容量、恢复、内容库 | A | B | 业务接口、持久状态、失败恢复、测试 |
| OCR/解析、Embedding适配、混合检索与问答 | B | A | 内容有出处、索引生命周期、权限隔离、评测 |
| 文件页与版本/容量/任务界面 | B | A提供契约并验收 | 复用当前主题，状态完整，操作可取消 |
| 账号、管理员交接、课程权限 | A | B做交互与回归 | 不扩大私人内容读取权限 |
| Agent工具与执行 | A负责内容工具；B负责模型规划与界面 | 互审 | 同一业务接口，不建立绕权捷径 |
| Gateway、父POM、数据库迁移、锁文件、CI | 当个任务明确唯一编辑人 | 另一人审核 | 先约定再修改，减少冲突 |
| 测试与文档 | 各自任务负责人 | 另一人独立复核 | 不把测试全部留给另一人或最后一天 |
| 合并与发布 | A/B轮流担任当天集成人 | 另一人批准 | 合并不等于部署；生产发布另确认 |

**避免等待的方法：**A实现真实API时，B可先依据已确认契约开发交互与模拟响应；模拟状态只用于开发，PR完成前必须连接真实接口验证。A不能连续几天只做数据库而不给可调用契约，B也不能只交一套静态页面。

每天开始用10分钟确认：今日Issue、分支、修改文件、依赖、谁编辑共享文件；结束时记录：提交SHA、PR、测试、阻塞和下一步。

## 4. 开工前：基线交接、权限与首次拉取

### 4.1 仓库负责人先做的事

1. 邀请第二位开发者为仓库协作者，授予完成开发所需的写入权限；不共享GitHub账号、PAT、SSH私钥或服务器root凭据。
2. 第二位开发者接受邀请，使用自己的账号认证。公共仓库可直接拉取，但向原仓库推送仍需写入权限。
3. 共同确定基线SHA。先整理当前本机改动，再让对方开始拉取；不能只发送一个文件夹压缩包当长期协作方案。
4. 确定是否允许功能分支推送触发部署。当前只允许测试，不能将“推送分支”自动连到正式网站。

### 4.2 当前电脑的未提交代码如何交接

**仅由持有当前改动的人操作。下面是待执行方法，本次没有替你运行。**

```powershell
# 工作目录：C:\Users\31717\Desktop\yun\student-cloud-github
git status --short
git branch --show-current

# 将当前工作区保留在新分支；不会提交，也不会自动上传。
# 如同名分支已存在，先核对，不覆盖。
git switch -c chore/baseline-handoff

# 看清改动后，逐项选择相关文件暂存、提交。
# 先处理依赖的业务修复，再处理注释/文档等独立提交。
git diff --name-only
git diff --cached --name-only
```

随后：

- 排除 `.env`、私钥、真实邮箱授权码/API密钥、个人文件、数据库、日志和生成目录。
- `.gitignore` 只影响未跟踪文件；已跟踪文件里的秘密不会因此安全。仓库中受跟踪的Vite `.env*`只应包含公开构建开关，**绝不放服务端密钥**。
- 不盲目 `git add .`，不把全部历史改动塞入一个新功能提交。按真实文件逐项暂存，运行测试，审核暂存差异。
- 运行 `git diff --cached --check`，并检查暂存内容是否含秘密；命中可疑内容时在本机处理，不粘贴到聊天或Issue。
- 在此分支创建基线交接PR，写清功能、实际测试、未完成项。若仍有未完成代码，拆为不进入基线的独立分支/任务，不能硬合并。
- PR批准后合并到 `main`，双方记录最终SHA。没有完成这一交接，第二人拉取不到当前本机完整状态。

不要用 `git reset --hard`、`git clean -fd`、强制切换或强推来“同步最新”，这些可能删除尚未保存的工作。

### 4.3 第二位开发者首次拉取

**新电脑示例目录：`C:\dev`。目录可替换，不要在已有项目目录里再clone一层。**

```powershell
# 首次拉取；若已存在同名目录，不重复执行clone。
New-Item -ItemType Directory -Path C:\dev -ErrorAction SilentlyContinue
Set-Location C:\dev
git clone https://github.com/le5676922-max/student-cloud.git
Set-Location C:\dev\student-cloud

# 仅为本仓库设置提交署名；邮箱可用GitHub提供的noreply邮箱。
git config user.name "YOUR_GIT_NAME"
git config user.email "YOUR_GIT_EMAIL"

# 只允许自动快进拉取，避免无意产生额外合并。
git config pull.ff only
git fetch origin --prune
git switch main
git pull --ff-only origin main
git status --short
git rev-parse HEAD
```

核对最后SHA与交接记录一致。HTTPS认证交给系统凭据管理器/浏览器流程，不将Token拼入仓库URL或写进命令历史。SSH可以使用，但每人生成自己的密钥，在GitHub绑定公钥，不交换私钥。

### 4.4 查看同伴尚未合并的分支

```powershell
git fetch origin --prune
git branch -r

# 示例：远端已有此分支，本机尚未创建。
git switch --track origin/feature/upload-large-files
```

本机已存在该分支时使用 `git switch feature/upload-large-files`。查看/运行对方分支前确保自己的工作已妥善保存；默认不要在对方分支直接提交，反馈通过PR评论或自己的修复分支完成。

## 5. 每天的 Git 与 PR 工作流程

下方命令块展示操作顺序，**不是遇错仍可继续的一键脚本**。逐条执行并查看结果；Git、Maven、pnpm等外部命令执行后，PowerShell中的 `$LASTEXITCODE` 非0就停止并处理原因。只设置 `$ErrorActionPreference` 不能保证这些外部命令失败时自动停下。

### 5.1 一个任务一个短期分支

命名建议：

| 类型 | 示例 |
| --- | --- |
| 新业务 | `feature/upload-large-files`、`feature/quota-ledger` |
| 智能检索 | `feature/rag-hybrid-search`、`feature/document-ocr` |
| 交互 | `feature/file-version-ui` |
| 修复 | `fix/upload-cancel-recovery` |
| 文档/构建 | `docs/two-developer-guide`、`chore/current-ci-gates` |

不是每个人永远只有一条分支。任务完成并合并后，从最新 `main` 创建下一条。

### 5.2 开始新任务

先保证工作区干净；`git status --short`有输出时，先回到该任务保存，不直接执行后续切换。

```powershell
git fetch origin --prune
git switch main
git pull --ff-only origin main
git switch -c feature/upload-large-files
```

如果本地 `main` 有未推送提交导致不能快进，不强制重置；先识别本地提交归属，转移到合适分支并评审，再恢复约定。

### 5.3 提交与上传自己的成果

```powershell
# 查看真实改动，选择本任务文件；以下是文件名明确的示例。
git status --short
git diff
git add -- backend-independent/cloud-drive-application/src/main/java/io/studentcloud/drive/application/MountService.java
git add -- backend-independent/cloud-drive-application/src/test/java/io/studentcloud/drive/application/MountServiceTest.java

# 确认没有误暂存其它任务或秘密，再提交。
git diff --cached --check
git diff --cached
git commit -m "feat(upload): add bounded large-file transfer support"

# 首次上传此功能分支；同时设置跟踪关系。
git push -u origin feature/upload-large-files
```

后续在同一分支增加提交后，使用 `git push`。`Everything up-to-date`只表示没有新提交可推送，不表示未提交文件已经上传。提交失败时先修正原因，不绕过检查。

推送不会自动把成果合并到 `main`。[Git push 官方说明](https://git-scm.com/docs/git-push)

### 5.4 每天同步主线，默认不改写共享历史

在自己的任务分支、工作区干净时：

```powershell
git fetch origin --prune
git switch feature/upload-large-files
git merge origin/main

# 如发生冲突，按5.6处理；成功后重新测试并正常推送。
git push
```

本团队默认采用merge同步，不要求新手rebase，也不需要强推。不要在未确认分支归属时执行 `push --force`。若以后共同决定rebase，仅限自己的分支并另行制定规则，不混用两种协作习惯。

### 5.5 提交 Pull Request

GitHub仓库 → Pull requests → New pull request：

- `base`：`main`。
- `compare`：自己的功能分支。
- 关联Issue；只包含一个可独立验收任务。
- 未完成用Draft PR，不能写“可以合并”。
- 邀请另一位开发者review；自己不能代替对方批准自己的代码。

PR描述模板：

```markdown
## 目的与范围
- 关联Issue：
- 解决的业务问题：
- 明确没有修改的范围：

## 接口 / 迁移 / 配置
- 新增或变更契约：
- 数据库迁移与兼容顺序：
- 新配置、默认值、功能开关：

## 实际验证
- 命令、环境、提交SHA：
- 成功/失败/跳过及原因：
- 越权、取消、重复请求、重启与异常结果：
- 界面截图或可复现步骤（仅合成数据）：

## 回退与剩余风险
- 关闭开关/回退方法：
- 不可逆数据影响：
- 未验证的真实服务：
```

评审人重点检查：是否越权、能否覆盖/删错文件、预算是否守恒、重试是否重复副作用、索引撤权是否有效、日志是否含秘密、UI是否误报成功。CI通过不是免审理由。

### 5.6 解决冲突，不“全部选我的”

```powershell
# merge报告冲突后先看文件。
git status
git diff --name-only --diff-filter=U

# 在IDE中逐个理解并解决；不能只删冲突标记。
# 解决后仅暂存自己确认过的冲突文件。
git add -- PATH_TO_RESOLVED_FILE

# 检查标记、接口和测试；全部冲突解决后完成合并。
git diff --cached --check
git merge --continue
git push
```

`PATH_TO_RESOLVED_FILE`是需要替换的路径，不要原样执行。无法确认合并语义时，联系另一人；本次合并前工作区应已干净，可以使用 `git merge --abort` 中止这次合并再讨论。Git也提醒在有未提交改动时进行merge可能难以完整恢复，因此不能跳过前置保存。[Git merge](https://git-scm.com/docs/git-merge)

以下冲突必须共同核对：

- **迁移版本**：先合并者保留版本，后者为尚未执行的迁移分配新版本；已执行迁移不得改内容。
- **接口字段/错误码**：确认双方调用与测试一致，不能把前端或后端一侧简单覆盖。
- **`pnpm-lock.yaml`**：先确认 `package.json` 的真实依赖，再由一人用项目指定pnpm生成；随后 `--frozen-lockfile` 验证。不要手拼锁文件条目。
- **父POM/Gateway**：检查依赖方向、路由与鉴权，避免为了编译删除架构约束。

### 5.7 合并与继续下一项

建议使用GitHub **Squash and merge**，一项业务留下一个清晰主线提交。合并前：review批准、必要CI成功、讨论关闭、迁移顺序明确、分支已同步最新主线。

合并后双方执行：

```powershell
git fetch origin --prune
git switch main
git pull --ff-only origin main
git rev-parse HEAD
```

远端功能分支可在PR页面确认合并后删除；不要删除对方仍在使用的分支。本地 `git branch -d 分支名` 在Squash后可能提示未合并，因为提交身份不同；不要为消除提示立即强制删除，可先保留并核对成果。

### 5.8 常见情况

| 现象 | 正确处理 |
| --- | --- |
| 切换分支会覆盖修改 | 停下，提交本任务文件到自己的分支；不强制切换 |
| 推送被拒绝 non-fast-forward | `fetch`后确认同名远端是否有他人提交，再正常merge；不强推 |
| 没有仓库写权限 | 接受协作者邀请；临时可fork+PR，但不要伪装成已能推送原仓库 |
| 忘记当前在哪 | `git branch --show-current`、`git status` |
| 对方看不到我的修改 | 检查是否commit、push到正确远端和分支，而不是只在IDE保存 |
| 提交误含密钥 | 停止传播、撤销/轮换密钥，联系负责人清理；删除当前文件不等于清除历史 |
| 合并后出现业务回归 | 关闭功能开关；新建修复/回退PR，用revert保留历史，不重写main |

## 6. 开发环境与本地启动

### 6.1 两人各用自己的环境

| 工具 | 当前建议 |
| --- | --- |
| Git | 支持 `git switch` 的版本 |
| JDK | Java21 |
| Maven | 3.9+ |
| Node.js | 22.13.0或以上、与CI的Node22主版本保持一致 |
| pnpm | 按当前 `frontend/package.json`：11.19.0 |
| MySQL | 8.4系列，正式一致性验收必须使用MySQL，不以H2代替 |
| Docker | 本地隔离联调/工作流夹具需要；不要求所有单元测试都启用 |
| Python | 3，用于已有合成业务工作流；Windows可使用 `py -3` |

每个人独立数据库与文件目录，不共用线上库、不共用一套开发库做破坏性迁移测试。只共享合成资料及配置模板，秘密各自保存在本机。

### 6.2 打开项目与先编译

- IDEA打开当前仓库的 `backend-independent/pom.xml`，导入16模块，不打开历史 `backend/pom.xml`当新主线。
- VS Code/IDEA前端打开 `frontend`；整仓库仍是一个Git仓库，不为前端再 `git init`。

```powershell
# 从自己的仓库根目录执行；不要照抄另一人的机器盘符。
java -version
mvn -version
node --version
pnpm --version

mvn -B -f backend-independent/pom.xml verify
Set-Location frontend
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test:unit
pnpm build
Set-Location ..
```

失败先记录命令、首个原因和退出码。不要用跳过测试证明已通过；首次下载依赖需要网络，日常不要无必要升级全部依赖。

### 6.3 原生开发：四个Java进程 + Vite

以下方式用于快速开发，默认 `local` profile 为独立H2内存库，重启丢数据。**不能代替MySQL验收，邮箱/外部模型未配置时不可用是正常边界。**

先在仓库根目录准备本机文件目录与一份私有开发环境脚本。`.local-data` 已被忽略；先验证忽略规则，脚本不能含真实生产秘密：

```powershell
New-Item -ItemType Directory -Path .local-data -ErrorAction SilentlyContinue
git check-ignore .local-data/dev-env.ps1
if ($LASTEXITCODE -ne 0) { throw '开发配置路径未被Git忽略，停止生成' }

# 应输出该路径；若没有输出，先处理忽略规则，不继续写秘密。
$envScript = Join-Path (Get-Location) '.local-data/dev-env.ps1'
if (Test-Path -LiteralPath $envScript) { throw '已有开发环境配置，请复用并检查，不覆盖' }

$keyBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
$rng.Dispose()
$internalKey = [Convert]::ToBase64String($keyBytes)
$localRoot = Join-Path (Get-Location) '.local-data'
foreach ($name in @('managed','courses','uploads')) {
    New-Item -ItemType Directory -Path (Join-Path $localRoot $name) -ErrorAction SilentlyContinue | Out-Null
}

# 路径中的单引号先转义；生成仅本机使用的环境脚本，不打印密钥。
$escapedRoot = $localRoot.Replace("'", "''")
$body = @'
$env:SC_INTERNAL_KEY='INTERNAL_KEY_VALUE'
$env:SC_MANAGED_STORAGE_ROOT='LOCAL_ROOT_VALUE\managed'
$env:SC_COURSE_STORAGE_ROOT='LOCAL_ROOT_VALUE\courses'
$env:SC_UPLOAD_STAGING_ROOT='LOCAL_ROOT_VALUE\uploads'
$env:SC_CONTENT_NODE_ID='local-dev'
$env:SC_ALLOWED_ORIGINS='http://127.0.0.1:5173,http://localhost:5173'
$env:SC_MAIL_ENABLED='false'
$env:SC_AI_API_KEY=''
$env:SPRING_PROFILES_ACTIVE='local'
'@
$body = $body.Replace('INTERNAL_KEY_VALUE',$internalKey).Replace('LOCAL_ROOT_VALUE',$escapedRoot)
Set-Content -LiteralPath $envScript -Value $body -Encoding utf8
```

四个终端都从**同一仓库根目录**执行 `. .\.local-data\dev-env.ps1`，复用同一随机内部密钥。不要每个窗口各生成一个，也不要将A机器的密钥发给B。若IDE运行，配置本机相同环境变量。

先构建运行JAR：

```powershell
# verify成功后通常已有产物；缺少时可构建。
# -DskipTests仅用于产物构建，不是测试通过证据。
mvn -B -f backend-independent/pom.xml -DskipTests package
```

依次打开四个终端；每段是**不同终端**，不是在一个窗口连续执行：

```powershell
# 终端1：平台
. .\.local-data\dev-env.ps1
java -jar backend-independent/cloud-platform-service/target/cloud-platform-service-0.1.0-SNAPSHOT.jar
```

```powershell
# 终端2：内容
. .\.local-data\dev-env.ps1
java -jar backend-independent/cloud-content-service/target/cloud-content-service-0.1.0-SNAPSHOT.jar
```

```powershell
# 终端3：智能
. .\.local-data\dev-env.ps1
java -jar backend-independent/cloud-intelligence-service/target/cloud-intelligence-service-0.1.0-SNAPSHOT.jar
```

```powershell
# 终端4：Gateway
. .\.local-data\dev-env.ps1
java -jar backend-independent/cloud-gateway/target/cloud-gateway-0.1.0-SNAPSHOT.jar
```

启动后先检查：

```powershell
Invoke-RestMethod http://127.0.0.1:18101/readyz
Invoke-RestMethod http://127.0.0.1:18102/readyz
Invoke-RestMethod http://127.0.0.1:18103/readyz
Invoke-RestMethod http://127.0.0.1:18093/healthz
```

终端5启动前端：

```powershell
$env:CLOUD_API_TARGET='http://127.0.0.1:18093'
$env:CLOUD_BACKEND_MODE='microservices'
Set-Location frontend
pnpm dev --host 127.0.0.1 --port 5173 --strictPort
```

访问 `http://127.0.0.1:5173`。首次在安装页自行设置合成测试管理员，不在文档提供默认真实密码。四进程只有一个启动成功不算完成；端口被占用时先辨认对应进程，不直接结束所有Java/Node。

如果磁盘已有私有配置、端口或环境变量，应先核对；上述命令是新开发环境方法，本次未实际启动。JAR版本变化后按实际构建产物修改，不用旧4.6/5.0文件名。

### 6.4 MySQL隔离验收

推荐复用当前独立微服务[部署与本地联调说明](../backend-independent/deploy/microservices/README.md)，不是旧单后端Docker教程。

- 一个MySQL实例、三个专属库和账号：platform/content/intelligence，禁止跨库JOIN和共享root应用账号。
- 完整Compose教程在 **WSL2的Ubuntu或Linux/Ubuntu** 中执行，需要Docker可用；WSL2建议在Linux文件系统内独立clone同一基线，避免NTFS卷权限差异。原教程含 `sudo install -d -o 10001 -g 10001 -m 0700 ...`，不能原样用于Windows Git Bash或PowerShell。本手册的Windows原生开发采用第6.3节；若将来需要Git Bash专用Compose方式，另补Windows目录/卷权限步骤，不混用。
- Compose的应用与数据库默认走内部网络，前端暴露 `127.0.0.1:18094`；不能假设Gateway/数据库已映射到宿主端口。
- 数据卷名称和测试环境各自独立；停止用 `docker compose down`，日常不要随手附 `-v` 删除数据。
- 开发者需要Vite热更新直连容器时，另加只绑定loopback的开发端口覆盖文件，并经过审核；不要为了省事把数据库或内部接口暴露公网。

已有合成工作流命令，从仓库根目录执行，先构建JAR并保证Docker运行：

```powershell
py -3 backend-independent/tools/verify_business_workflows.py --java "$env:JAVA_HOME\bin\java.exe"
```

它创建带归属标记的临时MySQL、合成SMTP/远程存储/模型及四个Java进程，结束清理自己拥有的资源；不是给真实OSS/QQ账号发邮件，也不代表公网验收。`JAVA_HOME`未设置时先设置本机JDK21路径，不原样使用空路径。

## 7. 第一阶段：完善可靠云盘底座

**目标：文件可处理大于50MiB的数据，版本和容量可核查，操作失败可恢复。**

### 7.1 大文件：从50MiB提升到分级处理

拟定首批目标为 **LOCAL与已验收远程驱动的1GiB上传/下载**，通过后再调大。1GiB是计划目标，不是当前能力或承载保证。

不能直接将所有50MiB常量改为1GiB：

1. 区分上传、下载、预览、OCR/解析、模型上下文和转换的限制。大文件能保存，不代表允许完整解码、OCR或发送给AI。
2. 统一配置与能力响应，联动Spring multipart、Nginx、Gateway、驱动、配额、分片及前端校验。
3. 通过流/分片处理，禁止大文件 `readAllBytes`、整文件Base64或完整堆内缓存；正确关闭流、响应断开和临时文件。
4. 当前浏览器续传协议还校验固定50MiB最大值，必须随服务端能力协议调整，不能只有后端放开。
5. 调整单账号100MiB/全局1GiB暂存预算；合并、上传副本、历史版本同时占用必须计入峰值。
6. 保留“不覆盖已有文件”。续传合并不先删除同名目标；超时未知先核查，不重复发布。
7. 对OSS/S3逐驱动测试分段读取/上传、条件写入、取消与清理；未验收能力关闭并说明原因。

**首批验收：**49/50/51MiB边界、128MiB、1GiB流式样本；校验SHA256；中断续传、重启、坏分片、撤权、磁盘不足；记录RSS峰值、暂存峰值、CPU及p95。解析和预览继续有独立预算。

### 7.2 文件身份与版本

建议先为托管LOCAL建立稳定 `fileId` 与不可变 `fileVersionId`，不将目录快照里的展示ID直接认定为永久文件身份。现有 `FileCatalog` 是有界目录投影，其刷新/逐出不应决定版本身份。

- 新内容以新版本保存，不原地破坏旧版本；普通上传仍拒绝同名，更新文件使用明确的“上传新版本”操作。
- 版本元数据包含归属、大小、摘要、创建者、来源/任务与状态；版本对象使用不可变内部存储键。
- 元数据先准备、存储后核查再完成；数据库事务不能与文件系统/OSS组成真正原子提交，需要持久收据和补偿核查。
- 恢复旧版本以生成新的当前版本完成，历史保留；恢复仍检查当前身份、目录、配额和冲突。
- 首版建议保留最近10个版本，最长30天；需明确容量和清理策略，暂不自动开启旧数据批量清理。
- 外部云盘手动修改/删除可通过对象版本、ETag/摘要与校准识别；不把ETag一概当MD5，不把Bucket版本控制自动当平台版本管理。

### 7.3 容量账本

划清：当前文件、历史版本、回收站、课程资料、上传暂存与处理临时空间。**不能同时从“当前文件”转进“回收站”又重复收费，也不能以删除展示记录立即释放实际仍占用的字节。**

建议内容库账本记录 `ownerScope/ownerId/bucket/reservationId/operationId/fileVersionId/deltaBytes/state`。这里是拟定字段，不是已存在表。

- 配额检查：`已计费占用 + 活动预留 + 新增预留 ≤ 上限`，由同库原子事务保证。
- 上传/复制/新版本/转换开始先预留，成功结算真实值，确认失败释放；不确定结果保留待核查，不按0。
- 同一操作只能结算/释放一次，配额降低到小于占用时阻止新增，不擅删用户文件。
- 暂存磁盘另有全站预算；用户额度足够不代表服务器可以继续接收。
- 后台增量变更+定期校准，校准差异进入记录，不每次上传扫描整棵大目录。
- 引导已有数据时先扫描、标记覆盖范围、对账再启用账本，不能假设旧占用为0。

### 7.4 操作记录、幂等与恢复

为上传、创建版本、复制/移动、回收、恢复、清理等建立可查询的 `operationId`，绑定当前用户、范围、参数摘要及幂等键。

拟定状态：`PREPARED → RUNNING → COMMITTING → SUCCEEDED`；确定失败为 `FAILED`，确认无副作用的取消为 `CANCELLED`，结果不明为 `INDETERMINATE`。

- 相同键相同请求返回同一收据，不同请求冲突。
- 记录旧/新版本身份、预算收据、来源变更事件及核查证据；不把文件正文、密码、授权头或云凭据写入记录。
- 任务租约只保护状态提交；文件发布仍需不可变目标、不覆盖操作、条件状态及代次核验。
- 重启扫描超时操作，先核查存储证据再决定恢复，不对移动、删除和发布自动盲重试。
- 用户只看本人的允许范围；全局后台可看脱敏统计和失败类型，不默认读取所有私人路径/内容。

### 7.5 管理员与课程交接

引用现有[业务优先级建议](后续业务优化优先级-20261025.md)，加入空间管理员和课程创建者交接：

- 发起者指定接任者，接任者确认，最终提交前复查双方身份和版本。
- 不允许空间处于无人可管理状态；停用、离职和交接的先后顺序明确。
- 原管理员管理权限及时撤销，会话与资格变更生效；不把其私人目录或个人Memory自动转给接任者。
- 公共课程资产归课程，成员私人内容仍归个人；交接不是全盘读取授权。
- 转移冲突、接任者停用、重复确认、超时撤销均测试。此能力不作为第一天可完成的小改动。

## 8. 第二阶段：智能文件处理引擎与 RAG 2.0

**目标：从“搜索有限个人资料”升级为“有权限、可更新、可引用的文件知识系统”。**

### 8.1 文件处理流水线

```mermaid
flowchart LR
    V[已完成且获准使用的文件版本] --> E[内容库变更事件/Outbox]
    E --> J[智能侧索引任务]
    J --> D[类型识别与受控解析]
    D --> O[有需要才OCR]
    O --> C[分段与页码/位置]
    C --> L[全文索引]
    C --> M[经授权调用Embedding]
    M --> X[向量投影]
    L --> R[范围过滤+混合召回]
    X --> R
    R --> A[内容服务即时授权/版本复核]
    A --> Q[多文档问答+引用]
```

内容服务拥有文件/课程与原始授权；智能服务拥有处理任务、检索和知识投影。使用带签名身份的内部接口，不跨数据库读表。

### 8.2 OCR与解析

- 原有TXT/MD/PDF/DOCX提取继续复用，不重复重写；图片与扫描PDF再进入OCR。
- 首版OCR建议验证Tesseract中文/英文的受限Worker，不在两台小服务器常驻大型视觉模型；复杂表格/公式识别只作有覆盖说明的后续增强。
- 每份文件记录页数、成功页、失败页、截断、语言、解析器版本和源摘要；失败不生成“完整理解”的假状态。
- 处理对象不是整盘默认全量外发；用户选择个人、课程或空间范围，遵循已有读取和外发同意规则。
- 解析/OCR/转换共用重任务准入预算；默认只允许1个CPU密集处理任务，不与上传抢满CPU。

### 8.3 全文与Embedding：建议技术路线

| 组件 | 建议 | 说明 |
| --- | --- | --- |
| 事实与任务记录 | 当前MySQL8.4专属库 | 不增加跨库JOIN |
| 中文全文起步 | MySQL FULLTEXT + ngram，配实际中文样本验证 | 不能直接把相关度叫BM25；H2不证明相同语义 |
| Embedding | 可替换外部API端口 | 单独配置模型、维度、批量、超时、预算与外发授权，不假定聊天API就是嵌入API |
| 向量存储 | 优先评估单节点Qdrant、磁盘持久化和内网访问 | 是本计划的新依赖建议，当前未接入；资源验收不通过则不启用 |
| 混合排序 | 词法Top20 + 向量Top20，经权限/版本过滤与去重后RRF融合，最多取8段 | 初始参数，离线评测再调整，不直接相加不同分数 |
| 重排 | 首批不强制另加模型 | 先证明召回收益再付费重排 |

MySQL提供适用于中文等语言的ngram全文解析器；具体参数会影响索引和检索，必须在实际MySQL校验。[MySQL ngram](https://dev.mysql.com/doc/refman/8.4/en/fulltext-search-ngram.html)

Qdrant可采用带租户过滤的共享集合等方式；本项目仍需自有用户/课程资格复核，不能把向量库过滤当成最终鉴权。[Qdrant多租户说明](https://qdrant.tech/documentation/manage-data/multitenancy/)

**默认关闭外部Embedding。** 未配置供应商或未同意外发时明确显示“词法检索模式”，不声称RAG2.0语义检索已完成。模型/维度变化新建投影版本重建，不能将不同向量混入一个维度/模型空间。

### 8.4 更新、删除与撤权一致性

拟定事件字段：`eventId/schemaVersion/fileId/fileVersionId/ownerScope/ownerId/sequence/action/occurredAt`，不含完整文件正文或密钥。

- 内容事务写入版本事实与Outbox；智能侧通过有界内部事件拉取/确认消费，双方各维护自己的状态，不宣称一次跨库原子提交。
- 同一事件重复消费不重复索引；乱序旧版本不能覆盖新版本，删除墓碑不能被迟到任务复活。
- 更新完成前旧版本是否继续可用要明确：默认只回答当前已授权版本；新版本未就绪显示“重新索引中”，不混入旧引用冒充最新。
- 删除/撤权立即从事实侧阻断读取与回答；索引物理删除可异步，但过滤与最终鉴权不能等待它完成。
- 生成前复核片段资格；生成过程中撤权或版本变化，最终不交付包含失效私有来源的答案，不把模型已生成文本直接发回。
- “撤回后不再使用”不代表能收回用户先前已合法下载/看到的资料，产品提示不能做这种承诺。
- 同空间不同成员、跨空间、课程退出/移除、共享资格变更、同角色不同账号都要验证，不只检查角色字符串。

### 8.5 多文档问答和产品知识

- 允许用户明确选择多份有权限文件或课程，不默认扫描全部云盘。
- 引用包含文件名、版本、页码/位置、片段；不返回物理路径或带签名云URL。
- 资料之间矛盾要分别说明来源，无法支持的结论明确不确定；课程问题与平台使用帮助分开检索。
- 项目使用知识要详细覆盖账号、空间、存储、OSS/S3/MinIO连接/故障、文件、课程、转换、容量、恢复、Agent与人工服务。
- “没有解决”反馈由管理员整理、可选AI润色、检查来源/隐私后审核发布，再用原问题回归；不能将私人问答未经同意转为全局知识或训练集。

### 8.6 右键格式转换如何纳入

保留“右击文件 → 格式转换…”以及键盘/更多菜单入口；先静态PNG/JPEG，之后再做Office/PDF和有限音视频。

转换与OCR共享受控执行设施，但业务任务、预算与产物用途分开：转换结果是用户新文件，会占容量；OCR中间片段是知识投影，不能当用户文件偷偷收费或永久保留。任何转换都不覆盖原件，课程结果发布仍检查上传资格。

UI细节与20项转换验收参考此前计划；如果与本文总体优先级冲突，以本文的“先底座、后处理”顺序为准。

## 9. 第三阶段：Agent 与自动化平台

**复用已有预览→确认→任务→内容幂等执行，不重建第二套任务系统。**

### 9.1 工具与风险分级

| 风险 | 示例 | 执行规则 |
| --- | --- | --- |
| 只读 | 搜索、列目录、查询任务/容量 | 当前内容授权；私有外发另同意 |
| 可恢复写入 | 建目录、复制、转换、保存生成结果 | 先预览确认；后续可建立有范围/额度/时效的可撤销策略 |
| 高影响 | 移动、批量改名、回收、恢复、课程发布 | 明确逐次确认及结果收据，资格不由模型决定 |
| 身份/管理敏感 | 设密码、云凭据、授权、批准申请、管理交接 | 保留受保护人工界面，不由模型任意执行或读取秘密 |

“完成自动化”不等于“取消所有确认”。模型只能提出白名单计划，不能调用任意shell、SQL、URL、文件路径或修改自己权限。

### 9.2 多步骤与恢复

- 每步绑定输入版本、前置条件、目标、容量和幂等键；先预览实际影响，不能只给自然语言承诺。
- 将确定成功、跳过、失败、部分成功、结果不明分开；不能假装多步文件操作可整体数据库回滚。
- 取消只影响尚未完成步骤；已完成操作需要明确补偿计划和再次授权，不自动删用户结果。
- 能在原任务查询结果和安全恢复，不能整单从头重跑已完成移动/上传。
- Agent调用转换、索引与版本恢复使用同一业务服务接口；前端点按钮和模型调用不能规则不同。

### 9.3 必须先修的任务调度边界

当前 `TaskType` 枚举存在 `DOCUMENT_INDEX` 等类型，不等于生产消费者已实现。现有 `TaskService.claim` 不按类型筛选，`AgentWorker`主要执行文件整理。

第二阶段引入索引任务前，必须先增加按类型领取或唯一dispatcher，验证Agent不会抢走并拒绝索引任务。任务表通用化不能跨内容库与智能库直接操作；内容侧文件操作与智能侧索引/规划任务通过明确接口协调。

### 9.4 Memory、Wiki与表达

- Memory按本人管理，明确保存/修改/删除/关闭；与执行任务状态分开。
- RAG优先，Wiki仅在多资料整合有收益时加入，绑定来源、版本和失效关系，不把私人概念页共享到全站。
- 回答默认简洁：“结论 → 必要步骤 → 引用/任务结果”，不输出长篇无关解释。
- 人工客服是用户确认后的支持路径，不是每次检索不足都强制转人工。
- 自动评测是持续回归，不是无条件自动微调；外部训练须另行有数据授权、费用预算、足够样本和留出评测。

## 10. 第四阶段：综合业务生态

核心阶段通过后，再按真实需求挑选，避免同时开所有方向：

| 方向 | 必须复用的能力 | 不做的过度承诺 |
| --- | --- | --- |
| 课程学习 | 文件版本、课程资格、正文索引、引用 | 不能自动把个人资料变成课程公开资料 |
| 团队协作 | 审批、通知、管理交接、操作日志 | 不直接等同多人实时Office编辑 |
| 知识图谱/Wiki | 可追溯片段、来源失效、权限过滤 | 不能通过图关系暴露无权节点和私有文件存在性 |
| 媒体智能管理 | 受限解析、任务与容量、可选授权模型 | 不在2核机器默认全盘转码或识别人脸 |
| 第三方插件 | 白名单工具契约、独立凭据与预算、审计和撤销 | 不让外部插件执行任意宿主命令或共享root凭据 |

每个扩展都须回答：“它如何改善同一份文件从存储、理解到任务执行的链路？”回答不清就不纳入当前版本。

## 11. 服务、接口与数据库协作约定

### 11.1 保持当前边界

| 服务 | 数据与职责 | 不允许 |
| --- | --- | --- |
| platform | 身份、空间、管理员、成员资格、人工支持 | 直接访问内容/智能表 |
| content | 文件事实、版本、授权、容量、课程、文件操作与内容事件 | 持有外部模型全局管理或智能库连接 |
| intelligence | 处理任务协调、知识投影、检索、问答、Memory、Agent规划 | 绕过内容授权直接读任意文件路径 |
| Gateway | 路由、显式边界与调用保护 | 编排文件操作或访问业务数据库 |

16个模块是构建职责，不需要16个运行服务。当前内容POM还禁止直接依赖 `cloud-tasks` 等智能任务实现；不能为了代码复用随意删除架构限制。

### 11.2 先合并契约，再并行实现

为每项跨人任务先提交小的契约PR，内容包括请求/响应、错误、权限、幂等、状态、分页、兼容和样例。建议维护在 `backend-independent/contracts` 下的当前契约文档或测试资源中，不只留在聊天里。

拟定交接对象：

| 对象 | 提供者 | 必备字段/含义 |
| --- | --- | --- |
| `FileVersionRef` | A | `fileId, fileVersionId, sha256, sizeBytes, ownerScope, ownerId, aclRevision, state` |
| `ContentChangeEvent` | A | `eventId, schemaVersion, fileId, fileVersionId, aclRevision, sequence, action, occurredAt`及最小范围标识 |
| `IndexStatus` | B | `fileId, fileVersionId, state, coveredPages, failedPages, truncated, parserVersion, embeddingVersion` |
| `RetrievalCitation` | B | `fileId, fileVersionId, chunkId, page/position, excerpt`；展示前经即时授权 |
| `OperationReceipt` | A | `operationId, state, resultVersionIds, errorCode, retryPolicy, generatedAt` |

这里是**待冻结的新契约**，尚未作为代码创建。开发者不能把字段存在于本文当作接口已经可用。

建议新增接口方向：

- 内容：`/api/drives/{key}/files/{fileId}/versions`、明确的版本恢复动作、本人操作收据与容量视图。
- 智能：本人/获准课程资料的索引状态、重建请求、混合检索、多文档问答。
- 内部：有界内容变更事件拉取与消费确认、指定版本授权与片段读取。

实际命名先检查已有路由，补HTTP/契约测试后冻结；保持当前前端兼容接口，不因架构升级擅自改全部路径。

### 11.3 迁移与配置

- A/B分别拥有自己服务的迁移；跨人需要改同一服务时先登记版本号和合并顺序。
- 新增迁移，不改已执行SQL与校验值。当前内容迁移由 `ContentMigrations`显式注册，智能侧由 `IntelligenceMigrations`注册，不能只放SQL文件却忘记注册。
- 先增加可兼容新字段/表，再上线写入/读取，观察后才移除旧字段；主线提交记录最低兼容版本。
- 权限/文件事件带schema版本，消费者拒绝未知不兼容版本并报警，不静默忽略。
- 配置名、类型、默认、是否秘密、影响范围写入PR。新功能默认关闭，先在隔离环境启用。
- 数据库结构回退不等于代码revert；如果新数据不能被旧代码读取，需要兼容层或前向修复，不能盲目删除新表。

## 12. 可执行任务分解与合并顺序

每个任务按以下循环交付：**失败用例 → 确认失败原因 → 最小实现 → 相关测试 → 真实联调 → PR互审 → 合并**。不要只交类、表或页面而没有业务结果。

| 编号 | 负责人 | 任务与代码位置 | 依赖 | 必须证明的结果 |
| --- | --- | --- | --- | --- |
| T00 | A+B | 基线交接；当前CI/启动入口校正 | 无 | 第二台机器拿到相同SHA并启动当前主线；SMTP失败有结论 |
| T01 | A，B评审 | `cloud-drive-application`的Mount/Resumable、内容驱动；前端续传协议由B配合 | T00 | >50MiB端到端，数据哈希一致、内存有界 |
| T02 | A | 文件身份/版本与迁移、现有FileCatalog适配 | T00，契约先合并 | 新旧版本独立，刷新目录不丢稳定身份 |
| T03 | A，B配容量UI | 内容库账本、预算/结算/校准 | T02；T01最终验收须联动 | 并发不超配额，未知状态不释放实际占用 |
| T04 | A+B | 统一操作收据、恢复与前端状态 | T01–T03 | 重启/取消/重复请求不覆盖/重复发布；旧版本恢复成新版本 |
| T05 | A，B配交互 | 账号/空间/课程交接 | T00，独立小批次 | 双方确认、管理不断档、个人资料不转移 |
| T06 | A提供事件；B消费 | 内容Outbox + 内部事件契约 + 智能任务按类型领取 | T02–T04 | 重复/乱序/删除事件不重建失效索引、不被Agent抢任务 |
| T07 | B，A审查沙箱 | 复用LearningTextExtractor、增加OCR/类型识别与受限Worker | T06 | 覆盖与失败记录真实，资源受限，来源可核对 |
| T08 | B | 全文、Embedding适配、向量投影与版本迁移 | T07；供应商授权 | 真实词法+语义模式区分、维度正确、可重建 |
| T09 | B，A授权接口 | 混合检索、多文档引用、撤权复核 | T08 | 召回收益可测，删除/撤权/更新即时阻断旧内容 |
| T10 | B，A提供写接口 | 文件版本/容量/索引状态与右键转换 | 对应底座和Worker通过 | 原件不变、结果占额度、无权限和未知结果可处理 |
| T11 | B负责规划；A负责工具 | 复用AgentPlan/Worker/内容幂等执行，接入新工具 | T04、T09、T10 | 同一安全接口，预览/审批/部分结果/恢复可验证 |
| T12 | A+B | 产品知识、评测集、完整实验指导书场景 | 随各功能增量完成 | 帮助与当前实现一致，隔离与引用评测有证据 |

不要让A先写完所有底座再让B开始。阶段一期间，B可完成T00文档/CI配合、前端续传/状态、合成资料集、接口契约及已有词法检索基线评测；第二阶段再集中实现智能链路。

## 13. 测试、评测与 CI 门禁

### 13.1 当前CI覆盖与欠缺

当前独立workflow已有：后端 `mvn verify`、前端类型检查、Node单元测试、设计契约检查、生产构建。**没有因此自动覆盖**真实OSS/QQ SMTP、独立微服务MySQL端到端、大文件故障注入、真实模型评测或生产性能。

它使用路径过滤，仅部分目录变动时运行。若后续设为PR必需检查，应调整为每个PR都能产生必需状态，重任务在内部按变动条件执行；避免文档PR因workflow未触发而永久等待。当前自动push模式主要覆盖 `feature/**`，`fix/**/docs/**/chore/**`应依靠PR触发，或在T00统一扩展。

状态检查名称以GitHub实际运行页面为准，不凭 `backend`/`frontend`短名猜测。不同workflow的同名job可能造成歧义，应设唯一检查名。[GitHub分支保护说明](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)

### 13.2 各任务必须运行

```powershell
# 仓库根目录：后端完整；日常可先运行自己模块及依赖。
mvn -B -f backend-independent/pom.xml -pl cloud-content-service -am test
mvn -B -f backend-independent/pom.xml verify

# frontend目录：与当前CI保持一致。
Set-Location frontend
pnpm typecheck
pnpm test:unit
node scripts/check-design-contract.mjs
pnpm build
Set-Location ..
```

修改智能任务需运行智能模块及依赖测试；修改权限/内部契约需增加平台/内容/智能/Gateway联合验证。不要靠只跑最小模块掩盖跨服务编译或运行失败。

### 13.3 必测故障与业务场景

- **文件**：边界大小、哈希、慢客户端、断线、损坏分片、同名并发、不覆盖、Windows/Linux差异。
- **容量**：多任务抢额度、版本/回收占用、重复结算、外部改动、磁盘不足、校准和降额。
- **恢复**：发布后数据库提交前崩溃、重启、过期租约、取消竞争、部分成功、不确定状态核查。
- **权限**：同角色不同账号、同空间其他成员、跨空间、课程退出/移除、管理员交接、停用/撤权。
- **索引**：更新/删除/重复/乱序事件、迟到OCR、模型维度变化、部分页失败、授权范围改变。
- **RAG**：引用支持结论、无答案拒答、多文档矛盾、私有上下文未经同意不外发、文档中的提示注入不能触发工具。
- **前端**：成功/失败/等待/未知、刷新恢复、请求竞争、账号切换、键盘、窄屏、长文件名。

### 13.4 RAG评测与发布阈值

建议首批至少100条合成/许可资料问题，按个人/课程/产品说明、答案存在/不存在、资料版本、权限和中文同义问法分组。开发集与留出集按资料分开，不只改写同一个问题。

初始门槛是待校准目标，不是已取得成绩：

| 指标 | 初始目标 |
| --- | --- |
| 跨用户/空间/课程泄漏、越权工具、未经授权外发 | 确定性安全测试0失败，任一失败阻止发布 |
| 引用来源与当前版本有效性 | 确定性检查100%通过 |
| 有答案问题Recall@8 | 留出集目标≥85%，与当前词法基线比较 |
| 引用是否支持答案 | 人工按统一标准抽检，目标≥90% |
| 无答案问题处理 | 不虚构；单独报告拒答/误答，不混入整体“准确率” |
| 成本与延迟 | 记录调用数、token、费用、首字/总耗时和p95，不凭主观说更快 |

合成模型测试证明协议与权限，不证明真实模型推理质量；LLM裁判可辅助，但不能判定谁有权限。外部Embedding/LLM实际测试需本机私有凭据、明确预算和许可资料，结果分开记录。

## 14. 两台2核4GB ECS的资源与安全边界

先沿用当前布局：A放Nginx/Gateway/platform/MySQL，B放content/intelligence和受限处理单元。新OCR/转换默认单并发；不默认增加3–4个后端实例。

- 可选Qdrant拟定512MiB级初始内存上限，需用资料量、向量维度、磁盘/页缓存与p95实测确认；这不是推荐最低配置或承载保证。若放不下，缩小索引规模、调整部署或暂停语义模式，不能以OOM重启当正常扩容。
- Redis只有在明确的共享缓存、限流或通知场景接线后启用；不把缓存代替账本、任务事实或即时撤权。
- 当前单个platform的客服票据和单个content的文件位置都有状态限制；多副本前先解决共享状态与副作用，不能仅增加负载均衡目标。
- 解析/转换Worker禁网、非root、只读输入、单任务私有输出、受限CPU/内存/进程/时间/磁盘，无数据库/API/云凭据，无Docker socket。
- 内部服务、MySQL、Qdrant/Redis只在内网，公网仅必要HTTPS入口；新环境不能复用明文示例生产密码。
- Nginx和Gateway大文件预算一起调整；上传/下载/聊天流按路由测试，不将所有超时和缓冲全局关闭。
- 不承诺“优于其它项目”或“支持多少人”，同条件测元数据p50/p95、错误率、下载吞吐、索引队列、CPU/RSS和磁盘余量。

版本历史和回收站不是数据库/服务器备份。协作开发不要求保留旧线上数据，但生产灾难恢复策略须单独确定；不能因为能恢复误删文件就宣称服务器损坏也可恢复。

## 15. 明日安排与阶段排期

### 15.1 明天（2026-11-01）先完成什么

| 时间盒 | A | B | 共同结果 |
| --- | --- | --- | --- |
| 第1小时 | 清点本机未提交变更、基线交接 | 确认GitHub账号/写权限、准备环境 | 确认基线SHA，不带秘密 |
| 第2小时 | 定位SMTP回归/运行后端基线 | clone基线、安装依赖、前端与四进程启动 | 第二台电脑能跑当前项目，记录失败而非跳过 |
| 第3–4小时 | 写大文件与容量/版本契约，列受50MiB限制的调用链 | 复核续传前端协议、做基线样本和界面状态契约 | 合并小型契约PR，明确文件编辑人 |
| 第5–6小时 | T01新增失败用例与最小流式改造 | 前端按能力读取限制、处理断线/错误，先测后改 | 一个>50MiB合成文件的开发闭环或明确阻断点 |
| 最后1–2小时 | 相关回归、提交个人分支 | 界面/协议回归、提交个人分支 | 两个可review PR；未通过不合并main |

**不承诺明天完成第一阶段全部功能。** 完整版本、容量账本、恢复和RAG2.0是多轮迭代；明天成功标准是协作链路建立、有可靠基线与第一条可检验增量，不是把所有计划框打勾。

### 15.2 迭代规模建议

按两名能持续投入的开发者估算：

- 启动交接与契约：1–2个工作日。
- 第一阶段：约2–3个迭代，每迭代5个工作日；管理交接可并行但不牺牲文件安全门槛。
- 第二阶段：约2–3个迭代；Embedding服务准备、OCR质量和真实资料规模会影响工期。
- 第三阶段：约1–2个迭代；基于前两阶段已验收工具。
- 第四阶段：按需求单独估算，不给无限功能一个固定完成日。

这些是排期起点，不是保证。每个迭代结束按证据重新估算；不能把两人同时在线误算成吞吐翻倍。

## 16. 分支保护、合并与交付定义

### 16.1 GitHub待设置项

由仓库负责人在 Settings 的分支规则/Rulesets中为 `main` 配置，具体可用项受仓库和账号权限影响：

- [ ] 必须通过PR，至少另一位开发者1次批准。
- [ ] 新提交后旧批准失效，重要对话解决后才合并。
- [ ] 当前独立后端/前端必需检查成功；修正路径过滤造成的等待问题。
- [ ] 禁止强推与删除 `main`；管理员日常也遵守，不依赖绕过。
- [ ] 使用Squash合并；部署只对明确发布动作，不能每个分支自动上线。
- [ ] 针对平台秘密、真实资料和测试日志确认访问与保留策略。

本次只列方法，未修改GitHub设置。规则启用后先用小的文档PR验证不会卡死，再在双方确认后作为门禁。

### 16.2 一个任务“完成”的条件

1. 业务结果可在真实接口/界面复现，契约和错误状态一致。
2. 权限、幂等、取消、重启、容量与不确定结果用例经过实际验证。
3. 涉及数据结构时，空库升级和已有开发库增量迁移都通过，兼容和回退说明存在。
4. 单元、MySQL、浏览器、真实厂商/模型测试分开记，未运行项不写通过。
5. 配置模板不含秘密，帮助文档只描述已验收能力，必要许可证保留。
6. PR由另一人批准，CI通过，合并后的SHA双方可拉取并运行。
7. 生产发布另行确认；合并完成不能写“已部署”。

### 16.3 每日交接模板

```text
日期 / 开发者：
Issue / 任务编号：
分支 / PR / 最新提交SHA：
已完成业务结果：
实际测试及失败/跳过：
接口/迁移/配置变化：
对另一人的依赖：
已知风险与回退：
下一项具体动作：
```

## 17. 参考、范围与本次交付核对

本计划结合仓库中的业务优先级、独立后端说明、真实源码和CI配置形成：

- [现有业务优化顺序](后续业务优化优先级-20261025.md)：交接、版本、账本、幂等、课程与索引一致性。
- [当前独立后端](../backend-independent/README.md)：服务、端口、已实现与未完成范围。
- [当前部署/本地MySQL联调](../backend-independent/deploy/microservices/README.md)。
- [之前的格式转换细案](superpowers/plans/2026-11-01-business-upgrade-and-conversion.md)：仅复用功能细节，总体优先级以本文为准。
- 当前工作流、版本/文件/任务/课程/知识源码由主代理及两个只读子任务核对；没有运行新的产品全量测试或声称全部业务已完成。

官方资料支持Git流程和候选检索组件说明；技术方案中的数值、分工和排期均为针对本项目的建议，不是上游性能保证。本文未复制Filee桌面实现，也不以移除开源署名证明项目独立。

本次另经一位新上下文审阅者只读检查，修正了Git Bash/Linux教程混用及前端测试代码块缺少目录切换两处问题；没有因此运行文中的启动、Git推送或产品验收命令。

**本次文档自检要求：**UTF-8可读、Markdown结构闭合、内部链接存在、现有路径与拟新增契约分开、命令有工作目录与前置条件；没有用本机未提交代码冒充远端已发布版本。

> **现在的下一步：先让两人确认责任人和基线交接范围，再按T00开始。分支由本地创建、推送到GitHub；功能完成后PR互审合并，不直接把所有代码推到main。**
