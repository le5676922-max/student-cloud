package im.tlgxy.cloud.module.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource("classpath:learning-embedding.properties")
@EnableConfigurationProperties(LearningEmbeddingProperties.class)
public class LearningEmbeddingConfiguration { }
