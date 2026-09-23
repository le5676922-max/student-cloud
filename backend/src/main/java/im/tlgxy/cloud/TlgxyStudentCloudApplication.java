package im.tlgxy.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;


/**
 * 通理工学生个人云盘 应用程序主启动类。
 * <p>
 * 通理工学生个人云盘 是一个基于 Spring Boot 的网盘/文件管理系统，此类是整个应用的入口，
 * 负责引导 Spring Boot 应用启动并完成各项自动配置。
 * </p>
 *
 * @author tongligxy
 */
@SpringBootApplication
// 启用 AspectJ 自动代理，exposeProxy = true 允许通过 AopContext.currentProxy() 获取当前代理对象，
// proxyTargetClass = true 强制使用 CGLIB 代理（基于类的代理），以便对非接口方法也能进行 AOP 拦截
@EnableAspectJAutoProxy(exposeProxy = true, proxyTargetClass = true)
// 扫描并注册指定包下的 Servlet 组件（如 Filter、Servlet、Listener）
@ServletComponentScan(basePackages = {"im.tlgxy.cloud.core.filter", "im.tlgxy.cloud.module.storage.filter"})
// 组件扫描，覆盖所有 im.tlgxy.cloud 子包，确保 Spring 容器能发现并管理所有 Bean
@ComponentScan(basePackages = "im.tlgxy.cloud.*")
public class TlgxyStudentCloudApplication {

    /**
     * 应用程序主入口方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TlgxyStudentCloudApplication.class, args);
    }

}
