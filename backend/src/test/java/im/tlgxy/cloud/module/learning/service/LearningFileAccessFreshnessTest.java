package im.tlgxy.cloud.module.learning.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.learning.application.port.LearningFileAccess;
import im.tlgxy.cloud.module.learning.model.LearningMaterial;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the application through its own port, without constructing any storage or AI implementation. */
class LearningFileAccessFreshnessTest {
    @Test void coalescesChecksWithinOneSearchButRechecksAuthorizationForTheNextSearch() {
        var access = new ChangingAccess();
        var library = new LearningLibraryService(null, null, access, null, null, null);
        var material = material();

        assertEquals(3, library.searchCandidates(List.of(material), Set.of("needle")).citations().size());
        assertEquals(1, access.checks);

        access.revoked = true;
        var afterRevocation = library.searchCandidates(List.of(material), Set.of("needle"));
        assertTrue(afterRevocation.citations().isEmpty());
        assertEquals(1, afterRevocation.skipped());
        assertEquals(2, access.checks, "No authorization or fingerprint result may escape a single search request");
    }

    @Test void changedSourceHashIsNotAcceptedEvenWhenThePortStillGrantsRead() {
        var access = new ChangingAccess();
        var library = new LearningLibraryService(null, null, access, null, null, null);
        var material = material();
        assertFalse(library.searchCandidates(List.of(material), Set.of("needle")).citations().isEmpty());

        access.fingerprint = "changed";
        assertTrue(library.searchCandidates(List.of(material), Set.of("needle")).citations().isEmpty());
        assertEquals(2, access.checks);
    }

    private static LearningMaterial material() {
        var material = new LearningMaterial();
        material.setId(1); material.setCourseId(1); material.setStorageKey("local");
        material.setPath("/notes.txt"); material.setName("notes.txt"); material.setTags("");
        material.setContent("needle ".repeat(200)); material.setFingerprint("current");
        return material;
    }

    private static final class ChangingAccess implements LearningFileAccess {
        int checks;
        boolean revoked;
        String fingerprint = "current";

        @Override public byte[] read(String storageKey, String requestedPath) {
            throw new AssertionError("Retrieval must not reparse or upload the document");
        }

        @Override public String fingerprint(String storageKey, String requestedPath) {
            assertEquals("local", storageKey); assertEquals("/notes.txt", requestedPath);
            checks++;
            if (revoked) throw new BizException("权限已收回");
            return fingerprint;
        }
    }
}
