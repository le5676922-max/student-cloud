package im.tlgxy.cloud.module.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/** 加载 RAG 独立配置文件。 */
@Configuration
@PropertySource("classpath:ai-rag.properties")
public class AiRagConfiguration {
}
