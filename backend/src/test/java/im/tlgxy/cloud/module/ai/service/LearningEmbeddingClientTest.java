package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.config.LearningEmbeddingProperties;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Simulated transport only: no external provider/key or real HTTP request. */
class LearningEmbeddingClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void disabledOrMissingIndependentCredentialNeverCallsProvider() {
        var settings = settings();
        var client = new LearningEmbeddingClient(settings, (url, key, body, timeout) -> fail("Must not call provider"));
        settings.setEnabled(false);
        assertFalse(client.configured());
        settings.setEnabled(true);
        settings.setApiKey("");
        assertFalse(client.configured());
        assertEquals("", client.signature());
        assertThrows(LearningEmbeddingClient.EmbeddingException.class, () -> client.embed(List.of("text")));
        assertFalse(settings.toString().contains("synthetic-test-key"));
    }

    @Test void onlyHttpsWithoutUriCredentialsQueryFragmentIsConfigured() {
        var settings = settings();
        var client = new LearningEmbeddingClient(settings, (url, key, body, timeout) -> fail("Not a network test"));
        for (String invalid : List.of("http://example.test/v1", "https://user:password@example.test/v1",
                "https://example.test/v1?key=bad", "https://example.test/v1#fragment")) {
            settings.setBaseUrl(invalid); assertFalse(client.configured(), invalid);
        }
    }

    @Test void modelSignatureDoesNotContainOrDependOnKeyAndDoesDependOnModelDimensions() {
        var settings = settings();
        var client = new LearningEmbeddingClient(settings, (url, key, body, timeout) -> fail("Not a network test"));
        String initial = client.signature();
        assertFalse(initial.contains("synthetic-test-key"));
        settings.setApiKey("another-synthetic-test-key");
        assertEquals(initial, client.signature());
        settings.setModel("different-model");
        assertNotEquals(initial, client.signature());
        settings.setModel("synthetic-model");
        settings.setDimensions(64);
        assertNotEquals(initial, client.signature());
        assertEquals(64, client.dimensions());
        assertEquals(0.25, client.minSimilarity());
    }

    @Test void validatesPayloadAndReturnsVectorsInInputOrderEvenWhenProviderReorders() throws Exception {
        var client = new LearningEmbeddingClient(settings(), (url, key, body, timeout) -> {
            assertEquals("https://example.test/v1/embeddings", url.toString());
            var payload = json.readTree(body);
            assertEquals("synthetic-model", payload.path("model").asText());
            assertEquals("float", payload.path("encoding_format").asText());
            assertEquals(2, payload.path("input").size());
            assertTrue(timeout.compareTo(Duration.ofSeconds(10)) <= 0);
            var response = json.readTree(validResponse(2, 32));
            var data = (com.fasterxml.jackson.databind.node.ArrayNode) response.get("data");
            var first = data.remove(0); data.add(first);
            return new LearningEmbeddingClient.Response(200, json.writeValueAsBytes(response));
        });
        var vectors = client.embed(List.of("first", "second"));
        assertEquals(1f, vectors.get(0)[0]);
        assertEquals(1f, vectors.get(1)[1]);
        assertEquals(0f, vectors.get(0)[1]);
    }

    @Test void upTo64SegmentsUseBatchesOf16SharingOneTotalDeadline() {
        List<Duration> budgets = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var client = new LearningEmbeddingClient(settings(), (url, key, body, timeout) -> {
            var payload = json.readTree(body);
            int count = payload.path("input").size();
            assertEquals(16, count);
            budgets.add(timeout); calls.incrementAndGet();
            return new LearningEmbeddingClient.Response(200, validResponse(count, 32));
        });
        assertEquals(64, client.embed(java.util.Collections.nCopies(64, "text")).size());
        assertEquals(4, calls.get());
        for (int index = 1; index < budgets.size(); index++) assertTrue(budgets.get(index).compareTo(budgets.get(index - 1)) <= 0);
        assertThrows(LearningEmbeddingClient.EmbeddingException.class,
                () -> client.embed(java.util.Collections.nCopies(65, "text")));
    }

    @Test void wrongDimensionsZeroOrNonfiniteVectorsAndDuplicateIndicesAreRejected() throws Exception {
        assertRejected(validResponse(1, 31), List.of("text"));
        var zero = json.readTree(validResponse(1, 32));
        var vector = (com.fasterxml.jackson.databind.node.ArrayNode) zero.path("data").get(0).path("embedding");
        vector.removeAll(); for (int index = 0; index < 32; index++) vector.add(0);
        assertRejected(json.writeValueAsBytes(zero), List.of("text"));
        vector.set(0, json.getNodeFactory().textNode("NaN"));
        assertRejected(json.writeValueAsBytes(zero), List.of("text"));
        var duplicate = json.readTree(validResponse(2, 32));
        ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate.path("data").get(1)).put("index", 0);
        assertRejected(json.writeValueAsBytes(duplicate), List.of("first", "second"));
    }

    @Test void normalizationIsFiniteAndUnitLength() throws Exception {
        var response = json.readTree(validResponse(1, 32));
        var vector = (com.fasterxml.jackson.databind.node.ArrayNode) response.path("data").get(0).path("embedding");
        vector.removeAll(); for (int index = 0; index < 32; index++) vector.add(index + 1);
        var client = new LearningEmbeddingClient(settings(), (url, key, body, timeout) ->
                new LearningEmbeddingClient.Response(200, json.writeValueAsBytes(response)));
        var normalized = client.embed(List.of("text")).getFirst();
        double length = 0; for (float number : normalized) { assertTrue(Float.isFinite(number)); length += number * number; }
        assertEquals(1.0, length, 0.00001);
    }

    @Test void oversizedBodyAndLongOrEmptyInputAreRejected() {
        assertRejected(new byte[LearningEmbeddingClient.MAX_RESPONSE_BYTES + 1], List.of("text"));
        var client = new LearningEmbeddingClient(settings(), (url, key, body, timeout) -> fail("No transport for bad inputs"));
        assertThrows(LearningEmbeddingClient.EmbeddingException.class, () -> client.embed(List.of("x".repeat(701))));
        assertThrows(LearningEmbeddingClient.EmbeddingException.class, () -> client.embed(List.of(" ")));
        assertThrows(LearningEmbeddingClient.EmbeddingException.class, () -> client.embed(List.of()));
    }

    @Test void redirectIsRejectedWithoutRetryOrKeyDisclosure() {
        AtomicInteger calls = new AtomicInteger();
        var client = new LearningEmbeddingClient(settings(), (url, key, body, timeout) -> {
            calls.incrementAndGet(); return new LearningEmbeddingClient.Response(302, "do-not-expose-provider-body".getBytes());
        });
        var error = assertThrows(LearningEmbeddingClient.EmbeddingException.class, () -> client.embed(List.of("text")));
        assertEquals(1, calls.get());
        assertFalse(error.toString().contains("do-not-expose-provider-body"));
        assertNull(error.getCause());
    }

    @Test void transportTimeoutDoesNotRetryLeakCauseOrRetainPermit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new LearningEmbeddingClient(settings(), (url, key, body, timeout) -> {
            if (calls.incrementAndGet() == 1) throw new HttpTimeoutException("synthetic-test-key raw secret text");
            return new LearningEmbeddingClient.Response(200, validResponse(1, 32));
        });
        var error = assertThrows(LearningEmbeddingClient.EmbeddingException.class, () -> client.embed(List.of("text")));
        assertNull(error.getCause());
        assertFalse(error.toString().contains("synthetic-test-key"));
        assertEquals(1, calls.get());
        assertEquals(1, client.embed(List.of("text")).size());
    }

    @Test void receiveLimitCancelsBeforeKeepingOversizedResponse() {
        var subscriber = new LearningEmbeddingClient.LimitedBodySubscriber();
        var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(LearningEmbeddingClient.MAX_RESPONSE_BYTES + 1)));
        verify(subscription).cancel();
        assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    }

    private void assertRejected(byte[] response, List<String> input) {
        var client = new LearningEmbeddingClient(settings(), (url, key, body, timeout) -> new LearningEmbeddingClient.Response(200, response));
        assertThrows(LearningEmbeddingClient.EmbeddingException.class, () -> client.embed(input));
    }
    private LearningEmbeddingProperties settings() {
        var settings = new LearningEmbeddingProperties();
        settings.setEnabled(true); settings.setApiKey("synthetic-test-key");
        settings.setBaseUrl("https://example.test/v1/"); settings.setModel("synthetic-model"); settings.setDimensions(32);
        return settings;
    }
    private byte[] validResponse(int count, int dimensions) throws Exception {
        var root = json.createObjectNode(); var data = root.putArray("data");
        for (int index = 0; index < count; index++) {
            var item = data.addObject(); item.put("index", index); var vector = item.putArray("embedding");
            for (int dimension = 0; dimension < dimensions; dimension++) vector.add(dimension == index % dimensions ? 1 : 0);
        }
        return json.writeValueAsBytes(root);
    }
}
