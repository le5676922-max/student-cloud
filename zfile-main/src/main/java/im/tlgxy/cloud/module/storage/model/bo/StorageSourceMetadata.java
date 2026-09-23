package im.tlgxy.cloud.module.storage.model.bo;

import lombok.Data;

/**
 * 存储源元数据配置业务对象（BO）
 * <p>
 * 定义每个存储源类型支持的功能特性，用于控制前端页面上各功能的显示和启用状态。
 * 所有字段都提供了合理的默认值（通常为 true），因此每个具体的存储源实现类
 * 只需要覆写与默认值不同的字段即可，无需声明所有字段。
 * </p>
 * <p>
 * 主要用途：不同存储源的 API 能力不同（比如 S3 支持分片上传、FTP 不支持直接上传等），
 * 通过此元数据对象向系统声明该存储源支持的功能集合，
 * 前端根据这些标识来决定是否显示相应的操作按钮。
 * </p>
 */
@Data
public class StorageSourceMetadata {

    /**
     * 上传类型
     * <p>
     * 定义了该存储源使用的上传机制，如微软系上传、代理中转上传、S3 直传、又拍云上传等。
     * 不同类型对应不同的上传流程和前端交互方式。
     * </p>
     */
    private UploadType uploadType;

    /**
     * 是否支持重命名文件夹
     * 默认为 true，FTP 等协议可能不支持此操作
     */
    private boolean supportRenameFolder = true;

    /**
     * 是否支持移动文件夹
     * 默认为 true，某些只读存储可能不支持
     */
    private boolean supportMoveFolder = true;

    /**
     * 是否支持复制文件夹
     * 默认为 true，某些云存储 API 可能不支持直接的复制操作
     */
    private boolean supportCopyFolder = true;

    /**
     * 是否支持删除非空文件夹
     * 默认为 true，某些存储协议（如 FTP）需要先清空文件夹才能删除
     */
    private boolean supportDeleteNotEmptyFolder = true;

    /**
     * 是否需要在上传文件前先创建目标文件夹
     * 默认为 true，某些存储在上传时会自动创建不存在的父目录
     */
    private boolean needCreateFolderBeforeUpload = true;

    /**
     * 上传类型枚举
     * <p>
     * 定义系统支持的各种上传机制。
     * </p>
     */
    public enum UploadType {

        /**
         * 微软系上传方式
         * 适用于 OneDrive（国际版、国内版）和 SharePoint（国际版、国内版）。
         * 使用 Microsoft Graph API 提供的上传接口。
         */
        MICROSOFT,

        /**
         * 使用 TlgxyCloud 服务端作为代理中转上传
         * 文件先上传到 TlgxyCloud 服务器，再由服务器转发到目标存储。
         * 适用于不提供客户端直传 API 的存储类型。
         */
        PROXY,

        /**
         * 亚马逊 S3 兼容协议直传
         * 适用于 AWS S3 及所有兼容 S3 协议的对象存储（MinIO、阿里云 OSS 等）。
         * 文件直接从客户端上传到对象存储，无需经过 TlgxyCloud 服务器。
         */
        S3,

        /**
         * 又拍云直传
         * 适用于又拍云对象存储服务，需要先获取上传签名和策略。
         */
        UPYUN


    }

}
