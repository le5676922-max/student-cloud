# OSS 与 S3 远程存储接入指南

适用于独立后端当前的阿里云 OSS、S3 兼容存储和 MinIO 接入。配置契约核对日期：2026-10-27。本文说明已实现的使用方式；真实云账号验收情况见文末。

## 1. 运维先配置内容服务

| 环境变量 | 用途与要求 |
| --- | --- |
| `SC_STORAGE_MASTER_KEY` | 凭据加密主密钥，必须是 **32 个随机字节的标准 Base64 编码**。由 Secret 管理设施注入内容服务；不要写进 Git、前端配置、截图或日志。多实例使用同一密钥。 |
| `SC_STORAGE_ALLOWED_ENDPOINTS` | 自建或非默认官方端点的精确 origin 白名单，例如 `https://minio.example.org:9443`。多个值可用逗号、分号或空白分隔；不支持通配符。 |
| `SC_UPLOAD_STAGING_ROOT` | 内容服务可读写、已存在的私有上传暂存目录，使用绝对路径，放在可浏览云盘目录之外。远程上传需先暂存并校验实际长度。 |

例如在受控的 PowerShell 会话生成主密钥并赋给环境变量，以下命令不会将值打印到终端：

```powershell
$remoteStorageKeyBytes = [byte[]]::new(32)
$remoteStorageRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $remoteStorageRng.GetBytes($remoteStorageKeyBytes)
    $env:SC_STORAGE_MASTER_KEY = [Convert]::ToBase64String($remoteStorageKeyBytes)
} finally {
    $remoteStorageRng.Dispose()
    [Array]::Clear($remoteStorageKeyBytes, 0, $remoteStorageKeyBytes.Length)
}
```

生产部署应将生成值保存在受控 Secret 中，由部署环境注入；不要把这个临时进程环境当作持久备份。数据库保存的是 AES-GCM 加密凭据，配置详情不会返回明文 AK、SK 或 Token。备份数据库时应另行安全保管对应主密钥。当前没有自动主密钥轮换迁移流程，直接更换或丢失主密钥会导致已有凭据无法解密。

默认允许的官方地址使用 HTTPS，例如：

- OSS：`https://oss-cn-hangzhou.aliyuncs.com`，填写地域 API 端点，不使用 Bucket 域名或 `-internal` 端点作为默认官方地址。
- AWS S3：`https://s3.ap-southeast-1.amazonaws.com`，地域须与 Bucket 一致。
- MinIO 或其他 S3 服务：由运维加入精确白名单，如 `https://minio.example.org:9443`；表单中的协议、主机和端口必须匹配。

Endpoint 不能包含账号密码、Bucket 路径、查询参数或片段。例如 `https://host/bucket`、控制台地址、网站托管地址、带签名的文件链接均不能作为 API Endpoint。Bucket 和专用前缀分别填写。HTTP 只允许白名单中的回环地址，例如本机协议测试用的 `http://127.0.0.1:9000`；远端服务器或内网 IP 的 HTTP 地址不被接受。不要为连通而关闭 TLS 校验。

## 2. 准备专用 Bucket、前缀与最小权限

保持 Bucket 私有，为本系统分配独立 Bucket 或独立前缀。不要与外部应用共同写入相同对象名，也不要让外部程序修改 `.studentcloud-` 系统保留目录、应用写入的对象元数据或 Bucket 版本状态。

按实际用途授予以下权限，而不是授予整个云账号的管理权限：

| 用途 | AWS S3 权限 | OSS RAM 权限 | 范围 |
| --- | --- | --- | --- |
| 列目录、容量扫描 | `s3:ListBucket` | `oss:ListObjects` | 对应 Bucket，利用前缀条件限制到分配的范围；不能仅授予对象资源。 |
| 读取、HEAD、测试回读 | `s3:GetObject` | `oss:GetObject` | 分配的对象前缀，包括其下的测试探针。 |
| 上传、新建目录标记、写入测试 | `s3:PutObject` | `oss:PutObject` | 分配的对象前缀。 |
| 探针清理 | `s3:DeleteObject` | `oss:DeleteObject` | **仅** `<专用前缀>/.studentcloud-probes/*`，无需开放用户文件删除。 |
| 核验 Bucket 版本状态 | `s3:GetBucketVersioning` | `oss:GetBucketVersioning` | 对应 Bucket。 |

