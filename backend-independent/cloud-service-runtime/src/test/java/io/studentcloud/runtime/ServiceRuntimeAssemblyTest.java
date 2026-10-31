package io.studentcloud.runtime;

import io.studentcloud.account.Actor;
import io.studentcloud.account.Role;
import io.studentcloud.account.SessionAuthenticator;
import io.studentcloud.runtime.http.RequestPathPolicyFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.*;

/** Starts the real servlet registration path; directly constructing a filter cannot prove assembly. */
class ServiceRuntimeAssemblyTest {
    private static final AtomicInteger AUTHENTICATIONS = new AtomicInteger();
    private static final AtomicInteger CONTROLLER_CALLS = new AtomicInteger();
    private static ServletWebServerApplicationContext context;
    private static HttpClient client;
    private static String origin;

    @BeforeAll static void startRuntime() {
        context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(Fixture.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                        "--logging.level.root=ERROR", "--spring.jmx.enabled=false",
                        "--student-cloud.web.allowed-origins=http://127.0.0.1:5173",
                        "--student-cloud.services.internal-key=isolated-runtime-assembly-test-key-32");
        origin = "http://127.0.0.1:" + context.getWebServer().getPort();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterAll static void stopRuntime() { if (context != null) context.close(); }
    @BeforeEach void resetCounters() { AUTHENTICATIONS.set(0); CONTROLLER_CALLS.set(0); }

    @Test void policyIsRegisteredInTheActualServletContainer() {
        assertTrue(context.getServletContext().getFilterRegistrations().values().stream()
                .anyMatch(registration -> RequestPathPolicyFilter.class.getName().equals(registration.getClassName())),
                "The explicit shared runtime must install its route policy as a servlet filter");
    }

    @Test void routeAliasesAreRejectedBeforeIdentityLookupAndControllerBodyParsing() throws Exception {
        for (String path : new String[]{"/api/probe;ignored=1", "/api/%70robe", "/api/probe%3Bignored=1",
                "/internal/%72eady", "/user/login;ignored=1"}) {
            HttpResponse<String> response = post(path, "{} ".repeat(3000));
            assertEquals(400, response.statusCode(), path);
            assertTrue(response.body().contains("INVALID_ROUTE"), path);
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        }
        assertEquals(0, AUTHENTICATIONS.get(), "Aliases must not reach the identity service");
        assertEquals(0, CONTROLLER_CALLS.get(), "Aliases must not reach body parsing or business code");
    }

    @Test void canonicalRoutesStillAllowEncodedVirtualFileNamesInQueryValues() throws Exception {
        HttpResponse<String> response = post("/api/probe?path=%2Fname%3Bpart.txt", "source text");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("source text"));
        assertEquals(1, AUTHENTICATIONS.get());
        assertEquals(1, CONTROLLER_CALLS.get());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ServiceRuntimeConfiguration.class)
    static class Fixture {
        @Bean SessionAuthenticator accounts() {
            return token -> {
                AUTHENTICATIONS.incrementAndGet();
                return new Actor(1, "SC_TEST", "fixture", null, Role.USER, 1L);
            };
        }
        @Bean Probe probe() { return new Probe(); }
    }

    @RestController
    static class Probe {
        @PostMapping("/api/probe") Map<String, String> probe(@RequestBody String body) {
            CONTROLLER_CALLS.incrementAndGet();
            return Map.of("body", body);
        }
    }
}
