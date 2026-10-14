package im.tlgxy.cloud.module.share.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.share.model.dto.ShareEntryDTO;
import im.tlgxy.cloud.module.share.model.enums.ShareEntryTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharePathPolicyTest {
    @Test void normalizesRootRelativeUnicodeAndDuplicateSlashesWithoutTrimmingNames() {
        assertEquals("/", SharePathPolicy.normalize(null));
        assertEquals("/", SharePathPolicy.normalize(""));
        assertEquals("/允许/space name .txt", SharePathPolicy.normalize("//允许//space name .txt/"));
        assertEquals("/ selected .txt", SharePathPolicy.normalize(" selected .txt"));
        assertEquals("/report:2026.txt", SharePathPolicy.normalize("report:2026.txt"));
    }

    @Test void rejectsTraversalPlatformAndEncodedAliasesBeforeSigning() {
        for (String path : List.of("../file", "/a/../file", "/a/./file", "a\\file", "C:/file",
                "%2e%2e/file", "%252e%252e/file", "allowed%2fsecret.txt", "allowed%5csecret.txt", "a\u0000file")) {
            assertThrows(BizException.class, () -> SharePathPolicy.normalize(path), path);
        }
    }

    @Test void fileSelectionAllowsOnlyExactFileNotSiblingOrDescendant() {
        List<ShareEntryDTO> entries = List.of(entry("selected.txt", ShareEntryTypeEnum.FILE));
        assertDoesNotThrow(() -> SharePathPolicy.requireSelected(entries, "/selected.txt", false));
        assertThrows(BizException.class, () -> SharePathPolicy.requireSelected(entries, "/other.txt", false));
        assertThrows(BizException.class, () -> SharePathPolicy.requireSelected(entries, "/selected.txt/child", false));
        assertThrows(BizException.class, () -> SharePathPolicy.requireSelected(entries, "/", false));
        assertDoesNotThrow(() -> SharePathPolicy.requireSelected(entries, "/", true));
    }

    @Test void folderSelectionAllowsDescendantsButNotSiblingPrefix() {
        List<ShareEntryDTO> entries = List.of(entry("allowed", ShareEntryTypeEnum.FOLDER));
        assertDoesNotThrow(() -> SharePathPolicy.requireSelected(entries, "/allowed/sub/file.txt", false));
        assertDoesNotThrow(() -> SharePathPolicy.requireSelected(entries, "/allowed", true));
        assertThrows(BizException.class, () -> SharePathPolicy.requireSelected(entries, "/allowed-other/file.txt", false));
    }

    @Test void corruptedStoredSelectionsFailClosedWhileLegacyWholeSharesRemainSupported() {
        for (String corrupt : List.of("broken", "null", "[null]", "[{}]", "[{\"name\":\"../secret\",\"type\":\"FILE\"}]",
                "[{\"name\":\"allowed/child\",\"type\":\"FILE\"}]", "[{\"name\":\"ok\",\"type\":\"BAD\"}]")) {
            assertThrows(BizException.class, () -> ShareLinkFileService.parseShareEntries(corrupt), corrupt);
        }
        assertTrue(ShareLinkFileService.parseShareEntries(null).isEmpty());
        assertTrue(ShareLinkFileService.parseShareEntries("[]").isEmpty());
    }

    @Test void rootBoundaryChecksDoNotMatchSiblingPrefixes() {
        assertTrue(SharePathPolicy.isWithin("/allowed/file", "/allowed"));
        assertFalse(SharePathPolicy.isWithin("/allowed-other/file", "/allowed"));
    }

    private ShareEntryDTO entry(String name, ShareEntryTypeEnum type) {
        ShareEntryDTO entry = new ShareEntryDTO();
        entry.setName(name);
        entry.setType(type);
        return entry;
    }
}
