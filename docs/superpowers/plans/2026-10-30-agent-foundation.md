# AI智能云盘：执行与知识基础实施计划

> For agentic workers: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`，先失败回归，再实现，最后联合验证。

**Goal:** 在当前独立后端增加可测试的模型操作规划、私有知识使用闸门和详细产品知识评测基线；完成当前遗留安全与成员隐私修复。

**Architecture:** 保持三个业务服务及Gateway。模型只提出受限操作建议，内容服务继续负责预览与真实执行。Wiki后续建设复用有来源、版本与角色约束的产品知识，不在此阶段声称已完成任意课程的自动Wiki编译。

**Tech Stack:** Java21、Spring Boot、JdbcTemplate、JUnit、Vue3及Node test；合成模型/SMTP接口验证，不调用付费外部服务。

## 范围与顺序

本计划落实总设计的第一阶段及知识种子，不把全部Wiki、授权策略、共享课程正文索引或微调标为完成。

### 1. 完成当前成员隐私与安全修复

- [ ] 将 `frontend/src/pages/workspace-admin.vue` 的管理员代设初始密码改为打开已有邀请码面板；`frontend/src/api/home/workspace-admin.js` 删除手工创建入口。真实页面运行测试验证无设密字段、无自动创建请求、身份切换清理。
- [x] 普通管理员发邮件、成员自行重置；旧手工创建与改密HTTP入口先鉴权再410。账号及平台96项回归通过。
- [x] PDF子进程隔离、受限内存/截止时间、Windows进程清理与Boot嵌套类路径，内容25项定向回归通过；磁盘目录预算为周期检查，不宣称OS硬磁盘配额。
- [x] 兼容路由过滤器显式装配；分片上传活动预算与历史收据分离；邀请活跃额度与历史分离。联合测试再次检查。

### 2. 修复知识导出与记忆使用边界

Files: `cloud-assistant/.../AnswerFeedbackService.java`，`cloud-intelligence-service/.../config/IntelligenceConfiguration.java`，`.../ai/ChatService.java`及对应测试。

- [ ] RED：已有私有来源、没有来源、仅空间范围或已撤回的已解决反馈不能成为共享训练导出；只有当前活动GLOBAL审核知识且来源允许发布时才导出。
- [ ] 复用 `KnowledgeSourcePolicy`，缺失来源策略默认拒绝导出；活动知识的答案和来源反馈版本必须一致，不仅筛选 `RESOLVED`。
- [ ] RED：未选择 `optInPrivateContext` 时请求外部模型不含个人记忆；开启时只召回相关且仍启用的本人记忆。补充关闭/删除/账号变化后的复核。
- [ ] GREEN：运行 assistant 与 intelligence 定向测试；外部处理无授权的内存内容在组装请求前排除。

### 3. 有限模型规划接入已有安全执行链路

Files: 新建 `cloud-intelligence-service/.../agent/AgentModelPlanner.java`及测试；修改 `.../ai/ChatService.java` 与配置。

- [ ] RED：复杂但明确的整理、重命名、复制、移动、新目录及个人学习导入请求可生成受限计划；模型不能提交身份、任意工具、删除/覆盖、未选择的存储路径。
- [ ] 模型返回严格结构化建议：`kind=PLAN|CLARIFY|NONE`，允许的操作仅已有 `MKDIR/RENAME/COPY/MOVE/INDEX_MATERIAL`，最多12步；计划必须交由现有 `AgentPlanService.preview` 再次验证。
- [ ] 模型调用前判别显式操作目标、否定/询问及必要上下文；缺少文件/目录/课程时澄清，不扫描或把整盘内容发送模型。
- [ ] 不自动确认写操作；当前预览确认不伪称已升级为长期自动授权。未配置模型保留规则路由；模型异常不退化为猜测写入。
- [ ] GREEN：用本机合成模型响应验证支持操作、无效JSON、未知操作、重复/超预算、澄清与否定；不执行服务器shell或任意URL。

### 4. 详细产品知识与自动评测基线

Files: `cloud-intelligence-service/src/main/resources/ai/knowledge/project-help.json`、`.../ProjectKnowledgeService.java`、新增有来源的 `docs/knowledge/` 资料和版本化评测集及JUnit测试。

- [ ] 按当前实现校正成员自行设密、课程/空间邀请码、真实Agent边界，避免帮助误导。
- [ ] 新增Agent使用、自动化限制、Wiki与RAG区别、Memory隐私、反馈发布和故障恢复的详细资料，标明已实现与规划。每页有角色/步骤/失败处理/来源与覆盖边界。
- [ ] 版本化评测集覆盖真实业务自然提问、角色范围、无关问题、不可执行问题及格式；评测固定当前来源版本，分别记录检索与执行结果。
- [ ] 报告仅汇报实际通过项；不把合成模型的格式与安全测试称为真实模型智能效果。

### 5. 联合验证与交付

- [ ] 后端执行 `mvn -B -o "-Dmaven.repo.local=D:/develop/MAVEN—local repository" verify`，前端执行 `pnpm test:unit`、`pnpm typecheck`、`pnpm build`。
- [ ] 若HTTP联调脚本仍调用退休的手工创建接口，改为本机假SMTP+邀请码+成员邮箱注册，或单独记录旧脚本不再适配，不能宣称联调通过。
- [ ] 独立审查权限、异常路径、来源与计划状态；修正后仅重复相关测试及必要联合验证。
- [ ] 写实施记录，逐项区分本轮完成、未完成、不适用；不部署线上、不推送、不启动付费训练。
