---
version: alpha
name: Student Cloud
description: 学习资料云盘，保留几何角色登录页的黑白灰与少量紫色，管理界面采用清晰的文件索引密度。
colors:
  primary: "#262629"
  ink: "#242428"
  muted: "#606067"
  frame: "#1c1d20"
  canvas: "#efeff0"
  surface: "#ffffff"
  line: "#dddde1"
  accent: "#6d42f6"
typography:
  sans:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "16px"
    lineHeight: "1.5"
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Consolas, monospace"
rounded:
  DEFAULT: "10px"
  panel: "20px"
spacing:
  frame: "12px"
  sidebar: "244px"
  form-width: "768px"
components:
  button:
    borderRadius: "10px"
  panel:
    borderRadius: "20px"
---

# Student Cloud Design System

## Overview

North Star：像一份可操作的课程资料索引，文件名称、位置、权限和下一步操作优先。用户已选择深灰外框、浅灰面板、白色操作区及几何角色登录界面；不以本轮优化为由重新设计品牌。

- 受众：中国大学课程资料管理者、空间管理员和成员；中文简体，桌面为主，支持窄屏。
- Register：登录页保留角色动效；已登录界面是任务型产品，不使用营销大标题、夸张渐变或大型装饰数字。
- 记忆点：文件索引式紧凑卡片与细紫色选中/焦点标记。表达集中在资料内容和已有小芽形象，周围保持克制。
- 反例：不要把所有管理页面改成登录页插画，不增加无意义 AI 光效，不用长时间过渡掩盖请求等待。
- 权威运行时 token 为 `frontend/src/styles/main.css`；本文件镜像其已接受值，不反向生成新配色。`admin.scss` 与 Element Plus 读取这些变量。新增组件用语义变量，不复制另一套 palette。
- 本轮迁移存储列表/编辑和总览反馈；历史页面的既有偏差作为迁移事项记录，不把旧事故写成新规范。

## Colors

深灰 primary 用于主要保存/添加操作；surface/canvas/frame 区分内容、工作区、框架。accent 仅用于焦点、选中和有限的交互提示。成功/警告/错误沿用 Element Plus 的语义 token，状态必须同时包含文字，不能只靠绿/红区分。

| 文档路径 | 运行时 canonical owner | 使用者 |
| --- | --- | --- |
| colors.primary | `--cloud-primary` | 按钮、导航 |
| colors.ink / muted | `--cloud-ink` / `--cloud-muted` | 标题、辅助说明 |
| colors.frame / canvas / surface / line | 同名 `--cloud-*` | shell、卡片、表单 |
| colors.accent | `--cloud-accent` | 键盘焦点、当前步骤 |
| rounded.DEFAULT / panel | `--el-border-radius-base` / `--cloud-radius` | 输入控件、面板 |
| typography.sans | `main.css` 的 html/body/#app | 中文正文与控件 |

滚动条全局基线同样由 `main.css` 维护：标准 `scrollbar-color/width` 与 WebKit fallback；高对比度使用系统色，滚动条不隐藏。

## Typography

使用现有中文系统字体，无外部字体下载或晚到字体切换。正文 16px 为应用基础，管理辅助说明和控件按现有 13–14px 密度，标题 18–24px；名称比图标和元数据更突出。UUID、账号和数字可用等宽/等宽数字，长路径换行或有 title，不挤走操作按钮。界面文案用“添加云盘 / 保存设置 / 测试连接 / 检查写入结果”等实际动作，不承诺未知连通性。

## Layout

沿用 12px 深灰外框、244px 桌面导航、白色内容面板；移动导航使用现有 Headless UI dialog。已有 `.admin-main-content` 是管理页滚动容器，表单自然高度；不新增整页固定高度或双层纵向滚动。

存储卡片采用图标+名称+状态+操作的紧凑层级，类型和私有空间信息作为辅助。表单最大阅读/输入宽度跟随既有 768px；连接信息、使用范围和保存反馈有明确分组，避免读完整屏说明才能找到操作。窄屏按钮换行，页脚不覆盖字段。空/错/刷新状态保留合理几何，不用占满页面的遮罩阻断导航。

## Elevation & Depth

层级主要来自底色和细边框；悬浮或焦点可有轻微阴影。弹窗使用现有库的层级与焦点管理，不为每张静态卡片叠加重阴影、模糊或玻璃效果。

## Shapes

既有圆角面板和控件保持一致。小型状态标签、筛选可用胶囊形，但状态文字本身不是隐形开关。图标尺寸固定，不让 SVG 继承可增长容器而撑满页面。

## Components

- Element Plus 负责按钮、表单、Select、消息和确认对话框；不用重复实现 ARIA listbox 或原生 confirm。
- 点击目标使用 button/link，提供 hover、focus-visible、busy、disabled。重要移动操作尽量 44px；禁用有文字原因。
- 主要操作保持尺寸稳定；异步结果以原位置 inline status/alert 展示，toast 仅作确认。
- 过渡只用于按钮反馈、折叠和轻微进入，约 120–180ms。尊重 `prefers-reduced-motion`，不延迟路由切换，不增加循环动画。
- 控件、CRUD、异步及权限规则见根目录 `UX-CONTRACT.md`，token 漂移由项目测试验证。
- 私有文件预览沿用 `ZDialog` 与当前灰白主题；`PrivateFilePreview.vue` 是统一入口。图片、文档、媒体和模型工具在同一弹窗内显示，窄屏换行，保留关闭与键盘操作。不添加外部文档查看器、自动播放或模型自动旋转。

## Do's and Don'ts

保留用户选择的登录主题、小芽与正常页面结构；优先修复误导状态、无效接口、丢失输入和重复提交。不要为了“看起来高级”重排所有路由、增加技术术语或把不可用数据填成 0。浏览器视觉与交互结果必须单独验证，静态审计不等于验收。
