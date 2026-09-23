package im.tlgxy.cloud.module.admin.controller;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import im.tlgxy.cloud.core.util.AjaxJson;
import im.tlgxy.cloud.core.util.RequestHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * IP 地址辅助控制器，用于在管理后台排查网络相关问题。
 * <p>
 * 提供以下功能：
 * <ul>
 *   <li>获取客户端真实 IP 地址</li>
 *   <li>获取服务器地址（含反向代理场景）</li>
 *   <li>获取请求头信息（用于排查反向代理配置是否正确）</li>
 * </ul>
 *
 * @author zhaojun
 */
@Tag(name = "IP 地址辅助 Controller")
@Slf4j
@RequestMapping("/admin")
@RestController
public class IpHelperController {

    /**
     * 当前 HTTP 请求对象，由 Spring 容器自动注入
     */
    @Resource
    private HttpServletRequest httpServletRequest;

    /**
     * 获取当前请求的客户端真实 IP 地址。
     * <p>
     * 会考虑 X-Forwarded-For 等反向代理头信息，优先获取真实客户端 IP。
     *
     * @return 包含客户端 IP 地址字符串的 AjaxJson 响应
     */
    @GetMapping("clientIp")
    @Operation(summary = "获取客户端IP", description ="获取当前请求的客户端IP地址")
    public AjaxJson<String> clientIp() {
        // 使用 Hutool 工具类获取客户端真实 IP，自动处理反向代理场景
        String clientIp = JakartaServletUtil.getClientIP(httpServletRequest);
        return AjaxJson.getSuccessData(clientIp);
    }

    /**
     * 获取当前请求的服务器地址。
     * <p>
     * 注意：如果经过反向代理，获取到的可能是反向代理服务器的地址，而非真实服务器地址。
     *
     * @return 包含服务器地址字符串的 AjaxJson 响应
     */
    @GetMapping("serverAddress")
    @Operation(summary = "获取服务器地址", description = "获取当前请求的服务器地址(如果是反向代理过，可能获取到的是反向代理服务器的地址)")
    public AjaxJson<String> serverAddress() {
        return AjaxJson.getSuccessData(RequestHolder.getRequestServerAddress());
    }

    /**
     * 获取服务器接收到的所有请求头信息。
     * <p>
     * 将多值请求头合并为逗号分隔的字符串，并以格式化的 JSON 形式返回，
     * 方便管理员排查反向代理、负载均衡等网络配置问题。
     *
     * @return 包含格式化 JSON 请求头信息的 AjaxJson 响应
     */
    @GetMapping("headers")
    @Operation(summary = "获取 Headers", description = "获取服务器接收到的请求头信息，可用于排查反向代理配置问题")
    public AjaxJson<String> headers() {
        // 获取原始请求头 Map（一个 key 可能对应多个值）
        Map<String, List<String>> headersMap = JakartaServletUtil.getHeadersMap(httpServletRequest);
        // 将多值 List 合并为逗号分隔的单个字符串，便于查看
        Map<String, String> singleValueHeaderMap = headersMap.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.join(",", entry.getValue())
                ));
        // 以格式化的 JSON 形式返回，方便阅读
        return AjaxJson.getSuccessData(JSON.toJSONString(singleValueHeaderMap, JSONWriter.Feature.PrettyFormat));
    }
}