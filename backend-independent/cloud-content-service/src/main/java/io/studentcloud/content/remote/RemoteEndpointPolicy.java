package io.studentcloud.content.remote;

import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.object.ObjectStore;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Accept provider API origins, or an exact operator-controlled origin. No endpoint redirects. */
final class RemoteEndpointPolicy {
    private final Set<URI> allowed=new HashSet<>();
    private final boolean configured;
    RemoteEndpointPolicy(String origins){
        boolean valid=true;
        if(origins!=null&&!origins.isBlank())for(String value:origins.trim().split("[,;\\s]+")){
            try{URI origin=origin(value);if("http".equals(origin.getScheme())&&!loopback(origin.getHost()))throw new IllegalArgumentException();allowed.add(origin);}
            catch(RuntimeException invalid){valid=false;}
        }
        configured=valid;
    }
    URI validate(String value,String scheme,ObjectStore.Provider provider){
        if(!configured)throw new ApplicationFault("STORAGE_ENDPOINT_POLICY_UNAVAILABLE",503,"远程存储允许地址配置无效，请联系运维");
        URI endpoint;
        try{String raw=value==null?"":value.trim();endpoint=origin(raw.contains("://")?raw:(scheme==null||scheme.isBlank()?"https":scheme)+"://"+raw);}
        catch(RuntimeException invalid){throw invalid();}
        if(allowed.contains(endpoint))return endpoint;
        if(!"https".equals(endpoint.getScheme())||endpoint.getPort()!=-1)throw forbidden();
        String host=endpoint.getHost();
        boolean official=provider==ObjectStore.Provider.ALIYUN_OSS?aliyun(host):aws(host);
        if(!official)throw forbidden();return endpoint;
    }
    static boolean aws(String host){return host!=null&&host.matches("s3(?:[.-](?:dualstack\\.)?[a-z]{2}(?:-gov)?-[a-z]+-[0-9]+)?\\.amazonaws\\.com(?:\\.cn)?");}
    private static boolean aliyun(String host){return host!=null&&host.matches("oss-[a-z0-9]+(?:-[a-z0-9]+)*\\.aliyuncs\\.com")&&!host.contains("-internal.");}
    private static URI origin(String value){
        try{
            URI input=URI.create(value);String scheme=input.getScheme()==null?"":input.getScheme().toLowerCase(Locale.ROOT);String host=input.getHost();
            if(!Set.of("https","http").contains(scheme)||host==null||host.endsWith(".")||input.getRawUserInfo()!=null||input.getRawQuery()!=null||input.getRawFragment()!=null
                    ||(input.getRawPath()!=null&&!input.getRawPath().isEmpty()&&!input.getRawPath().equals("/"))||input.getPort()==0||input.getPort()>65535)throw new IllegalArgumentException();
            int port=input.getPort();if((scheme.equals("https")&&port==443)||(scheme.equals("http")&&port==80))port=-1;
            return new URI(scheme,null,host.toLowerCase(Locale.ROOT),port,"",null,null);
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid endpoint origin");}
    }
    private static boolean loopback(String host){return "localhost".equals(host)||"127.0.0.1".equals(host)||"[::1]".equals(host)||"::1".equals(host);}
    private static ApplicationFault invalid(){return new ApplicationFault("STORAGE_ENDPOINT_INVALID",400,"请填写不含账号、路径、查询参数或片段的存储 API 地址");}
    private static ApplicationFault forbidden(){return new ApplicationFault("STORAGE_ENDPOINT_FORBIDDEN",403,"该存储 API 地址未获运维允许，请使用官方地址或已配置的允许地址");}
}
