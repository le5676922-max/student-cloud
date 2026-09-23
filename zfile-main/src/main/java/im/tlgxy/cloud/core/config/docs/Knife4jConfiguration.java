package im.tlgxy.cloud.core.config.docs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j（Swagger/OpenAPI 3）配置类。
 * <p>
 * 将 API 文档分为两个分组：
 * </p>
 * <ul>
 *   <li><b>前台功能：</b>暴露给普通用户使用的 API，排除 /admin/** 路径。</li>
 *   <li><b>管理员功能：</b>管理员专用的 API（/admin/** 路径），并为所有管理员接口自动添加
 *       tongligong-student-cloud-token 请求头配置，方便在 Swagger UI 中测试需要认证的接口。</li>
 * </ul>
 * <p>
 * 同时配置了 OpenAPI 文档的基本信息，包括标题、描述、联系方式、版本号和许可证等。
 * </p>
 *
 * @author zhaojun
 */
@Configuration
public class Knife4jConfiguration {

    /**
     * 创建"前台功能"API 分组。
     * <p>
     * 扫描 im.tlgxy.cloud.module 包下的所有接口，排除 /admin/** 路径，
     * 这些接口是面向普通用户的前台功能。
     * </p>
     *
     * @return 前台功能分组的 GroupedOpenApi 实例
     */
    @Bean
    public GroupedOpenApi groupedOpenApi() {
        String groupName = "前台功能";
        return GroupedOpenApi.builder()
                .group(groupName)
                // 扫描 module 包下的所有 API
                .packagesToScan("im.tlgxy.cloud.module")
                // 排除管理员接口路径
                .pathsToExclude("/admin/**")
                .build();
    }

    /**
     * 创建"管理员功能"API 分组。
     * <p>
     * 只包含 /admin/** 路径的接口，并添加全局操作定制器，
     * 为所有管理员接口自动追加 tongligong-student-cloud-token 请求头参数。
     * </p>
     *
     * @return 管理员功能分组的 GroupedOpenApi 实例
     */
    @Bean
    public GroupedOpenApi groupedOpenApi2() {
        String groupName = "管理员功能";
        return GroupedOpenApi.builder()
                .group(groupName)
                .packagesToScan("im.tlgxy.cloud.module")
                // 只包含管理员接口路径
                .pathsToMatch("/admin/**")
                // 添加全局操作定制器，为每个接口追加 token header
                .addOperationCustomizer(globalOperationCustomizer())
                .build();
    }

    /**
     * 全局操作定制器，为所有管理员 API 接口自动添加 tongligong-student-cloud-token 请求头参数。
     * <p>
     * 在 Swagger UI 中测试管理员接口时，可以在此请求头中填入认证 token，
     * 无需手动为每个接口单独配置。
     * </p>
     *
     * @return OperationCustomizer 实例
     */
    public OperationCustomizer globalOperationCustomizer() {
        return (operation, handlerMethod) -> {
            // 为每个操作添加 tongligong-student-cloud-token header 参数
            operation.addParametersItem(new HeaderParameter()
                    .name("tongligong-student-cloud-token")
                    .description("token")
                    .required(true)
                    .schema(new StringSchema()));
            return operation;
        };
    }

    /**
     * 创建 OpenAPI 文档基本信息配置。
     * <p>
     * 包括文档标题、描述、服务条款链接、联系人信息、版本号和开源许可证等。
     * </p>
     *
     * @return OpenAPI 实例
     */
    @Bean
    public OpenAPI customOpenAPI() {
        // 构建联系人信息
        Contact contact = new Contact();
        contact.setName("学生云盘开发团队");

        return new OpenAPI()
                .info(new Info()
                        .title("学生云盘接口文档")
                        .description("# 学生云盘 RESTful 接口文档")
                        .contact(contact)
                        .version("1.0")
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://doc.xiaominfo.com")));
    }

}
