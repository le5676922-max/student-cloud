package im.tlgxy.cloud.module.learning.service;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Applies only the new coverage migration to an isolated memory database with an old index. */
class LearningCoverageMigrationTest {
    @Test void oldIndexesRemainUnknownWhileExpandedContentAndCoverageCanBePersisted() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement sql = db.createStatement()) {
            sql.execute("CREATE TABLE learning_material(id INTEGER PRIMARY KEY, content TEXT NOT NULL)");
            sql.execute("INSERT INTO learning_material(id, content) VALUES(1, 'old text')");
            for (String statement : Files.readString(Path.of("src/main/resources/db/migration-sqlite/V69__learning_index_coverage.sql")).split(";"))
                if (!statement.isBlank()) sql.execute(statement);
            try (var old = sql.executeQuery("SELECT * FROM learning_material WHERE id=1")) {
                assertTrue(old.next()); assertEquals(1, old.getInt("index_version"));
                assertNull(old.getObject("covered_chars")); assertNull(old.getObject("truncated"));
                assertEquals("old text", old.getString("content"));
            }
            String longText = "知识".repeat(100000);
            try (var insert = db.prepareStatement("INSERT INTO learning_material(id,content,index_version,covered_chars,truncated,index_notice) VALUES(2,?,2,?,0,'known')")) {
                insert.setString(1, longText); insert.setInt(2, longText.length()); insert.executeUpdate();
            }
            try (var current = sql.executeQuery("SELECT * FROM learning_material WHERE id=2")) {
                assertTrue(current.next()); assertEquals(longText, current.getString("content"));
                assertEquals(200000, current.getInt("covered_chars")); assertEquals(2, current.getInt("index_version"));
            }
        }
    }
}
