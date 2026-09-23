# 通理工学生个人云盘（后端）

通理工学生个人云盘是面向学生个人学习和文件管理场景的云存储服务。本目录包含 Spring Boot 后端，前端项目位于同级目录。

## 主要能力

- 统一管理本地存储、对象存储、WebDAV、FTP、SFTP 等存储源
- 文件浏览、上传、下载、移动、复制、删除、直链与分享
- 图片、音视频、文本、PDF、Office 与 3D 文件在线预览
- 多用户、工作空间、角色权限和存储源授权
- 管理后台、登录日志、下载日志及站点设置

## 运行环境

- Java 21
- Maven 3.9+
- MySQL 8
- 可选 Redis

## 本地启动

1. 按需调整 `src/main/resources/application.properties` 中的数据库地址和用户名，并在启动终端设置 `STUDENT_CLOUD_DB_PASSWORD` 环境变量；不要把密码写进源码。
2. 创建配置所指向的 MySQL 数据库。
3. 执行 `mvn spring-boot:run`。
4. 后端默认监听 `http://127.0.0.1:8080`。

数据库结构由 Flyway 在启动时自动迁移。已经发布并执行过的历史迁移文件不应直接修改。

## 构建

```bash
mvn clean package
```

构建产物默认输出到 `target/tongligong-student-cloud-4.5.0.jar`。

## 开源说明

本项目在原开源在线网盘项目基础上进行学习和个性化改造，遵循仓库中的 `LICENSE`，并保留依法需要的版权与许可信息。
