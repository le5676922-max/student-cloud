# A Independent Work Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 交付不等待 B 的真实 A06 控制修复、LOCAL 大文件增量和可接入的 A 方交接说明。

**Architecture:** 沿用独立后端、现有内容流式存储与 Vue 面板。来源导出复用 KnowledgeSourcePolicy；记忆端到端 CAS；本地传输配置与远程/预览预算分离。

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring Cloud 2025.1.4, Vue 3, Node 22, pnpm 11.19.0.

**Spec:** docs/superpowers/specs/2026-11-01-a-independent-work-design.md

## Global Constraints

- LOCAL 最大 1 GiB，分片 4 MiB；普通上传与远程仍为 50 MiB。
- 私人/课程/未知来源不导出；仅全局管理员；最多 500 条。
- 不伪造 B 的容量账本、操作收据、索引或计划审批已完成。
- 保留原件、权限复核、未知发布屏障、账号切换与草稿保护。
- 不推送远端、不迁移业务库、不部署生产。

## Review Focus

- 来源策略缺失、记录缺失或私人来源经人工修订后的导出。
- 两窗口更新/删除、正文恢复原值后的旧版本请求。
- 远程能力与普通上传不得被 LOCAL 的新上限无意放大。
- 256 块任务加合并文件的清理边界及未知发布的预算保留。
- 无流式保存能力或续传未启用时，不缓冲/回退大文件。

### Task 1: 安全导出已整理反馈

**Files:** AnswerFeedbackService.java, IntelligenceConfiguration.java, ChatProvenanceTest.java, ai-feedback.vue 及相关已有测试。
**Interfaces:** 消费 KnowledgeSourcePolicy.requirePublishable(long feedbackId)；产出同一 ReviewedAnswer 列表。
- [x] 写公开/私人/缺来源/课程混合导出失败回归，运行确认失败。
- [x] 为导出注入来源策略，默认拒绝；只忽略明确私人来源拒绝，其它异常不吞掉。
- [x] 更新界面说明并跑相关测试；提交。

### Task 2: 记忆版本贯通

**Files:** MemoryController.java, ai-memory-panel.mjs, AiMemoryPanel.vue, ai-memory-panel.test.mjs, 新增 MemoryControllerVersionTest.java。
**Interfaces:** Item.version；PUT expectedVersion；DELETE expectedVersion 查询参数。
- [x] 写旧版本删除、ABA 更新、缺版本和前端实际传版本回归，运行确认失败。
- [x] 绑定编辑起点版本，服务端拒绝缺版本更新/删除；409 保留草稿和已有账号隔离。
- [x] 跑前后端相关回归；提交。

### Task 3: LOCAL 大文件传输

**Files:** MountService/DriveOperations/DriveFacade/ResumableUploadService/ResumableUploadController、运行配置、独立上传与私有下载工具及其测试。
**Interfaces:** 本地传输策略与每个目标的能力上限；原 independent-v1 和既有状态不变。
- [x] 写 51 MiB/128 MiB/1 GiB、256 块、动态能力、Blob 兜底限制的失败行为测试。
- [x] 实现有界 LOCAL 配置、按 4 倍峰值预留、下载上限分离、清理边界和动态前端能力；远程保留 50 MiB。
- [x] 实测合成文件摘要、故障与清理；跑相关回归；提交。

### Task 4: 完整验证与 B 方交接

**Files:** backend-independent/contracts/a-side-upgrade-draft.md, docs/A侧独立增量交付-20261101.md, 本计划进度。
**Interfaces:** A 方合同仅为草案，不提前占用 B 类型实现。
- [x] 记录 A02/A03/A04/A05 共同依赖及待联调验收。
- [x] 跑完整 backend verify 和 frontend unit/typecheck/design-contract/build，逐项记录退出码与范围。
- [x] 请求独立全量变更评审，修复重要问题，形成可审查本地提交与补丁。
