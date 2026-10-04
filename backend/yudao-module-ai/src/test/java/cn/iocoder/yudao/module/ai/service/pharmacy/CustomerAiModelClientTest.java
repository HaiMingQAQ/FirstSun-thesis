package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO;
import cn.iocoder.yudao.module.ai.service.model.AiApiKeyService;
import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import cn.iocoder.yudao.module.ai.util.AiUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerAiModelClientTest {
    private final AiModelService models = mock(AiModelService.class);
    private final AiApiKeyService apiKeys = mock(AiApiKeyService.class);
    private final CustomerAiModelClient service = new CustomerAiModelClient(models, apiKeys);
    private final AiModelDO model = AiModelDO.builder().id(1L).keyId(2L)
            .platform("DeepSeek").model("deepseek-flash").build();

    private CustomerAiModelClient.Session configured(Provider provider, int maxTokens) {
        when(models.validateModel(1L)).thenReturn(model);
        when(apiKeys.validateApiKey(2L)).thenReturn(AiApiKeyDO.builder().id(2L)
                .platform("DeepSeek").apiKey("synthetic-test-key").url(provider.url()).build());
        return service.forModel(model, 0.2, maxTokens);
    }

    @Test void synchronousCustomerRequestDisablesReasoningWithoutExposingTools() throws Exception {
        try (var provider = new Provider(200, "application/json", """
                {"id":"test","object":"chat.completion","created":1,"model":"deepseek-flash",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"请补充症状。","reasoning_content":"hidden reasoning"},"finish_reason":"stop"}]}
                """)) {
            var session = configured(provider, 256);
            var response = session.client().call(new Prompt(List.of(new UserMessage("测试问题")), session.options()));
            assertEquals("请补充症状。", AiUtils.getChatResponseContent(response));
            assertEquals("STOP", response.getResult().getMetadata().getFinishReason().toUpperCase());
            assertRequest(provider.requests.get(0), 256, false);
            verify(models, never()).getChatModel(1L);
        }
    }

    @Test void streamedRequestDisablesReasoningAndRetainsIncompleteFinishReason() throws Exception {
        try (var provider = new Provider(200, "text/event-stream", """
                data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"hidden reasoning"},"finish_reason":null}]}

                data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":"先查看药品说明。"},"finish_reason":null}]}

                data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """)) {
            var session = configured(provider, 1200);
            var responses = session.client().stream(new Prompt(List.of(new UserMessage("测试问题")), session.options()))
                    .collectList().block(Duration.ofSeconds(5));
            assertNotNull(responses);
            assertEquals("先查看药品说明。", responses.stream().map(AiUtils::getChatResponseContent)
                    .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining()));
            assertTrue(responses.stream().anyMatch(response -> response.getResult() != null
                    && "LENGTH".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())));
            assertRequest(provider.requests.get(0), 1200, true);
        }
    }

    @Test void providerFailureIsReturnedWithoutHiddenRetries() throws Exception {
        try (var provider = new Provider(503, "application/json", "{\"error\":{\"message\":\"test unavailable\"}}")) {
            var session = configured(provider, 256);
            assertThrows(RuntimeException.class,
                    () -> session.client().call(new Prompt(List.of(new UserMessage("测试问题")), session.options())));
            assertEquals(1, provider.requests.size());
        }
    }

    @Test void streamedProviderFailureIsReturnedWithoutHiddenRetries() throws Exception {
        try (var provider = new Provider(503, "application/json", "{\"error\":{\"message\":\"test unavailable\"}}")) {
            var session = configured(provider, 1200);
            assertThrows(RuntimeException.class, () -> session.client()
                    .stream(new Prompt(List.of(new UserMessage("测试问题")), session.options()))
                    .blockLast(Duration.ofSeconds(5)));
            assertEquals(1, provider.requests.size());
        }
    }

    @Test void mismatchedProviderConfigurationIsRejectedBeforeCallingProvider() {
        when(models.validateModel(1L)).thenReturn(model);
        when(apiKeys.validateApiKey(2L)).thenReturn(AiApiKeyDO.builder().platform("OpenAI").build());
        assertThrows(IllegalStateException.class, () -> service.forModel(model, 0.2, 1200));
    }

    @Test void otherProvidersKeepTheirExistingClientAndOptions() {
        var other = AiModelDO.builder().id(3L).platform("OpenAI").model("test-model").build();
        var client = mock(ChatModel.class);
        when(models.getChatModel(3L)).thenReturn(client);
        var session = service.forModel(other, 0.2, 1200);
        assertSame(client, session.client());
        assertEquals("test-model", session.options().getModel());
        assertEquals(1200, session.options().getMaxTokens());
        verifyNoInteractions(apiKeys);
    }

    private void assertRequest(JsonNode request, int maxTokens, boolean streaming) {
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertEquals("deepseek-flash", request.path("model").asText());
        assertEquals(maxTokens, request.path("max_tokens").asInt());
        assertEquals(streaming, request.path("stream").asBoolean());
        assertFalse(request.has("extraBody"));
        assertTrue(!request.has("tools") || request.path("tools").isEmpty());
    }

    private static final class Provider implements AutoCloseable {
        private final HttpServer server;
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        Provider(int status, String contentType, String body) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                requests.add(new ObjectMapper().readTree(exchange.getRequestBody()));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
