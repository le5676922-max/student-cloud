package im.tlgxy.cloud.module.share.model.request;

import im.tlgxy.cloud.module.share.model.dto.ShareEntryDTO;
import im.tlgxy.cloud.module.share.model.enums.ShareEntryTypeEnum;
import im.tlgxy.cloud.module.share.model.enums.ShareTypeEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateShareLinkRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initializeValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1", "1234", "12345678", "12 3456", "a&b+?#"})
    void optionalPasswordsWithinDatabaseLimitRemainValid(String password) {
        CreateShareLinkRequest request = validRequest();
        request.setPassword(password);
        assertTrue(validator.validate(request).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789", "1234567890123456789"})
    void oversizedPasswordsAreRejectedBeforeDatabaseInsertion(String password) {
        CreateShareLinkRequest request = validRequest();
        request.setPassword(password);
        var violations = validator.validate(request);
        assertEquals(1, violations.size());
        var violation = violations.iterator().next();
        assertEquals("password", violation.getPropertyPath().toString());
        assertEquals("分享密码最多 8 个字符", violation.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "\t", ".", "..", "../report.txt", "folder/report.txt",
            "folder\\report.txt", "/report.txt", "\\report.txt", "a\nb", "a\rb", "a\u0000b", "a\u007fb", "a\u0085b"})
    void invalidNestedNamesAreRejected(String name) {
        CreateShareLinkRequest request = validRequest();
        request.getShareEntries().get(0).setName(name);
        var violations = validator.validate(request);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(violation ->
                violation.getPropertyPath().toString().equals("shareEntries[0].name")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", ".env", "课程资料.docx", " a b .txt", "README", "a..b", "..."})
    void legalNamesAreAcceptedWithoutTrimmingOrRenaming(String name) {
        CreateShareLinkRequest request = validRequest();
        request.getShareEntries().get(0).setName(name);
        assertTrue(validator.validate(request).isEmpty());
        assertEquals(name, request.getShareEntries().get(0).getName());
    }

    @Test
    void nestedEntryRequiresItsType() {
        CreateShareLinkRequest request = validRequest();
        request.getShareEntries().get(0).setType(null);
        var violations = validator.validate(request);
        assertEquals(1, violations.size());
        assertEquals("shareEntries[0].type", violations.iterator().next().getPropertyPath().toString());
        assertEquals("分享条目类型不能为空", violations.iterator().next().getMessage());
    }

    @Test
    void nullListElementIsRejectedInsteadOfReachingShareService() {
        CreateShareLinkRequest request = validRequest();
        request.setShareEntries(Arrays.asList((ShareEntryDTO) null));
        var violations = validator.validate(request);
        assertEquals(1, violations.size());
        assertEquals("分享条目不能为 null", violations.iterator().next().getMessage());
    }

    @Test
    void missingOrEmptyEntryListsAreRejected() {
        CreateShareLinkRequest request = validRequest();
        request.setShareEntries(null);
        assertFalse(validator.validate(request).isEmpty());
        request.setShareEntries(List.of());
        assertFalse(validator.validate(request).isEmpty());
    }

    private CreateShareLinkRequest validRequest() {
        CreateShareLinkRequest request = new CreateShareLinkRequest();
        request.setStorageKey("workspace-2");
        request.setSharePath("/");
        request.setShareEntries(List.of(new ShareEntryDTO("report.pdf", ShareEntryTypeEnum.FILE)));
        request.setShareType(ShareTypeEnum.FILE);
        return request;
    }
}
