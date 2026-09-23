package im.tlgxy.cloud.module.onlyoffice.service;

import cn.hutool.jwt.JWTUtil;
import com.alibaba.fastjson2.JSONObject;
import im.tlgxy.cloud.core.util.FileUtils;
import im.tlgxy.cloud.core.util.OnlyOfficeKeyCacheUtils;
import im.tlgxy.cloud.core.util.StringUtils;
import im.tlgxy.cloud.core.util.TlgxyCloudAuthUtil;
import im.tlgxy.cloud.module.config.model.dto.SystemConfigDTO;
import im.tlgxy.cloud.module.config.service.SystemConfigService;
import im.tlgxy.cloud.module.onlyoffice.model.OnlyOfficeFile;
import im.tlgxy.cloud.module.storage.model.result.FileItemResult;
import im.tlgxy.cloud.module.user.model.entity.User;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * OnlyOffice 预览配置生成服务
 * <p>
 * 同时被普通预览与分享预览复用, 统一处理 Key 缓存、JWT 签名以及编辑权限判定,
 * 避免两个入口对预览配置的处理逻辑出现偏差。
 * 负责构建 OnlyOffice 编辑器所需的完整 JSON 配置，包括文档信息、编辑器配置和 JWT Token。
 * </p>
 *
 * @author zhaojun
 */
@Service
public class OnlyOfficeConfigService {

    /** 系统配置服务，用于获取 OnlyOffice 的 Secret 和回调地址等配置 */
    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 生成 OnlyOffice 预览配置
     *
     * <p>同时将发起预览的用户 ID、是否允许编辑写入 {@link OnlyOfficeFile} 缓存, 供回调时校验。</p>
     *
     * @param fileItemResult 文件信息（名称、URL 等）
     * @param onlyOfficeFile 文件缓存信息(包含 storageKey/fullPath/userId)
     * @param allowEdit      预览发起时的编辑权限
     * @return OnlyOffice 预览配置 JSON，前端直接传给 OnlyOffice 编辑器 API
     */
    public JSONObject createConfig(FileItemResult fileItemResult,
                                   OnlyOfficeFile onlyOfficeFile,
                                   boolean allowEdit) {
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        String onlyOfficeSecret = systemConfig.getOnlyOfficeSecret();
        // 未配置 Secret 时回调无法验签, 直接降级为只读, 防止生成虚假的编辑配置.
        boolean effectiveAllowEdit = allowEdit && StringUtils.isNotEmpty(onlyOfficeSecret);
        onlyOfficeFile.setAllowEdit(effectiveAllowEdit);
        // 获取或生成缓存 key（过期时间 3000 秒），用于 OnlyOffice 回调时查找对应文件
        String key = OnlyOfficeKeyCacheUtils.getKeyOrPutNew(onlyOfficeFile, 3000);
        return buildPayload(fileItemResult, key, effectiveAllowEdit, systemConfig);
    }

    /**
     * 构建 OnlyOffice 预览所需的完整 JSON 配置
     *
     * @param fileItemResult 文件信息
     * @param key           唯一文档标识符
     * @param allowEdit     是否允许编辑
     * @param systemConfig  系统配置
     * @return OnlyOffice 配置 JSON 对象
     */
    private JSONObject buildPayload(FileItemResult fileItemResult, String key, boolean allowEdit, SystemConfigDTO systemConfig) {
        JSONObject jsonObject = new JSONObject();
        // 编辑器宽度和高度设置为 100%，占满容器
        jsonObject.put("width", "100%");
        jsonObject.put("height", "100%");

        // 文档配置：文件类型、唯一标识 key、权限、标题、下载地址、语言等
        jsonObject.put("document", new JSONObject()
                .fluentPut("fileType", FileUtils.getExtension(fileItemResult.getName()))
                .fluentPut("key", key)
                .fluentPut("permissions", new JSONObject()
                        .fluentPut("edit", allowEdit))
                .fluentPut("title", fileItemResult.getName())
                .fluentPut("url", fileItemResult.getUrl())
                .fluentPut("lang", "zh-CN"));

        User currentUser = TlgxyCloudAuthUtil.getCurrentUser();
        String onlyOfficeSecret = systemConfig.getOnlyOfficeSecret();

        // 构建用户信息（编辑者身份）
        JSONObject userJson = new JSONObject();
        if (currentUser != null) {
            userJson.fluentPut("id", currentUser.getId())
                    .fluentPut("name", StringUtils.firstNonNull(currentUser.getNickname(), currentUser.getUsername()));
        }

        // 编辑器配置：回调地址、语言、用户信息
        jsonObject.put("editorConfig", new JSONObject()
                .fluentPut("callbackUrl", StringUtils.concat(systemConfigService.getAxiosFromDomainOrSetting(), "/onlyOffice/callback"))
                .fluentPut("lang", "zh-CN")
                .fluentPut("user", userJson));

        // 如果配置了 Secret，对整个配置 JSON 进行 JWT 签名
        if (StringUtils.isNotEmpty(onlyOfficeSecret)) {
            String token = JWTUtil.createToken(jsonObject, onlyOfficeSecret.getBytes(StandardCharsets.UTF_8));
            jsonObject.put("token", token);
        }

        return jsonObject;
    }

}