package im.tlgxy.cloud.module.storage.context;

import im.tlgxy.cloud.module.storage.model.dto.StorageSourceInitDTO;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.entity.StorageSourceConfig;
import im.tlgxy.cloud.module.storage.service.StorageSourceConfigService;
import im.tlgxy.cloud.module.storage.service.StorageSourceService;
import im.tlgxy.cloud.module.storage.service.base.AbstractBaseFileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 存储源初始化器。
 *
 * 在 Spring 容器初始化完成后，自动执行存储源的初始化工作。
 * 实现了 {@link ApplicationContextAware} 接口，在 Spring 上下文设置完成后触发初始化逻辑。
 *
 * 初始化流程：
 * 1. 从 ApplicationContext 中获取所有 {@link AbstractBaseFileService} 的实现类 Bean；
 * 2. 调用 {@link StorageSourceContext#load(Map)} 注册所有存储源类型；
 * 3. 从数据库读取所有已配置的存储源及其参数；
 * 4. 逐个调用 {@link StorageSourceContext#init(StorageSourceInitDTO)} 初始化每个存储源。
 *
 * 通过 {@link Order} 注解（值为 100）控制初始化顺序，确保在其他依赖存储源的 Bean 之前完成初始化。
 * 通过 {@link DependsOn} 注解确保在 storageSourceService Bean 之后初始化。
 *
 * @author zhaojun
 */
@Slf4j
@Component
@Order(100)
@DependsOn(value = {"storageSourceService"})
public class StorageSourceInitializer implements ApplicationContextAware {

    /**
     * 存储源服务，用于从数据库查询存储源配置。
     */
    @Resource
    private StorageSourceService storageSourceService;

    /**
     * 存储源配置服务，用于从数据库读取存储源的具体参数配置。
     */
    @Resource
    private StorageSourceConfigService storageSourceConfigService;

    /**
     * Spring ApplicationContext 设置完成后的回调方法。
     *
     * 在此方法中执行所有存储源的初始化工作：
     * 1. 收集所有存储源 Service 类型并注册到 {@link StorageSourceContext}；
     * 2. 从数据库读取已配置的存储源，逐一进行初始化。
     *
     * 每个存储源的初始化失败不会影响其他存储源，错误会被记录到日志中。
     *
     * @param   applicationContext
     *          Spring 应用上下文，用于获取所有存储源 Service 类型的 Bean
     *
     * @throws  BeansException
     *          当获取 Bean 过程中发生异常时抛出
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        try {
            // 第一步：从 Spring 容器中获取所有 AbstractBaseFileService 类型的 Bean
            Map<String, AbstractBaseFileService> abstractBaseFileServiceMap = applicationContext.getBeansOfType(AbstractBaseFileService.class);
            // 注册到存储源上下文，建立枚举类型与 Service 的映射
            StorageSourceContext.load(abstractBaseFileServiceMap);
        } catch (Exception e) {
            log.error("初始化存储源 Bean 失败.", e);
            return;
        }

        // 第二步：从数据库读取所有已配置的存储源，并按排序值升序排列
        List<StorageSource> list = storageSourceService.findAllOrderByOrderNum();
        for (StorageSource storageSource : list) {
            try {
                // 读取存储源的具体配置参数
                List<StorageSourceConfig> storageSourceConfigList = storageSourceConfigService.selectStorageConfigByStorageId(storageSource.getId());
                // 转换为初始化 DTO
                StorageSourceInitDTO storageSourceInitDTO = StorageSourceInitDTO.convert(storageSource, storageSourceConfigList);
                // 执行初始化（注入参数、测试连接、注册到上下文）
                StorageSourceContext.init(storageSourceInitDTO);
                log.info("启动时初始化存储源成功, 存储源 id: [{}], 存储源类型: [{}], 存储源名称: [{}]",
                        storageSource.getId(), storageSource.getType().getDescription(), storageSource.getName());
            } catch (Exception e) {
                // 单个存储源初始化失败不影响整体流程，记录错误日志后继续处理下一个
                log.error("启动时初始化存储源失败, 存储源 id: {}, 存储源类型: {}, 存储源名称: {}",
                        storageSource.getId(), storageSource.getType().getDescription(), storageSource.getName(), e);
            }
        }
    }

}
