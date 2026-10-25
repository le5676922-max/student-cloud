package im.tlgxy.cloud.module.learning.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.learning.application.port.LearningFileAccess;
import im.tlgxy.cloud.module.learning.model.LearningMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningSearchTest {
    private final LearningFileAccess reader = mock(LearningFileAccess.class);
    private final LearningLibraryService library = new LearningLibraryService(null, null, reader, null, null, null);

    @Test
    void unrelatedMaterialsDoNotTriggerFileReads() {
        when(reader.fingerprint("local", "/1.txt")).thenReturn("current");
        var result = search(List.of(material(1, "needle"), material(2, "unrelated text")));
        assertEquals(List.of(1), result.citations().stream().map(c -> c.materialId()).toList());
        verify(reader).fingerprint("local", "/1.txt");
        verifyNoMoreInteractions(reader);
    }

    @Test void retrievesSegmentsBeyondOldChatLimitAndStillChecksLiveFingerprint() {
        when(reader.fingerprint("local", "/1.txt")).thenReturn("current");
        var result = search(List.of(material(1, "a".repeat(18000) + "needle")));
        assertFalse(result.citations().isEmpty());
        assertTrue(result.citations().getFirst().segment() > 20);
        assertTrue(result.citations().getFirst().excerpt().contains("needle"));
        when(reader.fingerprint("local", "/1.txt")).thenReturn("changed");
        assertTrue(search(List.of(material(1, "a".repeat(18000) + "needle"))).citations().isEmpty());
    }

    @Test
    void noMatchAndPunctuationOnlyQueriesDoNotReadFiles() {
        assertTrue(search(List.of(material(1, "unrelated"))).citations().isEmpty());
        assertTrue(library.searchCandidates(List.of(material(2, "needle")), Set.of()).citations().isEmpty());
        verifyNoInteractions(reader);
    }

    @Test
    void stopsAfterFiveVerifiedCitationsAndKeepsStableRanking() {
        when(reader.fingerprint(anyString(), anyString())).thenReturn("current");
        List<LearningMaterial> indexed = new ArrayList<>();
        for (int i = 30; i > 0; i--) indexed.add(material(i, "needle"));
        var result = search(indexed);
        assertEquals(List.of(1, 2, 3, 4, 5), result.citations().stream().map(c -> c.materialId()).toList());
        verify(reader, times(5)).fingerprint(anyString(), anyString());
        assertEquals(0, result.skipped());
    }

    @Test
    void invalidOrRevokedCandidatesAreSkippedAndReplaced() {
        when(reader.fingerprint(anyString(), anyString())).thenReturn("current");
        when(reader.fingerprint("local", "/1.txt")).thenReturn("changed");
        when(reader.fingerprint("local", "/2.txt")).thenThrow(new BizException("access revoked"));
        List<LearningMaterial> indexed = new ArrayList<>();
        for (int i = 1; i <= 7; i++) indexed.add(material(i, "needle"));
        var result = search(indexed);
        assertEquals(List.of(3, 4, 5, 6, 7), result.citations().stream().map(c -> c.materialId()).toList());
        assertEquals(2, result.skipped());
    }

    @Test
    void repeatedSegmentsAndCrossCourseReferencesShareOnlyRequestLocalFingerprint() {
        when(reader.fingerprint("local", "/1.txt")).thenReturn("current");
        var first = material(1, "needle ".repeat(400));
        var staleReference = material(2, "needle");
        staleReference.setPath("/1.txt");
        staleReference.setFingerprint("old");
        // Use a short first record to ensure the stale second-course reference is also checked.
        first.setContent("needle");
        assertEquals(1, search(List.of(first, staleReference)).skipped());
        verify(reader, times(1)).fingerprint("local", "/1.txt");
        first.setContent("needle ".repeat(400));
        assertEquals(5, search(List.of(first)).citations().size());
        verify(reader, times(2)).fingerprint("local", "/1.txt");
        when(reader.fingerprint("local", "/1.txt")).thenThrow(new BizException("access revoked"));
        assertTrue(search(List.of(first)).citations().isEmpty());
        verify(reader, times(3)).fingerprint("local", "/1.txt");
    }

    @Test
    void streamingHashMatchesExistingIndexAndEnforcesSizeLimit() throws Exception {
        byte[] content = new byte[LearningFileReader.MAX_BYTES];
        new Random(1).nextBytes(content);
        assertEquals(LearningLibraryService.fingerprint(content), LearningFileReader.fingerprint(new ByteArrayInputStream(content)));
        assertThrows(BizException.class, () -> LearningFileReader.fingerprint(new ByteArrayInputStream(new byte[content.length + 1])));
    }

    /** Opt-in local disk benchmark, not an HTTP/concurrency or external-model benchmark. */
    @Test
    @EnabledIfSystemProperty(named = "learning.benchmark", matches = "true")
    void benchmarkCandidateValidationAgainstFullScan(@TempDir Path directory) throws Exception {
        byte[] bytes = new byte[LearningFileReader.MAX_BYTES];
        new Random(1).nextBytes(bytes);
        String fingerprint = LearningLibraryService.fingerprint(bytes);
        List<LearningMaterial> indexed = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            Files.write(directory.resolve(i + ".txt"), bytes);
            var item = material(i, "needle");
            item.setFingerprint(fingerprint);
            indexed.add(item);
        }
        when(reader.fingerprint(eq("local"), anyString())).thenAnswer(invocation -> {
            try (var stream = Files.newInputStream(directory.resolve(invocation.<String>getArgument(1).substring(1)))) {
                return LearningFileReader.fingerprint(stream);
            }
        });
        long[] before = new long[7];
        long[] after = new long[7];
        for (int sample = -2; sample < 7; sample++) {
            long start = System.nanoTime();
            for (var item : indexed) assertEquals(fingerprint, LearningLibraryService.fingerprint(Files.readAllBytes(directory.resolve(item.getPath().substring(1)))));
            long oldNanos = System.nanoTime() - start;
            start = System.nanoTime();
            assertEquals(5, search(indexed).citations().size());
            long newNanos = System.nanoTime() - start;
            if (sample >= 0) { before[sample] = oldNanos; after[sample] = newNanos; }
        }
        Arrays.sort(before);
        Arrays.sort(after);
        System.out.printf(Locale.ROOT, "Learning disk verification benchmark (warm filesystem, 7 samples): fullScanMedian=%.2f ms, candidateMedian=%.2f ms; fileReads=30->5; bytes=150->25 MiB%n", before[3] / 1_000_000.0, after[3] / 1_000_000.0);
    }

    private LearningLibraryService.SearchResult search(List<LearningMaterial> materials) {
        return library.searchCandidates(materials, Set.of("needle"));
    }

    private static LearningMaterial material(int id, String content) {
        LearningMaterial item = new LearningMaterial();
        item.setId(id);
        item.setCourseId(1);
        item.setStorageKey("local");
        item.setPath("/" + id + ".txt");
        item.setName(id + ".txt");
        item.setTags("");
        item.setContent(content);
        item.setFingerprint("current");
        return item;
    }
}
