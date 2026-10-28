const screens = {
  login: [
    "登录界面",
    "邮箱或账号 ID 登录；保留几何角色互动与灰白主题。",
    "学生云盘登录界面，几何角色插画与邮箱或账号密码表单",
  ],
  files: [
    "文件管理",
    "查看授权目录，上传、下载、整理和分享学习资料。截图资料均为虚构演示。",
    "学生云盘文件管理界面，展示课程讲义、项目文档和多种格式资料",
  ],
  markdown: [
    "资料预览",
    "在当前浏览器内阅读 Markdown、表格和代码；私有文件不交给第三方查看器。",
    "学生云盘 Markdown 资料预览，显示使用指南和资料范围表格",
  ],
  workspace: [
    "空间管理",
    "普通管理员管理本空间成员、加入申请、云盘与课程。",
    "学生云盘普通管理员的空间管理界面",
  ],
  assistant: [
    "小芽助手",
    "先给出操作方案，再由用户确认；也可以查询资料、管理记忆和反馈答案。",
    "学生云盘小芽助手面板与快捷文件操作",
  ],
  model: [
    "三维模型",
    "OBJ/STL 本地几何预览，支持旋转、缩放与键盘操作，不加载外部材质。",
    "学生云盘三维模型预览界面，带有方向、缩放和还原按钮",
  ],
};
const image = document.querySelector("#screen-image");
const caption = document.querySelector("#screen-caption");
const original = document.querySelector("#original-link");
const failure = document.querySelector("#image-error");
const controls = [...document.querySelectorAll("[data-screen]")];
image.addEventListener("error", () => {
  failure.hidden = false;
});
image.addEventListener("load", () => {
  failure.hidden = true;
});
for (const button of controls) {
  button.addEventListener("click", () => {
    const key = button.dataset.screen;
    const screen = screens[key];
    if (!screen) return;
    for (const control of controls)
      control.setAttribute("aria-pressed", String(control === button));
    failure.hidden = true;
    image.alt = screen[2];
    image.src = `images/${key}.jpg`;
    original.href = `images/${key}.jpg`;
    caption.querySelector("strong").textContent = screen[0];
    caption.querySelector("span").textContent = screen[1];
  });
}
