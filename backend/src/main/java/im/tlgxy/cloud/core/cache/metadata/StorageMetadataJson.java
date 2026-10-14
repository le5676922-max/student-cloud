package im.tlgxy.cloud.core.cache.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.storage.model.enums.SearchModeEnum;
import im.tlgxy.cloud.module.storage.model.enums.StorageTypeEnum;

import java.util.List;

/** Explicit allowlist; no generic entity serialization, Java serialization or polymorphic type hints. */
final class StorageMetadataJson {
    private static final int SCHEMA = 1;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private StorageMetadataJson() { }

    static String encode(List<StorageSource> sources) throws JsonProcessingException {
        return JSON.writeValueAsString(new Payload(SCHEMA, capture(sources)));
    }

    static List<StorageSource> decode(String json) throws JsonProcessingException {
        Payload payload = JSON.readValue(json, Payload.class);
        if (payload.schema() != SCHEMA || payload.items() == null) {
            throw new IllegalArgumentException("Unsupported storage metadata schema");
        }
        return restore(payload.items());
    }

    static List<Metadata> capture(List<StorageSource> sources) {
        return sources.stream().map(Metadata::from).toList();
    }

    static List<StorageSource> restore(List<Metadata> items) {
        return items.stream().map(Metadata::toSource).toList();
    }

    private record Payload(int schema, List<Metadata> items) { }

    record Metadata(Integer id, Boolean enable, Boolean enableFileOperator, Boolean enableFileAnnoOperator,
                    Boolean enableCache, String name, String key, String remark, Boolean autoRefreshCache,
                    String type, Boolean searchEnable, Boolean searchIgnoreCase, String searchMode,
                    Integer orderNum, Boolean defaultSwitchToImgMode, Boolean compatibilityReadme,
                    Integer tenantId, Integer ownerUserId) {
        @SuppressWarnings("deprecation")
        static Metadata from(StorageSource value) {
            return new Metadata(value.getId(), value.getEnable(), value.getEnableFileOperator(),
                    value.getEnableFileAnnoOperator(), value.getEnableCache(), value.getName(), value.getKey(),
                    value.getRemark(), value.getAutoRefreshCache(), value.getType() == null ? null : value.getType().name(),
                    value.getSearchEnable(), value.getSearchIgnoreCase(),
                    value.getSearchMode() == null ? null : value.getSearchMode().name(), value.getOrderNum(),
                    value.getDefaultSwitchToImgMode(), value.getCompatibilityReadme(), value.getTenantId(), value.getOwnerUserId());
        }

        @SuppressWarnings("deprecation")
        StorageSource toSource() {
            StorageSource value = new StorageSource();
            value.setId(id);
            value.setEnable(enable);
            value.setEnableFileOperator(enableFileOperator);
            value.setEnableFileAnnoOperator(enableFileAnnoOperator);
            value.setEnableCache(enableCache);
            value.setName(name);
            value.setKey(key);
            value.setRemark(remark);
            value.setAutoRefreshCache(autoRefreshCache);
            value.setType(type == null ? null : StorageTypeEnum.valueOf(type));
            value.setSearchEnable(searchEnable);
            value.setSearchIgnoreCase(searchIgnoreCase);
            value.setSearchMode(searchMode == null ? null : SearchModeEnum.valueOf(searchMode));
            value.setOrderNum(orderNum);
            value.setDefaultSwitchToImgMode(defaultSwitchToImgMode);
            value.setCompatibilityReadme(compatibilityReadme);
            value.setTenantId(tenantId);
            value.setOwnerUserId(ownerUserId);
            return value;
        }
    }
}
