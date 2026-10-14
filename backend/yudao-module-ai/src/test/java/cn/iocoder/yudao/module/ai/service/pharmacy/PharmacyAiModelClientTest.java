package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO;
import cn.iocoder.yudao.module.ai.service.model.AiApiKeyService;
import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本机合成上游；验证成功、JSON 截断与 HTTP 错误的实际 HTTP 尝试次数。 */
class PharmacyAiModelClientTest {
    private final AiModelService models = mock(AiModelService.class);
    private final AiApiKeyService keys = mock(AiApiKeyService.class);
    private final PharmacyAiModelClient client = new PharmacyAiModelClient(models, keys);
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private String body;
    private int status;
    private AiModelDO model;
    private JsonNode requestBody;
    private String requestPath;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        model = AiModelDO.builder().id(1L).keyId(2L).model("synthetic-model").platform("OpenAI").maxTokens(800).build();
        when(models.validateModel(1L)).thenReturn(model);
        when(keys.validateApiKey(2L)).thenReturn(AiApiKeyDO.builder().apiKey("synthetic-api-key")
                .platform("OpenAI").url("http://127.0.0.1:" + server.getAddress().getPort()).build());
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void validUpstreamResponseRequiresOneRequest() {
        status = 200;
        body = "{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"synthetic-model\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        var session = client.forModel(model);
        assertTrue(session.allowRouteRepair());
        assertEquals("ok", session.client().call(new Prompt("synthetic", session.options())).getResult().getOutput().getText());
        assertEquals(1, requests.get());
        assertFalse(requestBody.has("thinking"));
        assertEquals("/v1/chat/completions", requestPath);
        model.setPlatform("DeepSeek");
        when(keys.validateApiKey(2L)).thenReturn(AiApiKeyDO.builder().apiKey("synthetic-api-key")
                .platform("DeepSeek").url("http://127.0.0.1:" + server.getAddress().getPort()).build());
        var deepSeek = client.forModel(model);
        assertEquals("ok", deepSeek.client().call(new Prompt("synthetic", deepSeek.options())).getResult().getOutput().getText());
        assertEquals(2, requests.get());
        assertEquals("disabled", requestBody.path("thinking").path("type").asText());
        assertEquals("/chat/completions", requestPath);
        assertEquals(800, requestBody.path("max_tokens").asInt());
        verify(models, never()).getChatModel(anyLong());
    }

    @Test
    void truncatedJsonDoesNotRetrySdkOrHttpTransport() {
        status = 200;
        body = "{\"id\":\"synthetic\",\"choices\":[";
        var session = client.forModel(model);
        Exception error = assertThrows(RuntimeException.class,
                () -> session.client().call(new Prompt("synthetic", session.options())));
        assertEquals("UPSTREAM_RESPONSE_TRUNCATED", PharmacyAiOrchestrator.errorCode(error, "route_model"));
        assertEquals(1, requests.get());
    }

    @Test
    void serviceUnavailableDoesNotRetrySdkOrHttpTransport() {
        status = 503;
        body = "{\"error\":{\"message\":\"synthetic upstream failure\"}}";
        var session = client.forModel(model);
        Exception error = assertThrows(RuntimeException.class,
                () -> session.client().call(new Prompt("synthetic", session.options())));
        assertEquals("UPSTREAM_HTTP_ERROR", PharmacyAiOrchestrator.errorCode(error, "route_model"));
        assertEquals(1, requests.get());
    }

    @Test
    void otherProviderDoesNotAddApplicationRepairToExistingSdkRetries() {
        var other = AiModelDO.builder().id(4L).model("synthetic").platform("Ollama").build();
        ChatModel existing = mock(ChatModel.class);
        when(models.getChatModel(4L)).thenReturn(existing);
        var session = client.forModel(other);
        assertSame(existing, session.client());
        assertFalse(session.allowRouteRepair());
        verifyNoInteractions(keys);
    }
}
