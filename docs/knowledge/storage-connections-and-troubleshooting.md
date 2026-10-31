# 存储源接入与连接失败排查

适用版本：2026-10-30 当前独立后端 `backend-independent`。本文说明代码已实现的行为；真实云账号、云端策略和当前部署状态仍须由管理员验收。示例都是占位内容，不是可用账号。本轮仅核对源码与官方文档，没有向任何云存储发送测试请求。

## 先选对存储方式和管理角色

| 方式 | 当前接入方式 | 文件能力与限制 |
| --- | --- | --- |
| LOCAL 服务器本地存储 | 全局管理员配置服务器受控目录；空间管理员申请后由全局管理员审批分配 | 本地目录及文件操作按授权开放；路径必须是后端实际能访问的目录 |
| 阿里云 OSS | 选择“阿里云 OSS”，使用 OSS API、桶、区域及访问凭据 | 列目录、读取、建目录、上传；写入前须完成验证 |
| S3 兼容对象存储 | 选择“S3 兼容对象存储”，包括受支持的 AWS S3 常规 API 端点及经允许运维端点 | 同上；“S3 兼容”不保证提供方实现所有条件写入与条件清理语义 |
| MinIO | 选择“MinIO / S3”，通过 S3 协议接入 | 同上；使用 S3 API 地址，不能填 Console 网页地址 |
| WebDAV、FTP、SFTP 及其他网盘 | 当前独立后端未开放这些驱动 | 图标、地址识别提示或旧页面代码不代表已经支持；不能靠改协议名接入 |

远程源当前没有复制、移动、重命名、删除、回收站、共享链接及本地式分片续传能力，相关接口会拒绝不支持的操作。远程上传单文件为 **1 字节至 50 MiB**，读取上限为 50 MiB。建目录通过零字节目录对象实现，不受“上传文件至少 1 字节”的限制。当前本地与远程管理均未开放存储缓存和自动刷新缓存。

- **普通成员 USER**：加入空间后申请具体云盘，由空间管理员分配个人目录、容量和操作权限；不配置连接或查看密钥。
- **空间管理员 TENANT_ADMIN**：管理本空间远程连接和成员授权；服务器本地目录需走审批，不自行选择服务器物理路径。别人为本空间创建的远程连接，其位置只能由创建者或全局管理员更改。
- **全局管理员 ADMIN**：配置受控本地目录、审批本地云盘、管理空间归属及远程连接。新建远程源必须选择已启用的工作空间，并且该空间已有普通管理员（即空间管理员）。管理连接不等于普通成员获得目录读取权。

依据：[管理入口](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/http/StorageAdministrationController.java)、[远程支持类型与授权](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteStorageAdministration.java)、[远程文件能力](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteDriveService.java)、[未实现能力的明确拒绝](../../backend-independent/cloud-drive-application/src/main/java/io/studentcloud/drive/application/DriveOperations.java)。

## 通用接入顺序

1. 确认当前账号、目标工作空间、存储归属以及后端到存储的网络可达性。远程桶须已存在，网页不会代建桶。
2. 在管理页“存储源”进入“添加存储源”（`/admin/storage-edit`），选择实际开放的类型。已存在连接从存储源列表编辑。
3. 填写名称、可选别名和本类型字段。全局管理员新建时核对“所属工作空间”；空间管理员只能使用本空间。智能识别和“检查填写”只检查格式，没有连接服务，也没有保存。
4. 先使用“只读测试连接”。成功后如需上传，勾选“同时验证写入、回读和清理测试文件”，执行“测试读写并清理”。只读探测、写入探测的准确含义见下文。
5. 核对明确成功且清理成功，再点击“保存设置”。远程启用保存会再次探测，测试成功不能代替保存。本地保存也会重新执行实际读写检查。
6. 回到列表确认连接存在、归属正确并已启用。远程编辑页显示“读写已验证”才说明保存了可写状态；显示“只读；尚未验证写入权限”时不会开放上传。
7. 在空间人员/云盘申请管理中向成员分配目录、容量、查看目录、读取、建目录及上传等所需权限。成员重新加载后，在自己的目录用一个无敏感信息且名称唯一的小文件验证使用效果。

保存响应超时或页面离开后，先返回列表刷新核实结果；离开页面不会撤销已发出的请求。不要连续创建同一连接。存储别名留空可由系统分配；自填须为 3～64 字符，以小写字母或数字开头，其余为小写字母、数字、`-` 或 `_`，且不能重复。名称为 1～120 字符。

