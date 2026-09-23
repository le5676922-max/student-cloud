package im.tlgxy.cloud.module.ai.model.knowledge;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Identifies the knowledge base available to the current account. */
@Getter
@RequiredArgsConstructor
public enum KnowledgeAudience {

    ADMIN("admin", "后台管理员"),
    USER("user", "平台用户");

    private final String key;

    private final String description;
}
