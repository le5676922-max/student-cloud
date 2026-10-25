package im.tlgxy.cloud.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import im.tlgxy.cloud.module.ai.config.LearningEmbeddingProperties;
import im.tlgxy.cloud.module.learning.application.port.LearningEmbeddingGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Optional semantic embedding client; failures propagate so the caller can explicitly use lexical fallback. */
@Service
public class LearningEmbeddingClient implements LearningEmbeddingGateway {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_BATCH = 16;
    public static final int MAX_SEGMENT_CHARS = 700;
    public static final int MAX_SEGMENTS_PER_MATERIAL = 64;
    private final LearningEmbeddingProperties settings;
    private final EmbeddingTransport transport;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore permits;

    @Autowired
    public LearningEmbeddingClient(LearningEmbeddingProperties settings) {
        this(settings, new HttpEmbeddingTransport());
    }
    LearningEmbeddingClient(LearningEmbeddingProperties settings, EmbeddingTransport transport) {
        this.settings = settings;
        this.transport = transport;
        this.permits = new Semaphore(Math.max(1, Math.min(settings.getMaxConcurrent(), 2)), true);
    }

    public boolean configured() {
        if (!settings.isEnabled() || settings.getApiKey() == null || settings.getApiKey().isBlank()
                || settings.getApiKey().length() > 4096 || settings.getApiKey().contains("\r") || settings.getApiKey().contains("\n")
                || settings.getModel() == null || settings.getModel().isBlank() || settings.getModel().length() > 200
                || settings.getModel().chars().anyMatch(Character::isISOControl)
                || settings.getDimensions() < 32 || settings.getDimensions() > 4096
                || settings.getTimeoutSeconds() < 1 || settings.getTimeoutSeconds() > 10
                || !Double.isFinite(settings.getMinSimilarity()) || settings.getMinSimilarity() < -1 || settings.getMinSimilarity() > 1
                || settings.getMaxConcurrent() < 1 || settings.getMaxConcurrent() > 2) return false;
        try { endpoint(); return true; } catch (RuntimeException invalid) { return false; }
    }

    public int dimensions() { return settings.getDimensions(); }
    public double minSimilarity() { return settings.getMinSimilarity(); }

    /** Cache/index compatibility identifier; does not contain the provider key, prompt or document content. */
    public String signature() {
        if (!configured()) return "";
        try {
            String identity = endpoint().toString() + "\n" + settings.getModel().trim() + "\n" + settings.getDimensions();
            return "embedding-v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public List<float[]> embed(List<String> segments) {
        if (!configured()) throw new EmbeddingException("语义检索模型尚未完整配置");
        if (segments == null || segments.isEmpty() || segments.size() > MAX_SEGMENTS_PER_MATERIAL)
            throw new EmbeddingException("单次资料嵌入只能包含 1 至 64 段文字");
        List<String> input = new ArrayList<>(segments.size());
        for (String segment : segments) {
            if (segment == null || segment.isBlank() || segment.length() > MAX_SEGMENT_CHARS)
                throw new EmbeddingException("每段嵌入文字不能为空且不能超过 700 字符");
            input.add(segment);
        }
        if (!permits.tryAcquire()) throw new EmbeddingException("语义检索服务繁忙，请使用关键词检索或稍后重试");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.getTimeoutSeconds());
        try {
            List<float[]> vectors = new ArrayList<>(input.size());
            for (int start = 0; start < input.size(); start += MAX_BATCH) {
                List<String> batch = input.subList(start, Math.min(start + MAX_BATCH, input.size()));
                byte[] payload = json.writeValueAsBytes(Map.of("model", settings.getModel().trim(), "input", batch,
                        "encoding_format", "float"));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new EmbeddingException("资料嵌入总处理时间达到上限，请使用关键词检索");
                Response response = transport.exchange(endpoint(), settings.getApiKey().trim(), payload, Duration.ofNanos(remaining));
                if (System.nanoTime() >= deadline) throw new EmbeddingException("资料嵌入总处理时间达到上限，请使用关键词检索");
                if (response == null || response.status() != 200)
                    throw new EmbeddingException("嵌入模型服务返回异常，请使用关键词检索");
                if (response.body() == null || response.body().length > MAX_RESPONSE_BYTES)
                    throw new EmbeddingException("嵌入模型响应超过安全上限");
                vectors.addAll(validateVectors(json.readTree(response.body()), batch.size()));
            }
            return List.copyOf(vectors);
        } catch (EmbeddingException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("嵌入请求已中断，请使用关键词检索");
        } catch (Exception error) {
            // Never retain a provider exception/body in the cause chain: it may expose a key or private text.
            throw new EmbeddingException("嵌入模型暂时不可用，请使用关键词检索");
        } finally { permits.release(); }
    }

    private List<float[]> validateVectors(JsonNode root, int count) {
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray() || data.size() != count)
            throw new EmbeddingException("嵌入模型返回的段数不匹配");
        float[][] ordered = new float[count][];
        for (JsonNode item : data) {
            JsonNode index = item.get("index");
            if (index == null || !index.isIntegralNumber() || !index.canConvertToInt()
                    || index.intValue() < 0 || index.intValue() >= count || ordered[index.intValue()] != null)
                throw new EmbeddingException("嵌入模型返回的索引无效或重复");
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray() || embedding.size() != settings.getDimensions())
                throw new EmbeddingException("嵌入模型维度与配置不一致，请重新配置后重建索引");
            double[] values = new double[settings.getDimensions()];
            double norm = 0;
            for (int i = 0; i < values.length; i++) {
                JsonNode value = embedding.get(i);
                if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
                    throw new EmbeddingException("嵌入模型包含非法数值");
                values[i] = value.doubleValue();
                norm = Math.hypot(norm, values[i]);
            }
            if (norm == 0 || !Double.isFinite(norm)) throw new EmbeddingException("嵌入模型返回的向量不能安全归一化");
            float[] normalized = new float[values.length];
            for (int i = 0; i < values.length; i++) normalized[i] = (float) (values[i] / norm);
            ordered[index.intValue()] = normalized;
        }
        return List.of(ordered);
    }

    private URI endpoint() {
        if (settings.getBaseUrl() == null || settings.getBaseUrl().isBlank()) throw new EmbeddingException("嵌入模型地址未配置");
        URI base = URI.create(settings.getBaseUrl().trim());
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null)
            throw new EmbeddingException("嵌入模型地址必须是不含凭据、查询或片段的 HTTPS 地址");
        String value = base.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return URI.create(value + "/embeddings");
    }

    public static final class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
    }
    record Response(int status, byte[] body) { }
    @FunctionalInterface interface EmbeddingTransport {
        Response exchange(URI endpoint, String key, byte[] request, Duration timeout) throws Exception;
    }

    private static final class HttpEmbeddingTransport implements EmbeddingTransport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public Response exchange(URI endpoint, String key, byte[] request, Duration timeout) throws Exception {
            HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("Accept-Encoding", "identity")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build();
            var pending = client.sendAsync(http, info -> new LimitedBodySubscriber());
            try {
                HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new Response(response.statusCode(), response.body());
            } catch (Exception failure) {
                pending.cancel(true);
                throw failure;
            }
        }
    }

    /** Reject during reception rather than allocating an unbounded provider response before checking size. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) output.size() + buffer.remaining() > MAX_RESPONSE_BYTES) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Embedding response too large")); return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(new IOException("Embedding transport unavailable")); }
        @Override public void onComplete() { body.complete(output.toByteArray()); }
    }
}
