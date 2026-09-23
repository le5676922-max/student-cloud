package im.tlgxy.cloud.core.util;

import com.alibaba.dcm.DnsCacheManipulator;
import com.alibaba.fastjson2.JSONArray;
import org.springframework.lang.Nullable;

/**
 * DNS 解析工具类
 * <p>
 * 提供通过阿里云 HTTP DNS 服务解析域名 IP 地址的功能，
 * 并支持将解析结果缓存到 JVM 的 DNS 缓存中，以加快后续的域名解析速度。
 * </p>
 *
 * @author zhaojun
 */
public class DnsUtil {

    /**
     * 通过阿里云 HTTP DNS 服务获取域名对应的 IP 地址
     * <p>
     * 使用阿里云公共 DNS 服务器 (223.5.5.5) 进行域名解析，
     * 请求超时时间为 3000 毫秒。short=1 参数表示只返回 IP 地址，不返回其他信息。
     * </p>
     *
     * @param   domain
     *          需要解析的域名
     *
     * @return  IP 地址数组，如果解析失败则返回 null
     */
    public static @Nullable String[] getDomainIpByHttpDns(String domain) {
        // 调用阿里云 HTTP DNS 接口，传入域名和 short=1 参数，超时 3 秒
        String jsonArrayStr = cn.hutool.http.HttpUtil.get("http://223.5.5.5/resolve?name=" + domain + "&short=1", 3000);
        // 将返回的 JSON 数组字符串解析为 JSONArray 对象
        JSONArray jsonArray = JSONArray.parseArray(jsonArrayStr);
        if (!jsonArray.isEmpty()) {
            // 将 JSONArray 中的每个元素转换为 String，存入结果数组
            String[] result = new String[jsonArray.size()];
            for (int i = 0; i < jsonArray.size(); i++) {
                result[i] = jsonArray.getString(i);
            }
            return result;
        } else {
            // 解析结果为空，返回 null
            return null;
        }
    }


    /**
     * 通过 HTTP DNS 获取域名对应的 IP 地址，并设置 JVM DNS 缓存
     * <p>
     * 首先调用 HTTP DNS 接口解析域名，然后将解析结果通过
     * {@link DnsCacheManipulator} 设置到 JVM 的 DNS 缓存中，
     * 缓存过期时间由 cacheTime 参数指定。这样后续的标准 DNS 解析
     * 将直接使用缓存结果，避免重复请求 HTTP DNS 服务。
     * </p>
     *
     * @param   domain
     *          需要解析的域名
     *
     * @param   cacheTime
     *          DNS 缓存时间，单位为毫秒
     *
     * @return  IP 地址数组，如果 HTTP DNS 解析失败则返回 null
     */
    public static String[] getDomainIpByHttpDnsAndCache(String domain, int cacheTime) {
        // 先通过 HTTP DNS 解析域名
        String[] domainIpByHttpDns = getDomainIpByHttpDns(domain);
        if (domainIpByHttpDns != null) {
            // 将解析结果设置到 JVM DNS 缓存中，有效期为 cacheTime 毫秒
            DnsCacheManipulator.setDnsCache(cacheTime, domain, domainIpByHttpDns);
        }
        return domainIpByHttpDns;
    }

}