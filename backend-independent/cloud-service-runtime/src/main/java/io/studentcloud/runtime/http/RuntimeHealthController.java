package io.studentcloud.runtime.http;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class RuntimeHealthController {
    private final ObjectProvider<DataSource> dataSource;
    public RuntimeHealthController(ObjectProvider<DataSource> dataSource) { this.dataSource=dataSource; }
    @GetMapping("/healthz") public Map<String,String> alive() { return Map.of("status","UP"); }
    @GetMapping("/readyz") public ResponseEntity<Map<String,String>> ready() {
        DataSource database=dataSource.getIfAvailable();
        if (database!=null) {
            try (var connection=database.getConnection()) {
                if (connection.isValid(1)) return ResponseEntity.ok(Map.of("status","UP"));
            } catch (java.sql.SQLException unavailable) { /* No connection string or database failure is disclosed. */ }
        }
        return ResponseEntity.status(503).body(Map.of("status","DOWN"));
    }
}
