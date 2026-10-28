package io.studentcloud.intelligence.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.studentcloud.common.ApplicationFault;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;

/** Bounded OpenAI-compatible chat completions. No automatic tool calls or hidden retried writes. */
public final class OpenAiChatClient {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private final URI endpoint;
    private final String model;
    private final String key;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore budget=new Semaphore(4);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public OpenAiChatClient(String baseUrl, String model, String key) {
        URI base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!("https".equals(base.getScheme()) || "http".equals(base.getScheme())
                && ("localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost()))))
            throw new IllegalArgumentException("Model endpoint requires HTTPS or localhost");
        if (base.getUserInfo() != null || base.getFragment() != null || base.getQuery() != null || base.getHost() == null)
            throw new IllegalArgumentException("Model endpoint is invalid");
        this.endpoint = base.resolve("chat/completions");
        this.model = model;
        this.key = key;
    }

    public String complete(List<Map<String,String>> messages, int maxTokens) {
        if (key == null || key.isBlank()) throw new ApplicationFault("ASSISTANT_MODEL_NOT_CONFIGURED", 503, "AI 模型尚未配置，请联系管理员");
        if (model == null || !model.matches("[A-Za-z0-9._-]{1,80}") || messages == null || messages.isEmpty()
                || messages.size() > 16 || maxTokens < 1 || maxTokens > 4096)
            throw new ApplicationFault("41000", 400, "模型请求参数无效");
        if(!budget.tryAcquire())throw new ApplicationFault("ASSISTANT_BUSY",429,"AI正在处理其他请求，请稍后重试");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            byte[] requestBody = json.writeValueAsBytes(Map.of("model", model, "messages", messages,
                    "max_tokens", maxTokens, "stream", false));
            if (requestBody.length > 128 * 1024) throw new ApplicationFault("41000", 400, "对话上下文过长");
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
            pending=http.sendAsync(request,info->new LimitedBody());
            HttpResponse<byte[]> response=pending.get(45,TimeUnit.SECONDS);
                if (response.statusCode() != 200) throw unavailable();
                byte[] bytes = response.body();
                if (bytes.length > MAX_RESPONSE_BYTES) throw unavailable();
                JsonNode choice = json.readTree(new String(bytes, StandardCharsets.UTF_8)).path("choices").path(0);
                String answer = choice.path("message").path("content").asText("").trim();
                if (!"stop".equals(choice.path("finish_reason").asText()) || answer.isEmpty() || answer.length() > 12000)
                    throw unavailable();
                return answer;
        } catch (ApplicationFault fault) { throw fault;
        } catch (IOException failure) { throw unavailable();
        } catch (ExecutionException | TimeoutException failure) { throw unavailable();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
        finally{if(pending!=null && !pending.isDone())pending.cancel(true);budget.release();}
    }

    public boolean configured() { return key != null && !key.isBlank(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription value){subscription=value;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){
                if(buffer.remaining()>MAX_RESPONSE_BYTES-bytes.size()){subscription.cancel();body.completeExceptionally(new IOException("Model response budget exceeded"));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure){body.completeExceptionally(failure);}
        public void onComplete(){body.complete(bytes.toByteArray());}
    }

    private static ApplicationFault unavailable() {
        return new ApplicationFault("ASSISTANT_MODEL_UNAVAILABLE", 503, "AI 模型暂不可用，请稍后重试");
    }
}
