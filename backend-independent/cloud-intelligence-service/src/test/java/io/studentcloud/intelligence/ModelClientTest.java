package io.studentcloud.intelligence;

import com.sun.net.httpserver.HttpServer;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.ai.OpenAiChatClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelClientTest {
    @Test void sendsBoundedChatAndReadsActualCompletion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("deepseek-v4-flash"));
            byte[] body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"真实回答\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiChatClient("http://127.0.0.1:" + server.getAddress().getPort(), "deepseek-v4-flash", "test-key");
            assertEquals("真实回答", client.complete(List.of(Map.of("role", "user", "content", "问题")), 128));
        } finally { server.stop(0); }
    }

    @Test void missingKeyCannotReturnFabricatedAnswer() {
        var client = new OpenAiChatClient("https://api.deepseek.com", "deepseek-v4-flash", "");
        assertThrows(ApplicationFault.class, () -> client.complete(List.of(Map.of("role", "user", "content", "问题")), 128));
    }
}
