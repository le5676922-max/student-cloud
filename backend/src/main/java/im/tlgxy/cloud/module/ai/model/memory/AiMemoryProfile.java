package im.tlgxy.cloud.module.ai.model.memory;

import lombok.Data;

/** One bounded, versioned private memory document per account. */
@Data
public class AiMemoryProfile {
    private Integer ownerUserId;
    private Boolean enabled;
    private String entriesJson;
    private Long version;
}
