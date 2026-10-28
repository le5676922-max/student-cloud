# 独立后端项目帮助条目

数据文件为 `src/main/resources/ai/knowledge/project-help.json`，版本 `2026-10-27-independent-remote-v2`，共42篇，仅包含产品用法和已实现能力，不包含用户文件、反馈原文、凭据或旧后端依赖。`schemaVersion` 仍为1，原36篇的稳定 ID 与角色范围保持不变，新增6篇远程存储说明。每条记录有稳定 ID、角色范围、关键词、正文和对应独立源码路径。源码更新时需同步核对这些说明，不能把源码存在当成已经部署或已通过真实云验收。

`new ProjectKnowledgeService()` 只读取随 JAR 打包的资源。`search(Actor actor, String query, int limit)` 返回 `List<Hit>`，其中 `Hit(id, title, content, source, score)` 可直接供聊天构建上下文和项目知识依据面板使用。调用者先通过账号服务复核 Actor；本类只按该服务端身份筛选产品帮助，不承担实时账号授权。查询最长2000字符，返回1–20条，排序按本地词法分数递减、ID升序。分数不是语义置信度。

| 条目 | 角色 | 内容 |
| --- | --- | --- |
| site-overview / roles-and-privacy / login-and-account | 全部 | 产品范围、三个角色、登录和隐私 |
| register-workspace-admin / member-join-workspace | 成员、空间管理员 | 邮箱注册、空间入驻与加入审批 |
| forgot-password | 全部 | 找回密码、邮件失败与旧会话失效 |
| storage-empty-home / member-cloud-application | 成员、空间管理员 | 存储列表为空、个人目录、授权和额度 |
| file-upload-download / resumable-upload | 成员、空间管理员 | LOCAL与远程50MiB传输；LOCAL分片、24小时会话、节点绑定 |
| file-mutations / file-preview / file-search-index | 成员、空间管理员 | 目录操作、预览格式限制、文件名和正文搜索区别 |
| trash-and-quota | 成员、空间管理员 | 7天回收、200项上限、仍计容量、恢复与彻底删除 |
| controlled-file-sharing / share-expiry-and-revocation | 按条目定义 | LOCAL登录限定的单文件分享、范围、有效期、撤销与源变化 |
| personal-learning-start / learning-text-coverage | 全部 | 个人课程、归档、10MiB资料、100000字符/100页、无OCR |
| learning-refresh-relink / learning-question-and-citations | 全部 | 刷新和重新关联、源摘要校验、本轮同意、摘录依据 |
| shared-course-join / shared-course-upload | 成员、空间管理员 | 跨空间课程资格、上传审批、独立课程容量 |
| assistant-answer-style / assistant-attachments / assistant-memory | 全部 | 回答风格、临时附件与上传区别、主动记忆 |
| agent-preview-confirmation / agent-results-and-retry / agent-weekly-schedule | 成员、空间管理员 | 先预览确认、结果与取消边界、定时规则只生成预览 |
| answer-feedback | 全部 | 明确提交本轮反馈，不自动转人工或训练 |
| workspace-member-management / workspace-local-cloud-request / course-owner-management | 空间管理员 | 本空间审批、目录与额度、本地盘申请、课程治理 |
| platform-storage-administration / platform-knowledge-review / platform-statistics-meaning | 全局管理员 | 独立目录配置、反馈审核与来源限制、统计口径 |
| remote-storage-not-supported | 全部 | OSS/S3/MinIO已实现list/read/mkdir/upload，WebDAV/FTP/SFTP和其余远程操作未开放 |
| remote-storage-add | 空间管理员、全局管理员 | 添加OSS/S3/MinIO、字段、专用前缀隔离、停用后改连接与保留历史 |
| remote-storage-validation / remote-storage-write-safety | 空间管理员、全局管理员 | 填写检查、只读测试、读写探针、保存后权限、不覆盖、OSS版本状态与精确清理 |
| remote-member-privacy-quota | 成员、空间管理员 | 成员审批、固定个人根、远程配额不足、扫描预算与未开放操作 |
| remote-pending-write-inspection | 空间管理员、全局管理员 | UNCERTAIN/PUBLISHING定位、手动检查、肯定证据解冻、PENDING保持冻结 |
| remote-storage-credentials | 全局管理员 | 独立凭据主密钥、精确端点白名单、私有暂存、编辑空值含义和真实云验收边界 |

共享词法工具为 `cloud-assistant` 的 `KnowledgeTextMatcher`：中文按词段与双字片段、英文按词边界匹配，过滤常见疑问词，最多64个唯一检索词，标题和标签权重大于正文。审核知识仍只查询最近500个合格活动候选，保留空间和显式课程授权前后复核；未新增向量数据库、embedding、模型训练、外部解析或后台自动发布。

定向测试源码：`KnowledgeTextMatcherTest`、`ReviewedKnowledgeRelevanceTest`、`ProjectKnowledgeServiceTest`。本次在根任务构建完成后，以 `mvn -pl cloud-intelligence-service -am -Dtest=ProjectKnowledgeServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` 验证远程说明：先新增测试，旧资源产生3个断言失败和1个缺少条目错误；更新资源后7项全部通过，0失败/错误/跳过。回归覆盖普通管理员中文询问连接阿里云、成员远程配额不足、全局管理员定位未确认写入、角色过滤、版本与42篇契约、只读/读写保存语义、不覆盖和真实云验收边界。未调用外部模型、发送真实资料或部署；该测试不等于真实 OSS/S3 云账号验收。
