package io.studentcloud.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 启动统一网关，转发三个业务服务的请求，不承载业务数据库。
 *
 * No business service or database implementation is on this application's classpath.
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
}
