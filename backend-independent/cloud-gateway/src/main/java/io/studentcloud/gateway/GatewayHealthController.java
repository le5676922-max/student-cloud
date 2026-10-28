package io.studentcloud.gateway;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayHealthController {
    @GetMapping("/healthz") public Map<String,String> alive() { return Map.of("status", "UP"); }
    @GetMapping("/__gateway/unavailable") public ResponseEntity<Map<String,Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store")
                .body(Map.of("code", "50300", "msg", "服务暂时不可用，请稍后重试"));
    }
}
