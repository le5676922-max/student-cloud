package im.tlgxy.cloud.module.storage.model.param;

import im.tlgxy.cloud.module.storage.annotation.StorageParamItem;
import im.tlgxy.cloud.module.storage.annotation.impl.EncodingStorageParamSelect;
import im.tlgxy.cloud.module.storage.model.enums.StorageParamTypeEnum;
import lombok.Getter;

/**
 * SFTP 初始化参数
 *
 * @author zhaojun
 */
@Getter
public class SftpParam extends ProxyTransferParam {

    @StorageParamItem(name = "域名或 IP", order = 1)
    private String host;

    @StorageParamItem(name = "端口", order = 2)
    private int port;

    @StorageParamItem(name = "SSH 主机 SHA256 指纹", order = 2, description = "必须从服务器管理员或可信控制台核实，格式 SHA256:...。不要盲信 ssh-keyscan 扫描结果；更换主机密钥后需重新核实。未配置或不匹配将拒绝连接。")
    private String hostKeyFingerprint;

    @StorageParamItem(name = "编码格式",
            defaultValue = "UTF-8",
            type = StorageParamTypeEnum.SELECT,
            optionsClass = EncodingStorageParamSelect.class,
            description = "表示文件夹及文件名称的编码格式，不表示文本内容的编码格式.", order = 3)
    private String encoding;

    @StorageParamItem(name = "用户名", required = false, order = 4)
    private String username;

    @StorageParamItem(name = "密码", required = false, order = 5)
    private String password;

    @StorageParamItem(name = "密钥", type = StorageParamTypeEnum.TEXTAREA, required = false, order = 6)
    private String privateKey;

    @StorageParamItem(name = "密钥 passphrase", required = false, order = 7)
    private String passphrase;

    @StorageParamItem(name = "基路径", defaultValue = "/", description = "基路径表示该存储源哪个目录在 TlgxyCloud 中作为根目录，如： '/'，'/文件夹1'", order = 8)
    private String basePath;

    @StorageParamItem(name = "最大连接数", defaultValue = "8", description = "要确保你服务器 SSH 的可用连接数大于这个值，不然可能会报错 channel is not opened.", order = 9)
    private Integer maxConnections;

}
