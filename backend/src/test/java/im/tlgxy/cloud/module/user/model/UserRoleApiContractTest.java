package im.tlgxy.cloud.module.user.model;

import im.tlgxy.cloud.module.user.model.entity.User;
import im.tlgxy.cloud.module.user.model.request.SaveUserRequest;
import im.tlgxy.cloud.module.user.model.response.UserDetailResponse;
import im.tlgxy.cloud.module.user.model.result.CheckLoginResult;
import im.tlgxy.cloud.module.user.model.result.LoginResult;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserRoleApiContractTest {
    @Test
    void apiDocumentationContainsOnlyAccountRoles() {
        for (Class<?> dto : List.of(SaveUserRequest.class, CheckLoginResult.class, LoginResult.class,
                UserDetailResponse.class, User.class)) {
            var result = new ModelConverters().resolveAsResolvedSchema(new AnnotatedType(dto));
            Schema<?> schema = (Schema<?>) result.schema.getProperties().get("role");
            assertNotNull(schema, dto.getSimpleName());
            assertEquals("string", schema.getType(), dto.getSimpleName());
            assertEquals(List.of("ADMIN", "TENANT_ADMIN", "USER"), schema.getEnum(), dto.getSimpleName());
        }
    }
}