## LOCAL：服务器目录与审批

### 空间管理员申请

在存储源页展开“申请服务器本地云盘”，填写 1～500 字符的用途并提交。出现“待审核”表示申请已登记；刷新申请查看结果。同一申请人已有待审申请时会返回该申请，不会重复分配目录。

全局管理员在“服务器本地云盘申请审批”中核对工作空间和用途，选择“批准并开通”或填写说明后拒绝。批准时系统在受控根下分配独立目录并给申请管理员个人目录授权；申请人不能在申请表中指定服务器路径。批准后还需为普通成员处理具体云盘使用申请和容量授权。“已拒绝”应先看审核说明，修改用途后再申请。

### 全局管理员直接配置

运维先配置内容服务的 `SC_MANAGED_STORAGE_ROOT`，使用绝对私有目录，并准备其下已经存在的独立子目录。表单唯一的本地连接字段是“受控目录” `filePath`。

| 场景 | 示例占位路径 | 应核对的内容 |
| --- | --- | --- |
| Linux 后端 | 受控根 `/srv/studentcloud/managed`；表单 `/srv/studentcloud/managed/workspace-demo` | 子目录已存在，后端运行用户有读取、写入及删除临时文件权限 |
| Windows 后端 | 受控根 `D:\StudentCloud\managed`；表单 `D:\StudentCloud\managed\workspace-demo` | 使用服务所在 Windows 的路径和运行账号权限 |
| 容器后端 | 容器根 `/data/managed`；表单 `/data/managed/workspace-demo` | 宿主机目录已挂载进该容器；表单使用容器内路径 |

浏览器所在电脑的 `C:\Users\...` 不会自动成为服务器目录。不能直接选择受控根本身、根外目录、符号链接或与现有本地存储相等/互相包含的目录。以内容服务的运行身份检查；管理员在宿主机有权限不表示容器内服务有权限。

只读测试会列目录但不写文件；勾选写入测试会创建随机 `.student-cloud-preflight-*.tmp` 文件，写入、回读核对并删除。**LOCAL 点击保存时总会读写检查**，所以只读测试成功的只读磁盘仍可能保存失败。更换启用中的本地目录前先停用。不要通过给整个磁盘开放权限来解决单个受控目录的问题。

依据：[本地路径校验与探测](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/application/StorageAdministrationService.java)、[申请、审批和隔离分配](../../backend-independent/cloud-drive-application/src/main/java/io/studentcloud/drive/application/WorkspaceCloudService.java)、[存储根配置](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/config/StorageConfiguration.java)。

## 远程表单：每个字段填什么

以下为当前远程参数接口实际返回的全部连接字段；不要套用旧驱动的 `signerType`、下载域名、代理地址或 CORS 配置。

| 表单字段 / 参数 | 填写格式与占位示例 | 校验和使用要点 |
| --- | --- | --- |
| API Endpoint / `endPoint` | `https://oss-cn-hangzhou.aliyuncs.com`；`https://s3.ap-southeast-1.amazonaws.com`；自建例 `https://s3.example.invalid:9000` | 只填 API 来源：协议、主机、可选端口；不含桶、对象路径、账号密码、查询或签名参数。省略协议默认 HTTPS；建议显式写 HTTPS |
| Bucket 名称 / `bucketName` | `example-student-files` | 只填桶名，不是 `oss://...` 或整条 URL。本应用要求 3～63 位小写字母、数字、点、短横线，首尾为小写字母或数字，不能包含连续点；提供方可有更严限制 |
| 区域 Region / `region` | OSS `cn-hangzhou`；AWS `ap-southeast-1`；MinIO 填管理员确认的签名区域 | 必填，1～64 位小写字母、数字、短横线。填桶所在或服务配置的签名区域，不填中文显示名或 Endpoint 域名 |
| Access Key ID / `accessKey` | `<ACCESS_KEY_ID>` | 新建必填；最长 512 字符；专用低权限身份，不是网站账号或阿里云登录密码 |
| Secret Key / `secretKey` | `<SECRET_ACCESS_KEY>` | 新建必填；最长 4096 字符；只在密钥字段填写，不放进 Endpoint、备注或聊天 |
| 临时凭据 Token / `sessionToken` | `<SESSION_TOKEN>` | 仅在使用相应临时凭据时填写，最长 16384 字符；须与同一组 AK/SK 配套并未过期 |
| 专用目录前缀 / `basePath` | `studentcloud/workspace-demo/` | 桶内逻辑前缀，不是操作系统目录；可空但会占用整桶前缀范围。建议每空间独立前缀；系统补尾部 `/`、去一个前导 `/`。最长 500 字符，不含反斜杠、连续 `/`、`.`/`..` 路径段或以 `.studentcloud-` 开头的路径段 |
| 使用 Path Style / `pathStyleAccess` | 开关 | 选 MinIO/S3 时参数默认开启，选 OSS 时默认关闭；以提供方 API 和证书部署为准。此开关真实传入 SDK，不是任意切换协议的选项 |

