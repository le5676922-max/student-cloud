package im.tlgxy.cloud.module.user.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRoleEnum {

    ADMIN("ADMIN", "系统管理员"),
    USER("USER", "普通用户"),
    GUEST("GUEST", "访客");

    @EnumValue
    @JsonValue
    private final String value;

    private final String description;

    public boolean isAdmin() {
        return this == ADMIN;
    }

    /** Reject removed/unknown roles instead of silently converting them to an omitted role. */
    public static class Deserializer extends com.fasterxml.jackson.databind.JsonDeserializer<UserRoleEnum> {
        @Override
        public UserRoleEnum deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            String value = parser.getText();
            try {
                return UserRoleEnum.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw context.weirdStringException(value, UserRoleEnum.class, "角色只允许 ADMIN、USER、GUEST");
            }
        }
    }
}
