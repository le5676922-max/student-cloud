package im.tlgxy.cloud.core.exception;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.core.exception.biz.StorageSourceIllegalOperationBizException;
import im.tlgxy.cloud.module.storage.model.enums.FileOperatorTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated HTTP contract checks; no application, database, account or external API is started. */
class GlobalExceptionHandlerClientErrorTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ClientErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void ordinaryBusinessRejectionIs400AndPreservesItsCodeAndMessage() throws Exception {
        mvc.perform(get("/handler-test/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BIZ_BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.msg").value("页码须为1至10000，每页条数须为1至100"));
    }

    @Test
    void plainBusinessMessageKeepsTheExistingJsonCodeFallbackButNotHttp500() throws Exception {
        mvc.perform(get("/handler-test/plain-business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.SYSTEM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value("工作空间不存在或已停用"));
    }

    @Test
    void malformedTypedQueryIs400WithoutEchoingItsRawValueOrConversionDetails() throws Exception {
        String rawValue = "private-input-not-an-integer<script>";
        mvc.perform(get("/handler-test/typed").param("page", rawValue))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("请求参数格式不正确，请检查页码、数量和筛选条件"))
                .andExpect(content().string(not(containsString(rawValue))))
                .andExpect(content().string(not(containsString("private-input-not-an-integer"))))
                .andExpect(content().string(not(containsString("NumberFormatException"))))
                .andExpect(content().string(not(containsString("Failed to convert"))));
    }

    @Test
    void genuineUnclassifiedCheckedExceptionRemains500WithTheExistingSafeMessage() throws Exception {
        mvc.perform(get("/handler-test/unclassified"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.SYSTEM_ERROR.getCode()))
                .andExpect(jsonPath("$.msg").value("系统异常, 请联系管理员"))
                .andExpect(content().string(not(containsString("internal-test-failure"))));
    }

    @Test
    void genuineUnclassifiedRuntimeFailureAlsoRemains500() throws Exception {
        mvc.perform(get("/handler-test/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.SYSTEM_ERROR.getCode()));
    }

    @Test
    void unauthorizedFileOperationIs403InsteadOfAServerFailure() throws Exception {
        mvc.perform(get("/handler-test/file-operation"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.BIZ_STORAGE_SOURCE_ILLEGAL_OPERATION.getCode()));
    }

    @RestController
    static class ClientErrorController {
        @GetMapping("/handler-test/file-operation")
        public String fileOperation() {
            throw new StorageSourceIllegalOperationBizException(999, FileOperatorTypeEnum.UPLOAD);
        }
        @GetMapping("/handler-test/business")
        public String business() {
            throw new BizException(ErrorCode.BIZ_BAD_REQUEST.getCode(), "页码须为1至10000，每页条数须为1至100");
        }

        @GetMapping("/handler-test/plain-business")
        public String plainBusiness() {
            throw new BizException("工作空间不存在或已停用");
        }

        @GetMapping("/handler-test/typed")
        public int typed(@RequestParam(name = "page") int page) {
            return page;
        }

        @GetMapping("/handler-test/unclassified")
        public String unclassified() throws Exception {
            throw new Exception("internal-test-failure");
        }

        @GetMapping("/handler-test/runtime")
        public String runtime() {
            throw new IllegalStateException("test runtime failure");
        }
    }
}