Endpoint 是服务地址；Bucket 是容器名；Region 是签名区域；`basePath` 是桶内前缀，四者分开填写。虚拟主机式与路径式会把桶放在不同 URL 位置，SDK 按开关组装，无需用户在 Endpoint 再拼桶。AWS 说明两种寻址方式及带点桶名的 TLS 限制，应用的允许地址规则仍以本项目为准。[AWS S3 寻址说明](https://docs.aws.amazon.com/AmazonS3/latest/userguide/VirtualHosting.html)

编辑已有连接时，接口不会回显 AK/SK/Token。密钥空白表示保留原值，不表示未配置。只改 AK 而不填写匹配 SK 会被拒绝；使用新凭据时提交同一组 AK/SK/Token。**当前空白 Token 也表示保留，表单没有明确清空旧 Token 的能力**；从临时身份转长期身份时不要以为空白会删除旧 Token，应由运维制定替换连接流程，先核对待确认写入与前缀重叠限制。

依据：[实际参数、字段校验和编辑留空语义](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteStorageAdministration.java)、[Endpoint 校验](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteEndpointPolicy.java)。

## 阿里云 OSS：按顺序接入

1. 在 OSS 控制台确认桶已存在、实际 Region、外网 API Endpoint、版本控制状态和网络访问策略。为该云盘保留独立桶或不重叠专用前缀，例如 `studentcloud/workspace-demo/`。
2. 为 RAM 身份准备受限凭据。不要使用主账号密钥。只读需要列对象及读取相应对象的能力；写入验证另需写对象、读桶版本状态和清理探针的权限，具体见权限表。
3. 管理页选择“阿里云 OSS”。以杭州桶为例填写 `https://oss-cn-hangzhou.aliyuncs.com`、桶名、Region `cn-hangzhou`、AK/SK，临时身份再填 Token；Path Style 使用默认关闭。
4. 点击“检查填写”和“只读测试连接”。成功表示专用前缀可列举；仍须用已授权账号读取一个已知对象才证明该对象可读。
5. 如需上传，确认当前桶从未启用/未挂起版本控制，勾选写入验证并执行。全部通过后保持勾选并保存；确认编辑页为“读写已验证”，再向成员授权。

OSS Region 示例应填 `cn-hangzhou`，不填 `oss-cn-hangzhou`；后者用于组成 Endpoint。本驱动使用 V4 签名，区域须匹配桶。[阿里云区域与域名说明](https://help.aliyun.com/en/oss/user-guide/access-oss-via-bucket-domain-name)、[V4 区域不匹配排查](https://www.alibabacloud.com/help/en/oss/user-guide/0002-00000228)

本项目默认允许的 OSS 地址是匹配规则的 HTTPS 官方外网 API 来源，不直接放行 `-internal` 地址、自定义域名、加速地址或非默认端口；这些地址需由运维逐项核对是否属于实际支持的 API 形态并配置精确允许来源。允许来源不保证 SDK 支持其语义。驱动关闭了 CNAME 支持及重定向，不能拿已绑定桶的自定义下载域名代替区域 API 地址。

本驱动在上传前读取版本状态，仅接受未启用状态；启用或挂起版本控制的 OSS 桶会拒绝写入。阿里云文档说明这两种状态下 `x-oss-forbid-overwrite` 不生效，因此不能把版本控制当成本驱动不覆盖写入的替代方案。无需为接入而修改已有生产桶版本设置，可选择独立且符合要求的桶，或保留只读。[OSS PutObject 的版本与禁止覆盖规则](https://help.aliyun.com/en/oss/developer-reference/putobject)

依据：[OSS SDK 接入与写入条件](../../backend-independent/cloud-object-storage/src/main/java/io/studentcloud/storage/AliyunOssObjectStore.java)。

## AWS S3 与其他 S3 兼容存储

1. 确认提供方真正提供 S3 API，并给出桶、Region、API Endpoint、AK/SK 和是否要求 Path Style。网页控制台、静态网站托管地址或文件下载地址不等于 S3 API。
2. AWS 示例：Endpoint `https://s3.ap-southeast-1.amazonaws.com`、Region `ap-southeast-1`、Bucket `example-student-files`，凭据使用专用 IAM 身份。根据桶和网络设置核对 Path Style；表单 S3 默认开关为开启，不应照搬其他客户端默认值。
3. 其他提供方示例：Endpoint `https://s3.example.invalid`、Bucket `example-student-files`、Region `<提供方给出的区域>`。由运维将实际 API 来源加入允许列表后，再按提供方要求设置 Path Style。示例 `.invalid` 域名不能连通。
4. 先只读测试，再执行完整读写探针；兼容方必须拒绝同名条件写入并支持安全探针清理。失败时保留只读，不能通过跳过测试来启用上传。

AWS 建议使用正确的桶区域端点；错误区域可能返回重定向。本应用关闭跨区域访问，不承诺自动追随区域变化。[AWS S3 地址和区域行为](https://docs.aws.amazon.com/AmazonS3/latest/userguide/VirtualHosting.html)

本应用使用 `If-None-Match: *` 创建对象。AWS 的条件写入在同名对象存在时拒绝覆盖；其他“兼容”实现必须由本应用探针实测，不能只凭产品名称认定支持。[AWS 条件写入](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)

S3 不统一禁止版本控制；写入验证仍要求探针回执、元数据和安全删除都通过。如果回执没有版本 ID，清理时会读取桶版本状态，非空状态会拒绝清理；如果有版本 ID，则须有读取和删除对应版本的权限。版本控制、对象锁、保留策略、代理过滤条件请求头，都可能让“能上传”但“清理失败”。本界面没有版本控制、保留策略或对象锁开关。

依据：[S3 SDK 配置和条件清理](../../backend-independent/cloud-object-storage/src/main/java/io/studentcloud/storage/S3ObjectStore.java)。

## MinIO：填写 API，不填写控制台

MinIO 接入复用 S3 驱动，没有单独的 MinIO 专有传输协议。选择“MinIO / S3”，填写实际 S3 API 来源、已存在桶、管理员确认的 Region、专用 Access Key/Secret Key，并通常保持 Path Style 开启。

例如外部部署可以使用 `https://minio-api.example.invalid`；如直接使用非默认 TLS 端口，可为 `https://minio-api.example.invalid:9000`，两者都需运维配置相应精确允许来源。MinIO 官方区分 S3 API 端口与 Console 端口：API 默认 9000，Console 是单独配置的端口（常见示例为 9001），实际部署可能经过负载均衡改为 443。[MinIO HTTP 入口说明](https://docs.min.io/aistor/reference/aistor-server/http-endpoints/)、[MinIO Console 端口说明](https://min.io/docs/minio/container/administration/minio-console.html)

后端在容器中时，`localhost`/`127.0.0.1` 指容器自身，不指用户电脑、宿主机或另一个 MinIO 容器。同网络容器宜通过经运维批准的服务 DNS 名和 TLS 入口连接。**本项目禁止非回环 HTTP 允许来源**：`http://minio:9000` 或 `http://192.168.x.x:9000` 即使网络可达也不能按当前策略获准；需要为该 API 配置可信 HTTPS，或使用同一网络命名空间内确实可达的回环开发入口。不要为了连接跳过证书验证。

只读成功但读写失败时，检查提供方权限、条件写入、回读及条件清理兼容性，而不是改成 Console 端口。未完成写入验证前继续保持只读。

## 运维：允许来源、加密与最小权限

本节面向运维和管理角色。不要要求成员在聊天中粘贴配置、环境文件、AK/SK、Token 或完整 SDK 异常。

| 内容服务设置 | 用途与实际规则 |
| --- | --- |
| `SC_STORAGE_MASTER_KEY` | 远程凭据加密主密钥，须为 **32 字节随机值的 Base64 编码**。不是 OSS/S3 Secret Key，不填入网页。缺失、格式错误或无法解密旧凭据时提示 `STORAGE_CREDENTIALS_UNAVAILABLE` |
| `SC_STORAGE_ALLOWED_ENDPOINTS` | 非默认官方来源的精确允许列表，可用逗号、分号或空白分隔。每项只含协议、主机和端口，不能含账号、路径、查询、片段；HTTPS 443/HTTP 80 会按默认端口归一化。协议和端口都是匹配条件，不支持通配符 |
| `SC_MANAGED_STORAGE_ROOT` | LOCAL 的绝对受控根；表单只能使用其独立子目录 |
| `SC_UPLOAD_STAGING_ROOT` | 远程上传/建目录及相关上传流程的本地私有暂存目录；内容服务须可访问、可写且非符号链接。只读远程连接测试不验证这个目录，所以“测试成功，实际上传报暂存未配置”需要另外处理 |

所有读取同一配置数据库的内容实例应使用同一有效主密钥。保管并备份主密钥；当前没有自动主密钥轮换/旧值迁移流程，直接更换可能使既有凭据无法解密。远程凭据以 AES-GCM 认证加密并绑定到挂载 ID，管理详情只返回凭据已配置标志和空白秘密字段。禁止把密钥写入知识库、README、命令输出或公开问题单。

允许列表中任意一项格式无效会使允许地址配置整体不可用，返回 `STORAGE_ENDPOINT_POLICY_UNAVAILABLE`。自建 HTTPS 来源可以是运维控制的域名；HTTP 只接受 `localhost`、`127.0.0.1` 或 IPv6 回环。官方默认地址规则是本项目白名单，不等于覆盖云厂商全部合法端点。SDK 的连接超时为 3 秒，单次 API 超时为 30 秒，自动错误重试关闭；页面反复点击不能修复底层网络。

### 按本项目调用分配权限

| 阶段 | OSS 所需动作 | AWS S3 所需动作/兼容等价能力 | 授权范围 |
| --- | --- | --- | --- |
| 只读连接探测 | `oss:ListObjects` | `s3:ListBucket` | 仅目标桶；允许本应用实际请求的专用前缀 |
| 正常读取与回读/对象核对 | `oss:GetObject` | `s3:GetObject`；按版本检查时需 `s3:GetObjectVersion` | 仅该云盘的专用前缀 |
| 正常上传、目录标记、探针 | `oss:PutObject` | `s3:PutObject` | 仅专用前缀，须允许条件请求 |
| 桶版本状态核对 | `oss:GetBucketVersioning` | `s3:GetBucketVersioning`（无版本回执的清理分支需要） | 目标桶；不需要授予修改版本控制 |
| 探针清理 | `oss:DeleteObject`；按版本路径可能用 `oss:DeleteObjectVersion` | `s3:DeleteObject`；有版本回执时用 `s3:DeleteObjectVersion` | 尽量仅 `<basePath>.studentcloud-probes/*`，不授予整桶业务对象删除 |

这是从当前调用推导的权限清单，不是可不经审阅直接套用的完整 IAM/RAM 策略。Bucket 条件、拒绝规则、组织策略、网络策略、KMS、保留规则等可能还需云管理员核对。不要授予 `*` 或全局管理策略来掩盖错误。本应用不需要在连接测试中列出账号所有桶、创建桶、修改 CORS 或修改桶策略。代理上传经内容服务完成，放宽桶 CORS通常不能解决后端认证和网络失败。

阿里云的动作与桶/对象资源分层、前缀限制见[OSS 权限动作表](https://www.alibabacloud.com/help/en/oss/user-guide/authorization-syntax-and-elements)与[目录级 RAM 授权示例](https://www.alibabacloud.com/help/en/oss/the-you-are-forbidden-to-list-buckets-error-message-appears-when-you-log-on-to-ossbrowser-using-the-accesskey)。AWS 条件写入权限见[AWS 条件写入说明](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)。具体权限须由桶管理员按实际策略评估。

依据：[加密实现](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteCredentialCipher.java)、[允许来源](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteEndpointPolicy.java)、[内容服务配置装配](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/config/RemoteStorageConfiguration.java)、[SDK 传输限制](../../backend-independent/cloud-object-storage/src/main/java/io/studentcloud/storage/SdkObjectStoreFactory.java)。

## 测试、保存和授权是三个结果

| 操作 | 真正做的事 | 成功还不能证明什么 |
| --- | --- | --- |
| 智能识别 / 检查填写 | 识别和校验表单格式 | 未发出连接探测，未保存、未授权 |
| LOCAL 只读测试 | 受控目录校验和列举 | 未验证写入；保存还会读写探测 |
| 远程只读测试 | 对 `basePath` 发起一次最多 1 条结果的列举 | 未读取文件、未验证上传/删除；返回 `writeVerified=false` 是预期结果 |
| 远程读写并清理测试 | 列举→创建随机 64 字节探针→再次同名创建必须被拒绝→回读校验→精确清理 | 未保存连接、未向成员分配目录或容量 |
| 启用状态保存远程连接 | 重新探测后加密存储配置；按是否选择写入验证保存可写状态 | 成员仍须单独授权；保存成功不保证未来网络永远可用 |
| 成员授权 | 分配本空间成员固定个人目录、容量和操作范围 | 不扩大该存储驱动尚未实现的能力 |

远程测试成功且勾选写入验证后保存，才会为该连接记录 `writeVerified=true`；只读方式保存或停用保存会令其为 false。读写只验证临时探针，不会清理业务对象。探针位于 `<basePath>.studentcloud-probes/<随机编号>`，成员文件列表会隐藏系统前缀。

出现 `cleanup=false` 或“临时测试对象未确认清理”时，本次测试未通过，当前调用没有保存配置；停止重复测试，记录返回的准确 `probeKey`，由桶管理员在受限渠道核对探针对象的标记、操作编号、大小、ETag 和版本。不能按前缀批量删除，也不能因页面报错便断定远端没有新对象。修复权限、保留策略或兼容性并确认该探针已处理后再测。LOCAL 临时文件清理失败同样由全局管理员检查该受控目录。

## 按失败阶段排查

先记录“识别、只读测试、写入测试、保存、实际上传、成员访问、结果核查”中的哪一步失败，再记录时间、角色、页面提示、公开错误码和 HTTP 状态。不要仅根据某个数字猜测密码错误。下表的云厂商错误名是运维在受控日志或厂商控制台中可能看到的线索；应用会脱敏合并，不保证将原始错误名返回网页。

| 表现 / 公开错误 | 常见分界 | 下一步与预期结果 |
| --- | --- | --- |
| 400；`STORAGE_INVALID` / `STORAGE_ENDPOINT_INVALID` | 本地参数或版本格式无效 | 核对 API 来源没有路径/签名，Bucket、Region、前缀、别名符合格式；新建 AK/SK 非空。修正后先检查填写 |
| 400；`OBJECT_STORE_REJECTED` | 提供方拒绝请求参数 | 核对类型、Region、Path Style 和服务兼容性；不要不断换密钥或修改桶策略 |
| 401、登录过期 | 网站身份/会话无效 | 重新登录，确认账号和空间仍启用，再打开原页面。网站 401 不等于云 AK 错误 |
| 403；`STORAGE_FORBIDDEN` / `STORAGE_LOCATION_OWNER_REQUIRED` | 管理角色、工作空间或连接创建者权限 | 使用正确空间管理员；位置变更由创建者或全局管理员处理。不要用提升成员全局角色代替授权 |
| 403；`STORAGE_ENDPOINT_FORBIDDEN` | API 来源未获运维允许 | 核对官方 API 格式，或请运维审阅精确允许来源；不允许 HTTP 私网来源、下载域名或路径代理绕过 |
| 提供方 401/403；应用 `OBJECT_STORE_FORBIDDEN` | AK/SK/Token、过期、云端拒绝或签名 | 检查同一组凭据、临时 Token、桶/前缀授权及显式拒绝策略；应用会把提供方 401 与 403 合并为 403 |
| `SignatureDoesNotMatch` / V4 Region 错误线索 | 签名区域或凭据不匹配；时间或代理头变化 | 对照桶 Region 与 API Endpoint，去掉错误的 `oss-` Region 前缀，核对服务时间与可信代理；当前没有签名版本可选字段 |
| 404；`STORAGE_NOT_FOUND` | 本应用的连接不存在或不在当前可见范围 | 刷新存储列表，确认账号、空间和连接是否仍存在；不要尝试枚举其他空间 ID |
| 404；`OBJECT_STORE_NOT_FOUND`；厂商 `NoSuchBucket` | 桶/对象名错误、桶不在此来源或资源已删除 | 在厂商控制台确认桶和区域，区分“列桶失败”和“某个文件不存在”；应用不自动创建桶 |
| DNS、连接拒绝、503 `OBJECT_STORE_UNAVAILABLE` | 后端无法解析/访问，端口或服务不可用 | 从内容服务运行环境检查 DNS、路由、防火墙、API 监听和代理；浏览器能打开 Console 不证明后端可达 |
| TLS 握手失败、证书名称不匹配 | 证书过期、链不可信、使用 IP/错误域名、虚拟桶域名不匹配 | 使用正确 API 域名，补齐受信证书链并核对系统时间；不关闭 TLS 校验 |
| 504 `OBJECT_STORE_TIMEOUT` / 上传后超时 | 请求未在时间内完成，写入结果可能不确定 | 只读失败可修复网络后重测；上传后先看待核查写入，不能直接重传 |
| 429 `STORAGE_PROBE_BUSY` / `REMOTE_BUSY` | 当前探测/读写并发已满 | 等当前任务结束后再试；探测最多同时 2 个，远程读写最多同时 4 个 |
| 409 `STORAGE_CHANGED` / `REMOTE_AUTHORIZATION_CHANGED` | 保存期间配置或授权发生变化 | 重新读取页面、核对修改后保存，不能复用旧版本盲目重发 |
| 409 `STORAGE_ROOT_OVERLAP` | 本地目录或同 Endpoint、同桶内前缀等同/互相包含 | 分配独立不重叠目录/前缀；不能用多个别名共享同一根来绕过 |
| 409 `STORAGE_DISABLE_BEFORE_ROOT_CHANGE` | 正在使用的连接位置将改变 | 先核对待确认写入，再停用；重新进入编辑位置，测试、保存后启用 |
| 只读通过但没有上传按钮 | 未保存写入验证，或个人权限不足 | 看已保存状态，再分别核对成员 `upload`/`mkdir` 权限；只读 `writeVerified=false` 本身不是失败 |
| 409 `OBJECT_STORE_VERSIONING_UNSUPPORTED` | OSS 已启用或挂起版本控制 | 使用符合当前写入策略的独立桶或保留只读；不要改生产桶版本状态来试错 |
| “同名拒绝/回读失败” | 条件请求被忽略或读取结果不一致 | 不启用写入；检查兼容实现和代理是否保留条件头，确认探针清理结果 |
| “探针未确认清理”、`cleanup=false` | 清理权限、版本状态、锁定/保留或响应不确定 | 停止重复测试，按准确探针对象核查；不要批量删除系统前缀 |
| 503 `REMOTE_STAGING_MISSING` | 暂存目录缺失、非目录或符号链接 | 运维配置 `SC_UPLOAD_STAGING_ROOT` 并核对运行身份的写权限和可用空间；连接测试不覆盖此项 |
| 413 `DRIVE_SIZE` / `REMOTE_FILE_SIZE` | 超过应用单文件范围 | 远程上传 1 字节～50 MiB、读取不超过 50 MiB；分片不能绕过远程限制 |
| 409 `DRIVE_QUOTA_EXCEEDED` | 成员目录容量不足 | 成员联系空间管理员核对实际使用与额度；不是云桶余额不足，不能通过重试增加额度 |
| 409 `DRIVE_CONFLICT` / `OBJECT_STORE_CONFLICT` | 目标同名或并发冲突 | 换唯一文件名或取消；应用不覆盖既有对象。业务上传后若同时提示待核查，先按待核查流程处理 |
| 409 `REMOTE_WRITE_PENDING` | 同一存储有执行中或结果不确定的写入 | 检查该连接的“待核查写入”；新写入及连接修改被保留冻结，见下节 |
| 501 `STORAGE_TYPE_UNAVAILABLE` / `STORAGE_CAPABILITY_UNAVAILABLE` | 协议或文件动作未实现 | 只使用当前开放的 LOCAL/OSS/S3/MinIO 及对应能力；修改凭据不能补齐驱动 |

外部签名与区域规则参见[阿里云 V4 Region 排查](https://www.alibabacloud.com/help/en/oss/user-guide/0002-00000228)及[AWS S3 区域端点](https://docs.aws.amazon.com/AmazonS3/latest/userguide/VirtualHosting.html)。DNS/TLS 问题在本应用常被合并为 503，网页不能据此确定具体根因。云端费用、流控、容量策略也可能被合并为暂不可用，须由云管理员查看受控厂商记录，不能编造一个“余额不足”结论。

依据：[脱敏错误映射](../../backend-independent/cloud-object-storage/src/main/java/io/studentcloud/storage/StoreFailures.java)、[本地检查](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/application/StorageAdministrationService.java)、[远程状态、额度和暂存检查](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteDriveService.java)。

## 上传结果不确定：PUBLISHING、UNCERTAIN 与核查

超时可能发生在远端已写入、应用尚未确认时。当前系统为同一远程存储保留写入记录及冻结保护，不会因为刷新、重启、对象暂时查不到就自动重放上传。

1. 成员保留操作时间、文件在本人目录中的路径和页面提示，联系本空间管理员；不要换账号重试或删除远端文件。
2. 空间管理员或全局管理员打开**已保存远程连接**的编辑页，展开“待核查写入”，点击“读取待核查写入”。记录包含操作编号、用户目录内路径、状态和时间，不显示桶密钥或完整对象键。
3. `PREPARING` 表示准备阶段；`PUBLISHING` 表示进入发布阶段；`UNCERTAIN` 表示结果未确认。对允许核查的记录点击“检查写入结果”。此操作只读取对象状态，不重放写入。
4. 只有远端对象键、大小、操作 ID 元数据与原记录、连接版本等一致，才返回 `CONFIRMED` / `COMPLETED`，释放该操作的容量预留和写入冻结。
5. 返回 `PENDING`、对象不存在、元数据不匹配、核查失败或仍处于准备阶段时，冻结继续保留；“没有发现”不是“没有写入”的充分证据。继续由管理员/运维在受控渠道核查，不直接清库、删除守卫、改状态或自动重传。当前没有自动解除所有不确定状态或强制失败的界面。

远程核查接口仅向管理角色开放，且空间管理员只能处理本空间。核查失败不要上传完整 SDK 日志；反馈可包括时间、公开错误码、操作编号和已脱敏的用户目录内路径。

依据：[核查入口](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/http/RemoteWriteAdministrationController.java)、[肯定证据确认与保留冻结](../../backend-independent/cloud-content-service/src/main/java/io/studentcloud/content/remote/RemoteDriveService.java)。

## 保存后成员仍看不到或不能上传

先确认存储启用、成员仍属于同一工作空间且具体云盘使用申请获批，再核对成员的固定个人目录、额度和所需操作权。普通成员看到的是 `/members/u<本人编号>` 对应的授权视图，空间管理员是自己的 `/admins/u<本人编号>`；不能把整个桶或其他人的目录作为成员根。

连接保存会给该工作空间的普通管理员配置个人目录授权；不会把全空间成员自动加进去。远程只读保存只提供列目录和读取；即使成员授权表里选择了上传，连接未完成写入验证时仍会被后端拒绝。读写已验证后仍需空间管理员分配成员的 `list`、`read`、`mkdir`、`upload` 等所需权限，范围不能超过该管理员自己的授权。

配额不足由空间管理员核对并调整成员额度。远程源按该用户前缀内对象大小检查当前使用量；远端账单/账户余额与本应用成员配额是不同问题。当前单次目录检索有预算，过大目录提示应缩小范围，不表示远端对象丢失。

## 连接维护与保留历史

- 更换远程 Provider、Endpoint、Bucket、Region、Path Style 或专用前缀前，先处理待核查写入，再停用连接。重新读取配置，修改、测试并保存后再启用。系统不会自动迁移旧桶数据或改成员历史文件位置。
- 同 Endpoint、同 Bucket 的远程专用前缀不能相等或互相包含；空前缀会覆盖整桶范围。不同域名可能指向同一物理桶，仍应由运维避免别名造成实际重叠。
- 远程连接当前不提供删除历史或删除对象，删除请求会提示先停用保留。LOCAL 删除配置须先停用并明确确认；有成员云盘申请历史时保持停用，不能删除。删除本地连接配置也不会删除实际文件。
- 当前不自动配置 CORS，不自动创建桶，不自动轮换密钥，不支持任意签名算法、匿名连接、自定义请求头、跳过 TLS 校验或把 WebDAV/FTP/SFTP 转为 S3。

遇到无法确认的情况，提供最小反馈：失败阶段、时间、本人角色、公开错误码/状态、是否已保存和已清理、是否存在待核查记录。收集错误码代替文件正文；只在确有需要时经授权提供已脱敏的本人目录相对路径。密钥、Token、环境文件、数据库密文和带签名链接留在管理员的受控配置渠道，不能发进聊天。

排查只使用当前账号已经获准访问的目录和资料。不得根据成员姓名、文件名、课程名称或猜测路径来查找、读取或推断他人私人资料；知道一个名字或路径不构成授权。管理角色也不能让助手以排障为由绕过成员与目录隔离。