S3 探针返回版本 ID 时，精确版本的 HEAD/删除还需要 `s3:GetObjectVersion`、`s3:DeleteObjectVersion`，对象范围仍只限探针前缀。使用额外 KMS 加密策略时，还需按云厂商要求配置相应密钥权限。各厂商权限对应关系可查 [AWS S3 API 权限表](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-with-s3-policy-actions.html)、[OSS RAM 策略与前缀限制](https://www.alibabacloud.com/help/en/oss/user-guide/ram-policy/)。

**OSS Bucket 的版本控制为 `Enabled` 或 `Suspended` 时禁止写入**，不能用“暂停版本控制”绕过。每次 OSS 条件写入都会重新检查版本状态；状态不明确也会拒绝。只读连接和读取不因此自动获得写入能力。S3 兼容服务必须通过实际的条件写入与探针清理测试，其标称“S3 兼容”不能代替验证。

无需授予列出所有 Bucket、修改 CORS、修改 ACL、删除 Bucket 等权限。当前浏览器通过内容服务代理读写，不调用旧版列桶/CORS 辅助接口，也不向用户返回凭据或预签名存储 URL。

## 3. 在界面添加或编辑

工作空间普通管理员（`TENANT_ADMIN`）可以添加、编辑、启用或停用自己空间的远程存储；全局管理员可以选择已启用且存在普通管理员的工作空间。服务器本地目录（LOCAL）仍需普通管理员提交申请，由全局管理员批准和分配受控目录，连接维护也由全局管理员负责，不能用远程 Endpoint 字段代替本地目录申请。远程源不提供删除入口，退役时停用并保留连接历史。

进入“添加存储源”，选阿里云 OSS、S3 兼容存储或 MinIO，填写：

| 界面字段 / API 参数 | 填写说明 |
| --- | --- |
| 名称 / `name` | 用于界面辨认。 |
| 别名 / `key` | 留空自动分配；手动填写为 3–64 位小写字母、数字、下划线或横线，首位为字母或数字。 |
| API Endpoint / `endPoint` | 不含路径的 API origin，推荐明确填写 `https://`。 |
| Bucket / `bucketName` | 已存在的 Bucket 名称，不含域名、斜杠或目录。 |
| 区域 / `region` | 按提供方填写，例如 OSS 的 `cn-hangzhou`、AWS 的 `ap-southeast-1`；不根据域名猜测。 |
| Access Key ID / `accessKey`、Secret / `secretKey` | 新建必须填写完整的一对凭据。使用专用 RAM/IAM 身份。 |
| 临时 Token / `sessionToken` | 使用临时凭据时填写；普通 AK/SK 可留空。 |
| 专用目录前缀 / `basePath` | 例如 `studentcloud/workspace-20/`；可留空，但空值占用整个 Bucket 前缀范围。保存时去掉前导 `/` 并补齐尾部 `/`。 |
| Path Style / `pathStyleAccess` | 按服务方要求设置。当前表单 OSS 默认关闭，S3/MinIO 默认开启；MinIO 通常使用 Path Style。 |

同一端点和 Bucket 下的存储源前缀不能相同或互相包含。例如 `workspace-20/` 与 `workspace-21/` 可分别使用，`workspace-20/` 与 `workspace-20/docs/` 会冲突。不要通过端点别名把同一实际 Bucket 重复登记来绕过隔离。前缀不能含 `.`、`..`、重复分隔符、反斜杠、控制字符或 `.studentcloud-` 保留目录。

编辑详情中的 AK、SK、Token 故意为空，`credentialsConfigured=true` 表示已有加密凭据。留空保留原值，更换 AK/SK 时同时填写一对；空 Token 也表示保留，当前没有通过空值清除 Token 的入口。不要把空框误认为凭据丢失。

编辑保存携带读取到的 `version`（兼容 `revision`）做并发检查，界面会自动携带。出现“配置已变化”时，重新读取后再编辑，不要强行覆盖。更换 Endpoint、Bucket、Region、Path Style 或前缀前，先单独停用并保存，再修改连接范围。存在正在执行或待核查写入时，连接修改会被阻止。

## 4. 分清检查、测试与保存

“检查填写”仅检查表单，不访问存储服务。“只读测试连接”对所选前缀执行列目录，不写对象；成功不等于已验证下载、上传或删除权限，也不保存配置。

勾选“同时验证写入、回读和清理测试文件”后，测试会：

1. 列目录并核验写入前提。
2. 在专用前缀下的 `.studentcloud-probes/<随机 UUID>` 条件创建随机内容。
3. 对同一名称再次条件创建，确认服务明确拒绝同名覆盖。
4. 回读并比较内容，最后只清理本次测试拥有的探针对象或其精确版本。

S3 使用 `If-None-Match: *`，OSS 使用禁止覆盖条件；不会退化为“先 HEAD 再覆盖 PUT”。清理会验证探针身份、元数据、大小、ETag 和版本；不能确认清理时测试不通过。不要反复点击测试或自行批量删除保留前缀，先核对报错及对应测试对象。

保存已启用的远程配置时，后端会按当前选择再次测试。未勾选写入验证的保存会使该配置保持只读；完成读写验证并保存后才开放远程上传和新建目录。单独一次测试成功不会自动改变已保存配置。编辑页显示的是“已保存配置：只读 / 读写已验证”。

## 5. 空间隔离、成员审批和容量

每个远程源绑定一个工作空间。普通管理员的私有根固定为 `/admins/u<用户ID>`，成员为 `/members/u<用户ID>`，浏览器中均显示为自己的 `/`。实际对象前缀由“专用前缀 + 私有根 + 相对路径”组成；成员不能借路径参数浏览他人的根。全局管理员具有该存储源的管理范围，不应把全局身份用于普通成员访问。

普通管理员可编辑、启停本空间的远程连接；由其他管理员创建或全局分配的连接，其 Bucket、端点与专用前缀只能由创建者或全局管理员更改，防止把已有凭据借用到其他数据位置。更换连接位置前须先停用；保存会核对配置版本与启停修订，旧请求不能覆盖刚执行的停用。

成员提交云盘访问申请后，由本空间普通管理员审批并分配权限和个人容量。管理员只能授予自己可委派的能力，不能把远程未开放的操作授予成员。上传会重新核验账号、工作空间、授权版本、配置版本和容量；容量不足或扫描超过预算会明确失败，不会当作空目录放行。

当前开放 `list`、`read`、`mkdir`、`upload`，并受保存的读写验证状态和用户授权共同限制。文件内容通过登录态代理读取；单文件上传为 **1 字节至 50 MiB**，读取上限 **50 MiB**；新建目录使用零字节目录标记。列表/容量扫描每个远端页最多 1000 项、最多 20 页、累计最多 10000 项并有时间预算，超出会报错，不静默截断。

远程复制、移动、重命名、删除、回收站和分享尚未开放；界面按能力隐藏入口。停用远程源不删除对象。当前也不自动删除远程连接历史，退役时先停用并保留记录，由运维另外安排数据处置。

## 6. 写入结果不确定时如何处理

远程发布开始后发生超时、网络错误或其他无法确认的结果，操作可能进入 `UNCERTAIN`。系统保留持久写入记录和该存储源的写入冻结，其他实例也不能自动接管重放；重新提交同一文件不能解决不确定性。

由全局管理员或该空间普通管理员进入已保存的远程编辑页，展开“待核查写入”，手动读取记录，按操作编号点击“检查写入结果”。界面不自动轮询，检查请求不携带表单凭据，也不重新上传文件。

- 仅当远端对象的 key、大小、`studentcloud-operation-id` 与冻结记录及配置版本精确匹配，检查才返回 `CONFIRMED`，将记录确认为 `COMPLETED` 并解除冻结。
- 对象不存在、大小相同但操作元数据缺失或不同、配置版本不匹配，都返回 `PENDING` 并保留冻结。“当前没看到对象”不等于“肯定没有写入”。
- 检查失败时保留原状态，待连接恢复后再次**检查**。不要删除 SQL guard、修改操作编号、覆盖目标对象或以反复上传代替核查。无法取得匹配证据时需要运维人工调查，目前没有一键强制放行入口。

运维接口为 `GET /admin/storage/{id}/pending-writes` 和 `POST /admin/storage/{id}/pending-writes/{operationId}/inspect`，均要求相应管理权限。列表只包含操作编号、发起用户 ID、该用户目录内路径、状态和毫秒时间戳，不返回完整 Bucket key 或凭据。

## 7. 验证记录与交付边界

SDK 的 loopback 协议测试使用合成凭据，验证签名、条件写入、冲突、分页、版本状态、精确探针清理、关闭流及不确定错误；测试说明和对应记录见 [对象存储模块 README](../backend-independent/cloud-object-storage/README.md)。前端表单、状态隔离、权限入口与手动核查有 Node 回归测试。

**尚未使用真实 OSS/S3 云账号、真实 Bucket 或生产凭据完成接入验收。** 本指南不把本机协议测试等同于真实云服务验收，也不声明已部署。MySQL 集成测试结果应以本次交付的实际运行报告为准，本文不预填未取得的结果。上线前应由运维在获准的专用 Bucket/前缀上，按本指南完成只读、条件写入冲突、回读、探针清理、成员隔离和容量验收，再开放写入。

实现依据：[远程管理表单与保存契约](../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteStorageAdministration.java)、[端点策略](../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteEndpointPolicy.java)、[远程操作与待核查处理](../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteDriveService.java)。
