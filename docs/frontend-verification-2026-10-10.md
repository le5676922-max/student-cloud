# 前端验证记录（2026-10-10）

验证目录：`student-cloud-github/frontend`。使用本机 Node.js 24.16.0 和已安装依赖；测试全部为本地模拟，不调用真实云盘、SMTP 或收费模型接口。

## 实际结果

| 检查 | 结果 |
| --- | --- |
| 全部 Node.js 测试 `node --test tests/*.test.mjs` | 最终 394 项通过，0 失败、0 跳过 |
| 分页 / 续传 / 上传生命周期目标测试 | 42 项通过（分页 7、续传辅助模块 8、真实上传组合式逻辑 27） |
| Vue / TypeScript `vue-tsc --noEmit` | 退出码 0 |
| 全仓 ESLint（不自动修改） | 退出码 0，0 错误、0 警告 |
| Vite 生产构建 `vite build` | 最终源码重新构建退出码 0；3108 模块，耗时 27.99 秒 |

生产构建输出为 `frontend/dist`。构建存在非致命提示：PDF 相关依赖含嵌套 CSS，部分懒加载预览资源大于 500 kB。没有将这些提示隐藏，也未批量重写无关组件。

根线程在追加 Nginx 分片缓冲保护后重跑全部 Node 测试，最终为 394 项（此前一轮 392 项）。新增 2 项是部署契约检查，不改变 Vue/TypeScript 源码及已构建的页面资源。

2026-10-12 再次运行全部 Node 测试，仍为 **394/394 通过、0 失败、0 跳过**。最终后端及真实 MySQL 结果另见 [业务修复与验收报告](业务修复与验收报告-20261012.md)。

## 可复现命令

在 `frontend` 目录执行（以下 `node` 使用 PATH 中的 Node.js）：

```powershell
# 全部运行时与约束测试。
node --test tests/*.test.mjs

# 新增快速分页、断点续传，以及原上传行为回归。
node --test tests/directory-page.test.mjs tests/resumable-upload.test.mjs tests/upload-lifecycle-runtime.test.mjs

# 类型检查。
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit

# 全仓静态检查：不使用 --fix，不批量修改文件。
node node_modules/eslint/bin/eslint.js "src/**/*.{ts,js,jsx,vue}" "presets/**/*.ts" "scripts/*.js" vite.config.ts --max-warnings 0

# 生产静态资源构建。
node node_modules/vite/bin/vite.js build
```

## 本轮重点场景

- S3 快速浏览不提交全局排序 / 全局名称筛选参数；总数明确为未知，隐藏内容导致空页时仍可翻页。
- 续传按 4 MiB 分片计算 SHA-256，真实内容摘要及清单一致；仅跳过服务端确认接收的分片。
- 上传期间跳转页面仍保留原存储源、目录、授权根目录；中文和百分号目录密码通过编码后的请求头传输。
- 取消或切换账号会中止分片请求，迟到响应不更新下一账号的任务，不删除已保留分片，也不继续提交保存。
- 续传能力接口失败会展示错误，不会悄悄回退整文件上传；仅服务端明确 `enabled=false` 才使用原流程。
- 保存响应丢失时标记结果待核查；点击重试仅执行 GET 状态核查，绝不自动重复 POST 完成操作。
- 上传弹窗的结果核查图标有独立操作标签，悬停提示明确不会重复提交，消息可查看完整提示。
- Agent 静默保存也可复用续传；小文件、S3 直传、OneDrive 等原流程保持兼容。

公网断点续传需 HTTPS；本机 localhost / 127.0.0.1 可使用浏览器安全上下文例外。此记录不代表真实对象存储或高并发压测已完成，后端和真实 MySQL / Redis 验证由相应测试记录说明。
