# B04–B06 安全增量与联调门禁（2026-11-01）

## 已执行的增量（不等于完整任务验收）

- B04：`TaskService.claim(workerId, leaseSeconds, allowedTypes)` 在租约耗尽清理、候选选择（LIMIT 之前）和 CAS 三处限制类别。AgentWorker 仅允许 `FILE_ORGANIZE`，不再领取、确认或失败化 `DOCUMENT_INDEX`。保留两参数接口供确实支持全部类别的 dispatcher 兼容使用，类别消费者不得调用它。
- B04：intelligence 迁移 8 新增本库投影元数据、消费去重表。事件 schemaVersion=1；序号不前进的事件不会替换新投影/删除墓碑，未知 schema 返回明确错误。开始处理提升 generation，发布同时绑定文件、不可变版本、序号、摘要与 generation；迟到结果被拒绝。此处只有元数据，不是已接通的文件解析器/事件消费者/自动过期租约。
- B05：独立 EmbeddingProvider 协议校验批次/文本预算、模型及版本、维度、有限数值、返回数量与输入索引唯一性；关闭、无许可或无供应商时返回 LEXICAL 且零供应商调用。没有默认 HTTP 供应商、外部 URL 参数或付费调用。
- B05/B06：确定性的 rank-only RRF 工具使用 Top20+Top20、k=60、去重、最多8段；**当前生产检索没有接向量库，因此不返回 HYBRID**。
- B06：可复用检索服务只接受最多10个显式不可变版本 UUID、最多2000字问题。事实端口先核查授权和最新版本，候选再次按版本/文件/摘要/ACL过滤，最多200候选、8段引用。发布前再次核查；私有回答缓冲后复查，授权/版本变化丢弃回答。没有证据不调用生成器；没有当前外发许可使用本地原文摘录，不假称综合生成。
- UI：`DocumentResearchPanel` 默认 `enabled=false`，父页面用 `VITE_DOCUMENT_RESEARCH_ENABLED === 'true'` 显式开启。关闭时零请求；版本能力不可用时不展示不能工作的搜索表单。问题只在内存，清空/取消/账号变化使旧回答失效。支持片段按来源分组、本地展开并标页码，不返回签名云 URL 或物理路径。

## 当前真实运行边界

A 侧不可变版本、ContentFacts、Outbox事件及解析片段尚未冻结并接通，不能用当前文件路径或内容摘要拼出伪生产版本 ID。因此生产装配使用 `unavailableFacts()`，**不启动 DOCUMENT_INDEX 消费者，不写入私人正文，不自动发外部嵌入**。

新增精确 HTTP 合同：

| 方法/路径 | 当前行为 |
| --- | --- |
| GET `/api/ai/documents/capabilities` | 认证后返回 `mode=LEXICAL, sourceVersionSupport=false, externalEmbeddingEnabled=false, reason=DOCUMENT_FACTS_UNAVAILABLE` |
| POST `/api/ai/documents/search` | 参数不合法400；合法版本请求在事实端口返回503，不伪装空结果成功 |
| POST `/api/ai/documents/answer` | 同上；不改变原有 `/api/ai/chat` |

请求只有 `{query,fileVersionIds,optInPrivateContext}`；身份取当前会话，不能提交 ownerId、role、URL或主机路径。内部 FactsPort 的 `externalContextAllowed` 必须表示当前内容服务核验后的**显式外发许可**，不能从读取许可、索引许可或问答上下文勾选推导。

`POST /api/ai/documents/indexes` 的真实来源选择、读取/处理许可、任务创建与取消/重建尚未接通；迁移没有假装已有可查询的生产chunk存储或向量集合。现有本地词法匹配不是 MySQL FULLTEXT、ngram 或 BM25。后续接入必须另做真实数据库验证。

## A→B 待冻结合同与验收

1. 真实 `FILE_ID / FILE_VERSION_ID / SOURCE_HASH / ACL_REVISION / SEQUENCE`，当前账号下即时读取/处理/外发许可，停用、删除、变版及成员移除语义；无跨库 JOIN。
2. 有界内部事件读取/游标/确认、不可变事件ID、schemaVersion兼容与隔离报警；投影事件消费成功才推进游标，错误不能当已消费。
3. 授权获取不可变源及解析片段，覆盖/失败页和 parserVersion；Worker拿到的只可为内容服务授权的能力引用，不能任意打开本机路径。
4. 任务租约及处理budget与 metadata generation对接，真实重启/过期/重复发布、删除后迟到OCR和撤权测试。
5. MySQL8.4事务/中文ngram全文、真实模型/向量集合版本切换/成本预算测试；未批准不开外部嵌入。
6. 私有答案生成前后即时授权、引用定位再次授权、100条许可/合成评测集和独立留出集；Recall@8与引用支持率仍是待测指标，当前未宣称达标。

## 本次验证证据

证据目录：`C:/Users/31717/Desktop/yun/.codex-tmp/developer-b-20261101/b04/`。

- typed-claim RED：2个预期行为失败；GREEN：cloud-tasks 14/14。
- document-contracts RED：13个预期行为失败、0错误；后续 reactor 中 cloud-assistant 44/44通过。该 reactor 的 intelligence 146项有2个同步开发中的B07恢复 RED，因此整条命令退出1，不能称整套通过。
- 前端 Panel RED：缺少组件导致4项失败；GREEN：4/4。strict premium audit：0错误/警告。
- 四角色独立 AgentWorker probe：同一个 DOCUMENT_INDEX 输入，BASELINE `FAILED/attempts=1/pendingOutbox=0`；MODIFIED `QUEUED/0/1`；执行 ROLLBACK 后 `FAILED/1/0`，原始hash一致。使用真实 TaskService+隔离H2，**不是MySQL生产并发测试**。补丁可精确重建修改件。探针中 javac 路径转义和 git autocrlf 两次错误及修正已保留。

未执行：真实A来源端到端、MySQL全文/并发、真实外部模型/向量库、真实私有文件截图与质量分数。没有提交、推送或部署本增量。
