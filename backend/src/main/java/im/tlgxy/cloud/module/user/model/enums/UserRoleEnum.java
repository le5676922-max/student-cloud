package im.tlgxy.cloud.module.user.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(type = "string", implementation = String.class, allowableValues = {"ADMIN", "TENANT_ADMIN", "USER"})
public enum UserRoleEnum {

    ADMIN("ADMIN", "全局管理员"),
    TENANT_ADMIN("TENANT_ADMIN", "工作空间管理员"),
    USER("USER", "普通用户"),
    /** Read compatibility for retired database rows; never accepted by account APIs. */
    @Deprecated
    GUEST("GUEST", "历史停用记录");

    @EnumValue
    @JsonValue
    private final String value;

    private final String description;

    public boolean isAdmin() {
        return this == ADMIN;
    }

    public boolean isLoginRole() {
        return this == ADMIN || this == TENANT_ADMIN || this == USER;
    }

    /** Reject removed/unknown roles instead of silently converting them to an omitted role. */
    public static class Deserializer extends com.fasterxml.jackson.databind.JsonDeserializer<UserRoleEnum> {
        @Override
        public UserRoleEnum deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            String value = parser.getText();
            try {
                UserRoleEnum role = UserRoleEnum.valueOf(value);
                if (role.isLoginRole()) {
                    return role;
                }
            } catch (IllegalArgumentException exception) {
                // Use the same response for retired and unknown values.
            }
            throw context.weirdStringException(value, UserRoleEnum.class, "角色只允许 ADMIN、TENANT_ADMIN、USER");
        }
    }
}
