package im.tlgxy.cloud.module.ai.service;

import cn.dev33.satoken.annotation.SaCheckLogin;
import im.tlgxy.cloud.core.annotation.ApiLimit;
import im.tlgxy.cloud.module.ai.controller.AiMemoryController;
import im.tlgxy.cloud.module.ai.mapper.AiLongTermMemoryMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AiMemoryContractTest {
    @Test
    void privateEndpointsHaveLoginAndRateGuardsAndRequestsCannotChooseAnOwner() {
        assertNotNull(AiMemoryController.class.getAnnotation(SaCheckLogin.class));
        assertArrayEquals(new String[]{"/api/ai/memories"}, AiMemoryController.class.getAnnotation(RequestMapping.class).value());
        for (var method : AiMemoryController.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) assertNotNull(method.getAnnotation(ApiLimit.class));
        }
        assertEquals(1, AiMemoryController.Save.class.getRecordComponents().length);
        assertEquals("content", AiMemoryController.Save.class.getRecordComponents()[0].getName());
        assertEquals(1, AiMemoryController.Settings.class.getRecordComponents().length);
        assertEquals("enabled", AiMemoryController.Settings.class.getRecordComponents()[0].getName());
    }

    @Test
    void databaseStatementsUseAccountKeyAndAtomicVersionBarrier() throws Exception {
        String read = String.join(" ", AiLongTermMemoryMapper.class.getMethod("find", int.class).getAnnotation(Select.class).value());
        String write = String.join(" ", AiLongTermMemoryMapper.class.getMethod("compareAndSet", int.class, long.class, boolean.class, String.class)
                .getAnnotation(Update.class).value());
        String delete = String.join(" ", AiLongTermMemoryMapper.class.getMethod("deleteOwned", int.class).getAnnotation(Delete.class).value());
        for (String query : Arrays.asList(read, write, delete)) assertTrue(query.contains("WHERE owner_user_id = #{owner}"));
        assertTrue(write.contains("AND version = #{version}"));
        assertTrue(write.contains("version = version + 1"));
        assertFalse(write.contains("${"));
    }

    @Test
    void editEndpointReusesValidatedContentAndDoesNotAcceptAnOwnershipParameter() throws Exception {
        var update = AiMemoryController.class.getMethod("update", String.class, AiMemoryController.Update.class);
        assertArrayEquals(new String[]{"/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertNotNull(update.getAnnotation(ApiLimit.class));
        var parameters = update.getParameters();
        assertNotNull(parameters[0].getAnnotation(PathVariable.class));
        assertNotNull(parameters[1].getAnnotation(RequestBody.class));
        assertNotNull(parameters[1].getAnnotation(Valid.class));
        assertEquals(2, parameters.length);
        assertEquals(2, AiMemoryController.Update.class.getRecordComponents().length);
        assertEquals("content", AiMemoryController.Update.class.getRecordComponents()[0].getName());
        assertEquals("expectedContent", AiMemoryController.Update.class.getRecordComponents()[1].getName());
    }
}
