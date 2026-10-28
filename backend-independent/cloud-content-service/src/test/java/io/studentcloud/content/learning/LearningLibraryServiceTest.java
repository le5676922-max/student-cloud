package io.studentcloud.content.learning;

import io.studentcloud.account.AccountDirectory;
import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.application.MountService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class LearningLibraryServiceTest {
    @TempDir Path temporary;
    private final Actor owner = new Actor(3, "a3", "owner", null, Role.USER, 1L);
    private final Actor stranger = new Actor(4, "a4", "stranger", null, Role.USER, 1L);
    private JdbcTemplate jdbc;
    private MountService mounts;
    private LearningLibraryService library;
    private Path files;

    @BeforeEach void setup() throws Exception {
        Path managed = Files.createDirectory(temporary.resolve("managed"));
        Path mounted = Files.createDirectory(managed.resolve("one"));
        files = Files.createDirectories(mounted.resolve("members/u3"));
        Files.writeString(files.resolve("notes.txt"), "transaction isolation and atomic commit");
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:learning_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql"),
                new ClassPathResource("db/independent-learning-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        AccountDirectory accounts = new AccountDirectory() {
            public Actor account(long id) { return id == 1 ? new Actor(1, "admin", "admin", null, Role.ADMIN, null) : id == owner.id() ? owner : stranger; }
            public boolean workspaceEnabled(long id) { return true; }
        };
        var tx = new DataSourceTransactionManager(database);
        mounts = new MountService(jdbc, tx, Clock.systemUTC(), managed, accounts);
        var mount = mounts.createLocal(new Actor(1, "admin", "admin", null, Role.ADMIN, null), "Drive", "local-drive", mounted, 1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read',TRUE,1,1)", mount.id());
        library = new LearningLibraryService(jdbc, tx, Clock.systemUTC(), accounts, mounts);
    }

    @Test void ownerCanIndexAndSearchOnlyOwnCurrentReadableFile() {
        var course = library.createCourse(owner, "Database", "Autumn");
        var material = library.addMaterial(owner, course.id(), "local-drive", "/notes.txt", "exam");
        assertEquals("transaction isolation and atomic commit".length(), material.coveredChars());
        assertFalse(material.indexedAt().isBlank());
        assertEquals(1, library.listMaterials(owner, course.id()).size());
        var search = library.search(owner, course.id(), "atomic", 8);
        assertEquals(1, search.citations().size());
        assertTrue(search.citations().getFirst().excerpt().contains("atomic"));
        assertThrows(ApplicationFault.class, () -> library.listMaterials(stranger, course.id()));
        assertThrows(ApplicationFault.class, () -> library.refresh(stranger, material.id()));
        assertThrows(ApplicationFault.class, () -> library.search(stranger, course.id(), "atomic", 8));
    }
    @Test void naturalQuestionsMatchRelevantChineseTermsInsteadOfRequiringAnExactSentence() throws Exception {
        Files.writeString(files.resolve("notes.txt"), "课程安排\n数据库课程阅读通知。\n" + "本周按时提交课堂练习。\n".repeat(150)
                + "数据库事务具有原子性；隔离级别包括读未提交、读已提交、可重复读和串行化。");
        var course=library.createCourse(owner,"数据库",null);
        library.addMaterial(owner,course.id(),"local-drive","/notes.txt",null);
        var result = library.search(owner, course.id(), "请解释数据库事务的隔离级别有哪些？", 5);
        assertEquals(1, result.citations().size());
        assertTrue(result.citations().getFirst().excerpt().contains("可重复读和串行化"));
        assertFalse(result.citations().getFirst().excerpt().contains("课程安排"));
        assertTrue(result.citations().getFirst().segment() > 1);
        assertTrue(result.citations().getFirst().excerpt().length() <= 500);
        assertTrue(library.search(owner,course.id(),"如何制作蛋糕",5).citations().isEmpty());
    }

    @Test void relevanceRanksOlderCompleteEvidenceAheadOfANewerPartialMatch() throws Exception {
        var course = library.createCourse(owner, "Database", "Autumn");
        var strong = archive(course.id(), "strong.md", "# Revision\n" + "General preparation. ".repeat(100)
                + "Transaction isolation makes concurrent work safe; atomic commit keeps changes consistent.", "");
        var weak = archive(course.id(), "weak.md", "# Overview\nTransaction and isolation are prerequisites for the next chapter.", "");

        var result = library.search(owner, course.id(), "Please explain transaction isolation atomic commit", 2);
        assertEquals(java.util.List.of(strong.id(), weak.id()), result.citations().stream().map(LearningLibraryService.Citation::materialId).toList());
        assertFalse(result.truncated());
        assertTrue(result.citations().getFirst().excerpt().contains("atomic commit"));
        assertFalse(result.citations().getFirst().excerpt().contains("# Revision"));
        var limited = library.search(owner, course.id(), "Please explain transaction isolation atomic commit", 1);
        assertEquals(strong.id(), limited.citations().getFirst().materialId());
        assertTrue(limited.truncated());
        assertEquals(0, limited.skipped());
    }

    @Test void acceptsLongNaturalQuestionsWithinTwoThousandCharactersAndRejectsLargerQueries() {
        var course = library.createCourse(owner, "Database", "Autumn");
        library.addMaterial(owner, course.id(), "local-drive", "/notes.txt", "");
        String question = "Please explain transaction isolation and atomic commit, including how these ideas work together "
                + "when multiple users change shared data. I am preparing for an examination and would like an explanation "
                + "that connects the guarantees, common failure cases, and practical examples to the material in my course.";
        assertTrue(question.length() > 200 && question.length() <= 2000);
        assertEquals(1, library.search(owner, course.id(), question, 8).citations().size());
        assertThrows(ApplicationFault.class, () -> library.search(owner, course.id(), "x".repeat(2001), 8));
    }

    @Test void titlesFilenamesAndTagsContributeToRelevanceWithoutRequiringBodyMatches() throws Exception {
        var course = library.createCourse(owner, "Database", "Autumn");
        var heading = archive(course.id(), "chapter.md", "# Transaction isolation\nRead the assigned examples.", "");
        var filename = archive(course.id(), "transaction isolation.md", "# Revision checklist\nRead the assigned chapter before the exercise.", "");
        var tagged = archive(course.id(), "reading.md", "# Revision checklist\nRead the assigned chapter before the exercise.", "transaction isolation");
        var body = archive(course.id(), "handout.md", "# Preparation\n" + "Read the introduction. ".repeat(50) + "Transaction isolation.", "");
        archive(course.id(), "irrelevant.md", "This island concerns transactional isolationism.", "");

        var result = library.search(owner, course.id(), "transaction isolation", 8);
        assertEquals(java.util.List.of(heading.id(), filename.id(), body.id(), tagged.id()),
                result.citations().stream().map(LearningLibraryService.Citation::materialId).toList());
        assertEquals(result.citations(), library.search(owner, course.id(), "transaction isolation", 8).citations());
        assertFalse(result.truncated());
        assertEquals(0, result.skipped());
    }

    @Test void tiesAreDeterministicAndAnUnrelatedTailDoesNotTruncateOneCompleteResult() throws Exception {
        var course = library.createCourse(owner, "Database", "Autumn");
        archive(course.id(), "unrelated.md", "unrelated calendar reminders", "");
        var first = archive(course.id(), "first.md", "# Notes\nAtomic operations.", "");
        var complete = library.search(owner, course.id(), "atomic", 1);
        assertEquals(first.id(), complete.citations().getFirst().materialId());
        assertFalse(complete.truncated());
        var second = archive(course.id(), "second.md", "# Notes\nAtomic operations.", "");
        assertEquals(java.util.List.of(second.id(), first.id()), library.search(owner, course.id(), "atomic", 8)
                .citations().stream().map(LearningLibraryService.Citation::materialId).toList());
        assertTrue(library.search(owner, course.id(), "please explain what is the", 8).citations().isEmpty());
    }

    @Test void rankingKeepsCourseAndOwnerScopeEvenWhenOtherSourcesAreStronger() throws Exception {
        var selected = library.createCourse(owner, "Selected", "Autumn");
        var outside = library.createCourse(owner, "Outside", "Autumn");
        var selectedMaterial = archive(selected.id(), "selected.md", "# Notes\nAtomic operations.", "");
        var outsideMaterial = archive(outside.id(), "atomic.md", "# Atomic\nAtomic operations.", "atomic");
        var privateCourse = library.createCourse(stranger, "Private", "Autumn");
        Path privateFiles = Files.createDirectory(files.getParent().resolve("u4"));
        Files.writeString(privateFiles.resolve("private.md"), "# Atomic\nPrivate atomic evidence.");
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) SELECT 4,id,'/members/u4','list,read',TRUE,1,1 FROM sc_drive_mount WHERE mount_key='local-drive'");
        var privateMaterial = library.addMaterial(stranger, privateCourse.id(), "local-drive", "/private.md", "atomic");

        assertEquals(java.util.List.of(selectedMaterial.id()), library.search(owner, selected.id(), "atomic", 8)
                .citations().stream().map(LearningLibraryService.Citation::materialId).toList());
        assertEquals(java.util.Set.of(selectedMaterial.id(), outsideMaterial.id()), library.search(owner, null, "atomic", 8)
                .citations().stream().map(LearningLibraryService.Citation::materialId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.List.of(privateMaterial.id()), library.search(stranger, null, "atomic", 8)
                .citations().stream().map(LearningLibraryService.Citation::materialId).toList());
    }

    @Test void metadataOnlyMatchesStillRequireFreshDigestAndCurrentReadPermission() throws Exception {
        var course = library.createCourse(owner, "Database", "Autumn");
        var material = archive(course.id(), "atomic notes.md", "original indexed evidence", "atomic");
        Path source = files.resolve("atomic notes.md");
        FileTime timestamp = Files.getLastModifiedTime(source);
        Files.writeString(source, "x".repeat("original indexed evidence".length()));
        Files.setLastModifiedTime(source, timestamp);
        var changed = library.search(owner, course.id(), "atomic", 8);
        assertTrue(changed.citations().isEmpty());
        assertEquals(1, changed.skipped());

        library.refresh(owner, material.id());
        assertEquals(1, library.search(owner, course.id(), "atomic", 8).citations().size());
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE WHERE user_id=3");
        var revoked = library.search(owner, course.id(), "atomic", 8);
        assertTrue(revoked.citations().isEmpty());
        assertEquals(1, revoked.skipped());
    }

    @Test void changedHighRankedSourcesAreSkippedBeforeReturningVerifiedLowerRankedEvidence() throws Exception {
        var course = library.createCourse(owner, "Database", "Autumn");
        archive(course.id(), "strong.md", "# Transaction isolation atomic commit\nDetailed evidence.", "");
        var fallback = archive(course.id(), "fallback.md", "# Notes\nTransaction and isolation prerequisites.", "");
        Files.writeString(files.resolve("strong.md"), "replaced private material");
        var result = library.search(owner, course.id(), "transaction isolation atomic commit", 1);
        assertEquals(java.util.List.of(fallback.id()), result.citations().stream().map(LearningLibraryService.Citation::materialId).toList());
        assertEquals(1, result.skipped());
        assertFalse(result.truncated());
    }

    @Test void revokedDriveGrantOrChangedFileNeverReturnsIndexedExcerpt() throws Exception {
        var course = library.createCourse(owner, "Database", "Autumn");
        library.addMaterial(owner, course.id(), "local-drive", "/notes.txt", "exam");
        Files.writeString(files.resolve("notes.txt"), "changed private file content");
        assertEquals(1, library.search(owner, course.id(), "atomic", 8).skipped());
        assertEquals(0, library.search(owner, course.id(), "atomic", 8).citations().size());
        jdbc.update("UPDATE sc_drive_grant SET enabled=FALSE WHERE user_id=3");
        assertEquals(1, library.search(owner, course.id(), "atomic", 8).skipped());
        assertThrows(ApplicationFault.class, () -> library.addMaterial(owner, course.id(), "local-drive", "/notes.txt", ""));
    }

    @Test void relinkReindexesWithoutMovingOriginalAndDeletionKeepsDriveFile() throws Exception {
        var course = library.createCourse(owner, "Database", "Autumn");
        var material = library.addMaterial(owner, course.id(), "local-drive", "/notes.txt", "exam");
        Files.writeString(files.resolve("other.md"), "fresh material");
        library.relink(owner, material.id(), "local-drive", "/other.md");
        assertEquals(1, library.search(owner, course.id(), "fresh", 8).citations().size());
        library.deleteMaterial(owner, material.id());
        assertEquals(0, library.listMaterials(owner, course.id()).size());
        assertTrue(Files.exists(files.resolve("notes.txt")));
        assertTrue(Files.exists(files.resolve("other.md")));
    }

    @Test void courseUpdatesPersistAndDeleteCascadesOnlyIndexes() {
        var course = library.createCourse(owner, "Before", "Spring");
        library.addMaterial(owner, course.id(), "local-drive", "/notes.txt", "exam");
        library.updateCourse(owner, course.id(), "After", "Autumn");
        assertEquals("After", library.listCourses(owner).getFirst().name());
        assertTrue(library.listCourses(stranger).isEmpty());
        library.deleteCourse(owner, course.id());
        assertTrue(library.listCourses(owner).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sc_learning_material", Integer.class));
        assertTrue(Files.exists(files.resolve("notes.txt")));
    }

    @Test void longTextHasHonestCoverageAndRefreshReplacesStaleIndex() throws Exception {
        var course = library.createCourse(owner, "Long", "Autumn");
        Files.writeString(files.resolve("long.md"), "a".repeat(150_000));
        var material = library.addMaterial(owner, course.id(), "local-drive", "/long.md", "");
        assertTrue(material.truncated());
        assertEquals(100_000, material.coveredChars());
        Files.writeString(files.resolve("long.md"), "refreshed text");
        library.refresh(owner, material.id());
        assertEquals(1, library.search(owner, course.id(), "refreshed", 8).citations().size());
    }

    @Test void rejectsTraversalUnsupportedAndOversizedDocuments() throws Exception {
        var course = library.createCourse(owner, "Limits", "Autumn");
        Files.writeString(files.resolve("script.html"), "private");
        assertThrows(ApplicationFault.class, () -> library.addMaterial(owner, course.id(), "local-drive", "/../notes.txt", ""));
        assertThrows(ApplicationFault.class, () -> library.addMaterial(owner, course.id(), "local-drive", "/script.html", ""));
        Files.write(files.resolve("large.txt"), new byte[10 * 1024 * 1024 + 1]);
        assertThrows(ApplicationFault.class, () -> library.addMaterial(owner, course.id(), "local-drive", "/large.txt", ""));
    }

    @Test void contentDigestRejectsSameSizeSameTimestampReplacement() throws Exception {
        var course = library.createCourse(owner, "Freshness", "Autumn");
        library.addMaterial(owner, course.id(), "local-drive", "/notes.txt", "");
        FileTime timestamp = Files.getLastModifiedTime(files.resolve("notes.txt"));
        Files.writeString(files.resolve("notes.txt"), "x".repeat(36));
        Files.setLastModifiedTime(files.resolve("notes.txt"), timestamp);
        assertTrue(library.search(owner, course.id(), "atomic", 8).citations().isEmpty());
    }

    @Test void pdfAndDocxTextExtractLocallyAndPdfPageBudgetIsReported() throws Exception {
        var course = library.createCourse(owner, "Documents", "Autumn");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700); stream.showText("database evidence"); stream.endText();
            }
            for (int i = 1; i < 101; i++) pdf.addPage(new PDPage());
            pdf.save(files.resolve("report.pdf").toFile());
        }
        var pdfMaterial = library.addMaterial(owner, course.id(), "local-drive", "/report.pdf", "");
        assertEquals(101, pdfMaterial.totalPages());
        assertEquals(100, pdfMaterial.indexedPages());
        assertTrue(pdfMaterial.truncated());
        try (var document = new XWPFDocument(); var output = Files.newOutputStream(files.resolve("report.docx"))) {
            document.createParagraph().createRun().setText("database document"); document.write(output);
        }
        library.addMaterial(owner, course.id(), "local-drive", "/report.docx", "");
        assertEquals(2, library.search(owner, course.id(), "database", 8).citations().size());
    }

    @Test void compressedZipExpansionIsRejectedBeforeDocxParsing() throws Exception {
        var course = library.createCourse(owner, "Archive", "Autumn");
        try (var zip = new ZipOutputStream(Files.newOutputStream(files.resolve("bomb.docx")))) {
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(new byte[4 * 1024 * 1024 + 1]); zip.closeEntry();
        }
        assertThrows(ApplicationFault.class, () -> library.addMaterial(owner, course.id(), "local-drive", "/bomb.docx", ""));
        assertTrue(library.listMaterials(owner, course.id()).isEmpty());
    }

    private LearningLibraryService.Material archive(long courseId, String name, String content, String tags) throws Exception {
        Files.writeString(files.resolve(name), content);
        return library.addMaterial(owner, courseId, "local-drive", "/" + name, tags);
    }
}
