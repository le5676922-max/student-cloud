# 独立后端 API 迁移清单

此文件由 `../tools/extract_legacy_contracts.py` 生成。仅记录行为对照，不导入、编译或执行旧代码。

基线提交：`fbb1610efa4f9d54b5a375e559b876e9392ad3da`。当前工作区源码静态扫描得到 231 条展开路由记录、166 条前端请求记录。

所有功能初始均为 **pending**，不表示已经实现、已通过测试或形成完整 OpenAPI。动态参数、包装器和注解推断须在实际实现时逐项确认。

| 旧接口业务模块 | 展开路由记录 | 独立实现状态 |
| --- | ---: | --- |
| admin | 13 | pending |
| ai | 33 | pending |
| config | 9 | pending |
| core | 2 | pending |
| course | 20 | pending |
| filter | 2 | pending |
| install | 2 | pending |
| learning | 10 | pending |
| link | 9 | pending |
| log | 5 | pending |
| onlyoffice | 2 | pending |
| password | 2 | pending |
| permission | 2 | pending |
| readme | 2 | pending |
| share | 11 | pending |
| sso | 7 | pending |
| storage | 51 | pending |
| support | 8 | pending |
| tenant | 18 | pending |
| user | 23 | pending |

## 接口逐项迁移

| 方法 | 路径 | 旧处理函数（行为证据） | 状态 |
| --- | --- | --- | --- |
| ANY | `/` | `FrontIndexController.redirect` | pending |
| POST | `/115/qrCodeStatus` | `Open115HelperController.getQrCodeStatus` | pending |
| GET | `/115/qrcode` | `Open115HelperController.generateQrCode` | pending |
| GET | `/admin/2fa/setup` | `AdminTwoFAController.setupDevice` | pending |
| POST | `/admin/2fa/verify` | `AdminTwoFAController.deviceVerify` | pending |
| GET | `/admin/ai-feedback` | `AiFeedbackAdminController.list` | pending |
| GET | `/admin/ai-feedback/export` | `AiFeedbackAdminController.export` | pending |
| GET | `/admin/ai-feedback/{id}` | `AiFeedbackAdminController.get` | pending |
| PUT | `/admin/ai-feedback/{id}` | `AiFeedbackAdminController.review` | pending |
| GET | `/admin/ai-knowledge/feedback/{id}` | `AiKnowledgeAdminController.state` | pending |
| GET | `/admin/ai-knowledge/history/{id}` | `AiKnowledgeAdminController.history` | pending |
| POST | `/admin/ai-knowledge/publish` | `AiKnowledgeAdminController.publish` | pending |
| POST | `/admin/ai-knowledge/refine` | `AiKnowledgeAdminController.refine` | pending |
| POST | `/admin/ai-knowledge/withdraw` | `AiKnowledgeAdminController.withdraw` | pending |
| GET | `/admin/business-metrics` | `GlobalBusinessMetricsController.get` | pending |
| GET | `/admin/clientIp` | `IpHelperController.clientIp` | pending |
| GET | `/admin/config` | `SettingController.getConfig` | pending |
| PUT | `/admin/config/access` | `SettingController.updateSecuritySetting` | pending |
| PUT | `/admin/config/link` | `SettingController.updateLinkSetting` | pending |
| PUT | `/admin/config/security` | `SettingController.updateSecuritySetting` | pending |
| PUT | `/admin/config/site` | `SettingController.updateSiteSetting` | pending |
| PUT | `/admin/config/view` | `SettingController.updateViewSetting` | pending |
| POST | `/admin/download/log/delete/batch` | `DownloadLogManagerController.batchDelete` | pending |
| POST | `/admin/download/log/delete/batch/query` | `DownloadLogManagerController.batchDeleteBySearchParams` | pending |
| DELETE | `/admin/download/log/delete/{id}` | `DownloadLogManagerController.deleteById` | pending |
| GET | `/admin/download/log/list` | `DownloadLogManagerController.list` | pending |
| GET | `/admin/headers` | `IpHelperController.headers` | pending |
| POST | `/admin/link/delete/batch` | `ShortLinkManagerController.batchDelete` | pending |
| DELETE | `/admin/link/delete/{id}` | `ShortLinkManagerController.deleteById` | pending |
| DELETE | `/admin/link/deleteExpireLink` | `ShortLinkManagerController.deleteExpireLink` | pending |
| GET | `/admin/link/export` | `ShortLinkManagerController.exportExcel` | pending |
| GET | `/admin/link/limit/info` | `ShortLinkManagerController.getLinkLimitInfo` | pending |
| GET | `/admin/link/list` | `ShortLinkManagerController.list` | pending |
| GET | `/admin/log/download` | `LogController.downloadLog` | pending |
| GET | `/admin/login/log/list` | `LoginLogController.list` | pending |
| GET | `/admin/overview` | `GlobalOverviewController.overview` | pending |
| GET | `/admin/overview/workspaces` | `GlobalOverviewController.workspaces` | pending |
| POST | `/admin/overview/workspaces/{tenantId}/storage` | `WorkspaceStorageProvisioningController.provision` | pending |
| GET | `/admin/overview/workspaces/{tenantId}/users` | `GlobalOverviewController.workspaceMembers` | pending |
| GET | `/admin/permission/list` | `PermissionController.list` | pending |
| POST | `/admin/rule-test` | `RuleMatcherTestController.testRule` | pending |
| GET | `/admin/serverAddress` | `IpHelperController.serverAddress` | pending |
| DELETE | `/admin/share/expired` | `ShareFileManagerController.deleteExpiredShares` | pending |
| GET | `/admin/share/list` | `ShareFileManagerController.getShareList` | pending |
| POST | `/admin/sso/provider` | `SsoManagerController.saveOrUpdateProvider` | pending |
| GET | `/admin/sso/provider/checkDuplicate` | `SsoManagerController.checkDuplicate` | pending |
| DELETE | `/admin/sso/provider/{provider}` | `SsoManagerController.deleteProvider` | pending |
| GET | `/admin/sso/providers` | `SsoManagerController.list` | pending |
| POST | `/admin/storage` | `StorageSourceController.saveStorageItem` | pending |
| GET | `/admin/storage-local-requests` | `LocalStorageApprovalController.list` | pending |
| POST | `/admin/storage-local-requests/{id}/review` | `LocalStorageApprovalController.review` | pending |
| GET | `/admin/storage-params` | `StorageMetaDataController.getFormByStorageType` | pending |
| POST | `/admin/storage/copy` | `StorageSourceController.copyStorage` | pending |
| GET | `/admin/storage/exist/key` | `StorageSourceController.existKey` | pending |
| POST | `/admin/storage/sort` | `StorageSourceController.updateStorageSort` | pending |
| POST | `/admin/storage/test-connection` | `StorageSourceController.testConnection` | pending |
| DELETE | `/admin/storage/{storageId}` | `StorageSourceController.deleteStorageItem` | pending |
| GET | `/admin/storage/{storageId}` | `StorageSourceController.storageItem` | pending |
| POST | `/admin/storage/{storageId}/compatibility_readme/{status}` | `StorageSourceController.changeCompatibilityReadme` | pending |
| POST | `/admin/storage/{storageId}/cors/apply` | `StorageSourceController.applyCors` | pending |
| POST | `/admin/storage/{storageId}/disable` | `StorageSourceController.disable` | pending |
| POST | `/admin/storage/{storageId}/enable` | `StorageSourceController.enable` | pending |
| GET | `/admin/storage/{storageId}/filters` | `StorageSourceFilterController.getFilters` | pending |
| POST | `/admin/storage/{storageId}/filters` | `StorageSourceFilterController.saveFilters` | pending |
| GET | `/admin/storage/{storageId}/password` | `StorageSourcePasswordController.getPasswordList` | pending |
| POST | `/admin/storage/{storageId}/password` | `StorageSourcePasswordController.savePasswordList` | pending |
| GET | `/admin/storage/{storageId}/permission` | `StorageSourcePermissionController.getPermissionList` | pending |
| GET | `/admin/storage/{storageId}/readme` | `StorageSourceReadmeController.getReadmeList` | pending |
| POST | `/admin/storage/{storageId}/readme` | `StorageSourceReadmeController.saveReadmeList` | pending |
| GET | `/admin/storages` | `StorageSourceController.storageList` | pending |
| GET | `/admin/support-storage` | `StorageMetaDataController.supportStorage` | pending |
| POST | `/admin/tenant` | `TenantController.save` | pending |
| GET | `/admin/tenants` | `TenantController.list` | pending |
| GET | `/admin/user/checkDuplicate` | `UserManagerController.checkDuplicate` | pending |
| POST | `/admin/user/copy` | `UserManagerController.copyStorage` | pending |
| DELETE | `/admin/user/delete/{id}` | `UserManagerController.delete` | pending |
| POST | `/admin/user/disable/{id}` | `UserManagerController.disable` | pending |
| POST | `/admin/user/enable/{id}` | `UserManagerController.enable` | pending |
| GET | `/admin/user/list` | `UserManagerController.list` | pending |
| POST | `/admin/user/saveOrUpdate` | `UserManagerController.saveOrUpdate` | pending |
| GET | `/admin/user/{id}` | `UserManagerController.getUser` | pending |
| POST | `/api/ai/chat` | `AiAgentController.chat` | pending |
| POST | `/api/ai/chat/cancel/{id}` | `AiAgentController.cancel` | pending |
| POST | `/api/ai/chat/files` | `AiAgentController.chatWithFiles` | pending |
| POST | `/api/ai/chat/files/stream/{id}` | `AiAgentController.streamWithFiles` | pending |
| POST | `/api/ai/chat/stream/{id}` | `AiAgentController.stream` | pending |
| POST | `/api/ai/feedback` | `AiFeedbackController.submit` | pending |
| GET | `/api/ai/knowledge/search` | `AiKnowledgeController.search` | pending |
| DELETE | `/api/ai/memories` | `AiMemoryController.clear` | pending |
| GET | `/api/ai/memories` | `AiMemoryController.state` | pending |
| POST | `/api/ai/memories` | `AiMemoryController.save` | pending |
| PUT | `/api/ai/memories/settings` | `AiMemoryController.settings` | pending |
| DELETE | `/api/ai/memories/{id}` | `AiMemoryController.forget` | pending |
| PUT | `/api/ai/memories/{id}` | `AiMemoryController.update` | pending |
| GET | `/api/ai/schedules` | `AgentScheduleController.list` | pending |
| POST | `/api/ai/schedules` | `AgentScheduleController.create` | pending |
| POST | `/api/ai/schedules/{id}/enabled` | `AgentScheduleController.setEnabled` | pending |
| POST | `/api/ai/schedules/{id}/preview` | `AgentScheduleController.previewNow` | pending |
| GET | `/api/ai/tasks` | `AgentTaskController.list` | pending |
| POST | `/api/ai/tasks` | `AgentTaskController.preview` | pending |
| GET | `/api/ai/tasks/duplicates` | `AgentTaskController.duplicates` | pending |
| GET | `/api/ai/tasks/{id}` | `AgentTaskController.get` | pending |
| POST | `/api/ai/tasks/{id}/cancel` | `AgentTaskController.cancel` | pending |
| POST | `/api/ai/tasks/{id}/confirm` | `AgentTaskController.confirm` | pending |
| POST | `/api/ai/tasks/{id}/retry` | `AgentTaskController.retry` | pending |
| GET | `/api/courses` | `SharedCourseController.list` | pending |
| POST | `/api/courses` | `SharedCourseController.create` | pending |
| POST | `/api/courses/invitation-preview` | `SharedCourseController.previewInvitation` | pending |
| POST | `/api/courses/join` | `SharedCourseController.join` | pending |
| GET | `/api/courses/{id}` | `SharedCourseController.get` | pending |
| PUT | `/api/courses/{id}` | `SharedCourseController.edit` | pending |
| POST | `/api/courses/{id}/code` | `SharedCourseController.code` | pending |
| GET | `/api/courses/{id}/incomplete-uploads` | `SharedCourseController.incomplete` | pending |
| GET | `/api/courses/{id}/invitations` | `SharedCourseController.invitations` | pending |
| POST | `/api/courses/{id}/invitations` | `SharedCourseController.createInvitation` | pending |
| DELETE | `/api/courses/{id}/invitations/{invitationId}` | `SharedCourseController.revokeInvitation` | pending |
| POST | `/api/courses/{id}/leave` | `SharedCourseController.leave` | pending |
| GET | `/api/courses/{id}/materials` | `SharedCourseController.materials` | pending |
| POST | `/api/courses/{id}/materials` | `SharedCourseController.upload` | pending |
| DELETE | `/api/courses/{id}/materials/{materialId}` | `SharedCourseController.delete` | pending |
| GET | `/api/courses/{id}/materials/{materialId}/download` | `SharedCourseController.download` | pending |
| GET | `/api/courses/{id}/members` | `SharedCourseController.members` | pending |
| DELETE | `/api/courses/{id}/members/{memberId}` | `SharedCourseController.remove` | pending |
| POST | `/api/courses/{id}/members/{memberId}/upload` | `SharedCourseController.review` | pending |
| POST | `/api/courses/{id}/upload-request` | `SharedCourseController.requestUpload` | pending |
| POST | `/api/file/operator/delete/batch` | `FileOperatorController.deleteFile` | pending |
| POST | `/api/file/operator/mkdir` | `FileOperatorController.mkdir` | pending |
| POST | `/api/file/operator/rename/file` | `FileOperatorController.rename` | pending |
| POST | `/api/file/operator/rename/folder` | `FileOperatorController.renameFolder` | pending |
| POST | `/api/file/operator/upload/file` | `FileOperatorController.getUploadFileUrl` | pending |
| POST | `/api/file/operator/{action:move&#124;copy}/{type:file&#124;folder}` | `FileOperatorController.moveFile` | pending |
| POST | `/api/install` | `InstallController.install` | pending |
| GET | `/api/install/status` | `InstallController.isInstall` | pending |
| GET | `/api/learning/courses` | `LearningController.courses` | pending |
| POST | `/api/learning/courses` | `LearningController.create` | pending |
| DELETE | `/api/learning/courses/{id}` | `LearningController.deleteCourse` | pending |
| PUT | `/api/learning/courses/{id}` | `LearningController.update` | pending |
| GET | `/api/learning/materials` | `LearningController.materials` | pending |
| POST | `/api/learning/materials` | `LearningController.add` | pending |
| DELETE | `/api/learning/materials/{id}` | `LearningController.deleteMaterial` | pending |
| POST | `/api/learning/materials/{id}/refresh` | `LearningController.refresh` | pending |
| POST | `/api/learning/materials/{id}/relink` | `LearningController.relink` | pending |
| GET | `/api/learning/search` | `LearningController.search` | pending |
| POST | `/api/path-link/batch/generate` | `DirectLinkController.generatorShortLink` | pending |
| POST | `/api/share/create` | `ShareLinkController.createShareLink` | pending |
| GET | `/api/share/download/{shareKey}` | `ShareLinkController.downloadShareFile` | pending |
| DELETE | `/api/share/expired` | `ShareLinkController.deleteExpiredShares` | pending |
| POST | `/api/share/files` | `ShareLinkController.getShareFileList` | pending |
| GET | `/api/share/info/{shareKey}` | `ShareLinkController.getShareInfo` | pending |
| GET | `/api/share/list` | `ShareLinkController.getUserShareList` | pending |
| POST | `/api/share/onlyOffice/config/token` | `ShareLinkController.getShareOnlyOfficeConfigToken` | pending |
| POST | `/api/share/verify` | `ShareLinkController.verifyPassword` | pending |
| DELETE | `/api/share/{shareKey}` | `ShareLinkController.deleteShare` | pending |
| POST | `/api/short-link/batch/generate` | `ShortLinkController.generatorShortLink` | pending |
| GET | `/api/site/config/global` | `SiteController.globalConfig` | pending |
| POST | `/api/site/config/storage` | `SiteController.storageList` | pending |
| GET | `/api/site/config/userRootPath/{storageKey}` | `SiteController.getUserRootPath` | pending |
| GET | `/api/sso/list` | `SsoAPIController.list` | pending |
| POST | `/api/storage/file/item` | `FileController.fileItem` | pending |
| POST | `/api/storage/files` | `FileController.list` | pending |
| GET | `/api/storage/list` | `FileController.storageList` | pending |
| GET | `/api/support/conversations` | `SupportController.list` | pending |
| POST | `/api/support/conversations` | `SupportController.open` | pending |
| GET | `/api/support/conversations/{id}` | `SupportController.get` | pending |
| POST | `/api/support/conversations/{id}/accept` | `SupportController.accept` | pending |
| POST | `/api/support/conversations/{id}/close` | `SupportController.close` | pending |
| GET | `/api/support/conversations/{id}/messages` | `SupportController.messages` | pending |
| POST | `/api/support/conversations/{id}/messages` | `SupportController.send` | pending |
| POST | `/api/support/socket-ticket` | `SupportSocketTicketController.ticket` | pending |
| POST | `/api/uploads/resumable/capabilities` | `LocalResumableUploadController.capabilities` | pending |
| POST | `/api/uploads/resumable/init` | `LocalResumableUploadController.initialize` | pending |
| DELETE | `/api/uploads/resumable/{id}` | `LocalResumableUploadController.delete` | pending |
| GET | `/api/uploads/resumable/{id}` | `LocalResumableUploadController.status` | pending |
| PUT | `/api/uploads/resumable/{id}/chunks/{index}` | `LocalResumableUploadController.chunk` | pending |
| POST | `/api/uploads/resumable/{id}/complete` | `LocalResumableUploadController.complete` | pending |
| GET | `/api/workspace-admin/invitations` | `WorkspaceAdminController.invitations` | pending |
| POST | `/api/workspace-admin/invitations` | `WorkspaceAdminController.createInvitation` | pending |
| DELETE | `/api/workspace-admin/invitations/{id}` | `WorkspaceAdminController.revokeInvitation` | pending |
| GET | `/api/workspace-admin/join-requests` | `WorkspaceAdminController.joinRequests` | pending |
| POST | `/api/workspace-admin/join-requests/{id}/review` | `WorkspaceAdminController.review` | pending |
| GET | `/api/workspace-admin/members` | `WorkspaceAdminController.members` | pending |
| POST | `/api/workspace-admin/members` | `WorkspaceAdminController.create` | pending |
| POST | `/api/workspace-admin/members/{id}/enabled` | `WorkspaceAdminController.setEnabled` | pending |
| POST | `/api/workspace-admin/members/{id}/password` | `WorkspaceAdminController.resetPassword` | pending |
| GET | `/api/workspace-admin/members/{id}/permissions` | `WorkspaceAdminController.permissions` | pending |
| PUT | `/api/workspace-admin/members/{id}/permissions` | `WorkspaceAdminController.permissions` | pending |
| GET | `/api/workspace-admin/summary` | `WorkspaceAdminController.summary` | pending |
| GET | `/api/workspace-cloud` | `WorkspaceCloudAccessController.available` | pending |
| GET | `/api/workspace-cloud/requests` | `WorkspaceCloudAccessController.requests` | pending |
| POST | `/api/workspace-cloud/requests/{id}/review` | `WorkspaceCloudAccessController.review` | pending |
| POST | `/api/workspace-cloud/{id}/apply` | `WorkspaceCloudAccessController.apply` | pending |
| GET | `/api/workspace-storage/local-requests` | `LocalStorageApprovalController.list` | pending |
| POST | `/api/workspace-storage/local-requests` | `LocalStorageApprovalController.submit` | pending |
| PUT | `/file/upload/{storageKey}/**` | `ProxyUploadController.upload` | pending |
| GET | `/gd/authorize` | `GoogleDriveCallbackController.authorize` | pending |
| GET | `/gd/callback` | `GoogleDriveCallbackController.googleDriveCallback` | pending |
| POST | `/gd/drives` | `GoogleDriveHelperController.getDrives` | pending |
| GET | `/onedirve/authorize` | `OneDriveCallbackController.authorize` | pending |
| GET | `/onedirve/callback` | `OneDriveCallbackController.oneDriveCallback` | pending |
| GET | `/onedirve/china-authorize` | `OneDriveCallbackController.authorizeChina` | pending |
| GET | `/onedirve/china-callback` | `OneDriveCallbackController.oneDriveChinaCallback` | pending |
| GET | `/onedrive/authorize` | `OneDriveCallbackController.authorize` | pending |
| GET | `/onedrive/callback` | `OneDriveCallbackController.oneDriveCallback` | pending |
| GET | `/onedrive/china-authorize` | `OneDriveCallbackController.authorizeChina` | pending |
| GET | `/onedrive/china-callback` | `OneDriveCallbackController.oneDriveChinaCallback` | pending |
| ANY | `/onlyOffice/callback` | `OnlyOfficeController.callBack` | pending |
| POST | `/onlyOffice/config/token` | `OnlyOfficeController.getPreviewFileJSONInfo` | pending |
| GET | `/open115/url/{storageId}/{pickCode}` | `Open115UrlController.redirectTo115DownloadUrl` | pending |
| GET | `/pd/{storageKey}/**` | `ProxyDownloadController.downAttachment` | pending |
| GET | `/s/{key}` | `ShortLinkController.parseShortKey` | pending |
| POST | `/s3/getBuckets` | `S3HelperController.getBucketNames` | pending |
| POST | `/s3/getCorsConfig` | `S3HelperController.getCorsConfig` | pending |
| POST | `/sharepoint/getDomainPrefix` | `SharePointHelperController.getDomainPrefix` | pending |
| POST | `/sharepoint/getSiteId` | `SharePointHelperController.getSiteId` | pending |
| POST | `/sharepoint/getSiteLists` | `SharePointHelperController.getSites` | pending |
| POST | `/sharepoint/getSites` | `SharePointHelperController.getSites` | pending |
| GET | `/sso/{provider}/login` | `SsoController.login` | pending |
| GET | `/sso/{provider}/login/callback` | `SsoController.callback` | pending |
| GET | `/user/login/captcha` | `UserController.captcha` | pending |
| GET | `/user/login/check` | `UserController.checkLogin` | pending |
| GET | `/user/login/entry/validate` | `UserController.validateLoginEntry` | pending |
| GET | `/user/login/verify-mode` | `UserController.loginVerifyMode` | pending |
| POST | `/user/logout` | `UserController.logout` | pending |
| POST | `/user/password-reset` | `PasswordResetController.reset` | pending |
| GET | `/user/password-reset/config` | `PasswordResetController.config` | pending |
| POST | `/user/password-reset/email-code` | `PasswordResetController.emailCode` | pending |
| POST | `/user/register` | `PublicRegistrationController.register` | pending |
| GET | `/user/registration/config` | `PublicRegistrationController.config` | pending |
| POST | `/user/registration/email-code` | `PublicRegistrationController.emailCode` | pending |
| PUT | `/user/resetAdminPassword` | `UserController.resetPwd` | pending |
| POST | `/user/updatePwd` | `UserController.updatePwd` | pending |

## 包装与边界

- JSON 成功码是字符串 `0`，常规字段为 `code/msg/data/dataCount/traceId`；原始下载和 SSE 不套统一响应。
- `axios-request` 影响错误响应/浏览器重定向，认证头和 cookie 名称须兼容；错误 HTTP 状态与业务码分别核对。
- 通用请求包装器、动态 URL、SSE 事件、WebSocket 票据及完整 DTO 结构不能仅凭此静态清单保证兼容。
- `frontend-baseline.json` 是跟踪前端文件的原始字节哈希；`.env`、私密配置及运行产物不读取。
- 独立后端拥有新模型与迁移起点，此清单不要求沿用旧数据库表或旧实现。
- 生成器可重复运行，但会重置本生成清单中的状态；正式实施进度应另行记录，不能将 pending 自动改成通过。
