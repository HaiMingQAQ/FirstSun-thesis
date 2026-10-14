package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.controller.admin.pharmacy.vo.PharmacyAiChatReqVO;
import cn.iocoder.yudao.module.ai.controller.admin.pharmacy.vo.PharmacyAiEvent;
import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link PharmacyAiOrchestrator} 决策解析与有限重试测试。
 * 覆盖：标准 JSON、Markdown ```json 代码块、前后解释文字、缺 action、垃圾输入、有限重试。
 */
class PharmacyAiOrchestratorTest {

    private final AiModelService modelService = mock(AiModelService.class);
    private final PharmacyAiToolService toolService = mock(PharmacyAiToolService.class);
    private final PharmacyAiCommandService commandService = mock(PharmacyAiCommandService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PharmacyAiModelClient modelClient = mock(PharmacyAiModelClient.class);
    private PharmacyAiOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new PharmacyAiOrchestrator(modelService, toolService, commandService, objectMapper, modelClient);
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private ChatResponse response(String content, String finishReason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }

    @Test
    void parseStandardJsonDecision() {
        JsonNode decision = orchestrator.parseDecision(
                "{\"action\":\"prepareUpdateDrug\",\"arguments\":{\"id\":163101,\"changes\":{\"retailPrice\":25}}}");
        assertEquals("prepareUpdateDrug", decision.path("action").asText());
        assertEquals(163101, decision.path("arguments").path("id").asLong());
        assertEquals(25, decision.path("arguments").path("changes").path("retailPrice").asInt());
    }

    @Test
    void parseMarkdownJsonFence() {
        JsonNode decision = orchestrator.parseDecision("""
                ```json
                {"action":"prepareUpdateDrug","arguments":{"id":163101,"changes":{"retailPrice":25}}}
                ```
                """);
        assertEquals("prepareUpdateDrug", decision.path("action").asText());
    }

    @Test
    void parseJsonWithLeadingText() {
        JsonNode decision = orchestrator.parseDecision("好的，我将为您修改药品价格："
                + "{\"action\":\"prepareUpdateDrug\",\"arguments\":{\"id\":163101,\"changes\":{\"retailPrice\":25}}}");
        assertEquals("prepareUpdateDrug", decision.path("action").asText());
    }

    @Test
    void parseJsonWithLeadingAndTrailingText() {
        JsonNode decision = orchestrator.parseDecision("已为您生成预览。"
                + "{\"action\":\"prepareUpdateDrug\",\"arguments\":{\"id\":163101,\"changes\":{\"retailPrice\":25}}}"
                + "，请点击确认按钮完成修改。");
        assertEquals("prepareUpdateDrug", decision.path("action").asText());
        assertEquals(163101, decision.path("arguments").path("id").asLong());
    }

    @Test
    void parseJsonWithoutActionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.parseDecision("{\"arguments\":{\"id\":1}}"));
    }

    @Test
    void parseGarbageInputFailsWithActionableMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.parseDecision("抱歉，我不太理解你的意思。"));
        assertTrue(ex.getMessage().contains("重新描述"), ex.getMessage());
    }

    @Test
    void routeDecisionRetriesOnceWhenFirstOutputIsUnparseable() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("让我先查一下药品信息……"))
                .thenReturn(response("{\"action\":\"prepareUpdateDrug\",\"arguments\":{\"id\":1,\"changes\":{\"retailPrice\":25}}}"));

        JsonNode decision = orchestrator.routeDecision(chatModel, mock(ChatOptions.class), "把药品 1 的零售价改为 25 元");

        assertEquals("prepareUpdateDrug", decision.path("action").asText());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void routeDecisionStopsAfterLimitedRetries() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("第一次不可解析"))
                .thenReturn(response("第二次仍不可解析"));

        assertThrows(IllegalStateException.class,
                () -> orchestrator.routeDecision(chatModel, mock(ChatOptions.class), "把药品 1 的零售价改为 25 元"));
        verify(chatModel, times(2)).call(any(Prompt.class)); // 初始 1 次 + 有限重试 1 次，禁止无限重试
    }

    private ChatModel streamModel() {
        var model = AiModelDO.builder().id(1L).model("synthetic-model").platform("OpenAI").build();
        ChatModel chatModel = mock(ChatModel.class);
        when(modelService.getRequiredDefaultModel(anyInt())).thenReturn(model);
        when(modelClient.forModel(model)).thenReturn(new PharmacyAiModelClient.Session(chatModel,
                mock(ChatOptions.class), true));
        return chatModel;
    }

    private PharmacyAiChatReqVO request() {
        var request = new PharmacyAiChatReqVO();
        request.setConversationId(3L);
        request.setClientMessageId("synthetic-message");
        request.setContent("synthetic-private-prompt");
        return request;
    }

    private List<PharmacyAiEvent> events() {
        return orchestrator.stream(request()).collectList().block(Duration.ofSeconds(5));
    }

    @Test
    void normalReadKeepsAuthenticatedTenantContextAndReturnsRealToolData() {
        ChatModel model = streamModel();
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"action\":\"getCurrentStoreInventory\",\"arguments\":{\"drugId\":9}}"));
        var authentication = new UsernamePasswordAuthenticationToken("synthetic-user", null);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        TenantContextHolder.setTenantId(21L);
        try {
            when(toolService.getCurrentStoreInventory(9L, null)).thenAnswer(invocation -> {
                assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
                assertEquals(21L, TenantContextHolder.getTenantId());
                assertFalse(TenantContextHolder.isIgnore());
                return Map.of("availableQty", 52);
            });
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("当前库存 52。"),
                    response("", "STOP"), new ChatResponse(List.of())));
            List<PharmacyAiEvent> events = events();
            assertEquals(List.of("metadata", "tool_started", "tool_result", "delta", "done"),
                    events.stream().map(PharmacyAiEvent::getType).toList());
            assertEquals("stop", ((Map<?, ?>) events.get(4).getData()).get("finishReason"));
            assertNotNull(((Map<?, ?>) events.get(0).getData()).get("requestId"));
            verifyNoInteractions(commandService);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }
    }

    @Test
    void upstreamTruncatedResponseIsCorrelatedAndDoesNotLeakExceptionOrPrompt() throws Exception {
        ChatModel model = streamModel();
        Exception eof = assertThrows(com.fasterxml.jackson.core.io.JsonEOFException.class,
                () -> objectMapper.readTree("{\"secret\":"));
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("synthetic-private-token", eof));
        Logger logger = (Logger) LoggerFactory.getLogger(PharmacyAiOrchestrator.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<PharmacyAiEvent> events = events();
            assertEquals(List.of("metadata", "error", "done"), events.stream().map(PharmacyAiEvent::getType).toList());
            Map<?, ?> error = (Map<?, ?>) events.get(1).getData();
            assertEquals("route_model", error.get("stage"));
            assertEquals("UPSTREAM_RESPONSE_TRUNCATED", error.get("errorCode"));
            assertEquals(((Map<?, ?>) events.get(0).getData()).get("requestId"), error.get("requestId"));
            assertFalse(objectMapper.writeValueAsString(events).contains("synthetic-private-token"));
            assertFalse(appender.list.stream().anyMatch(entry -> entry.getFormattedMessage().contains("synthetic-private")));
            verify(model, times(1)).call(any(Prompt.class));
            verifyNoInteractions(toolService, commandService);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void answerFailureKeepsToolResultAndSignalsAnIncompleteAnswer() {
        ChatModel model = streamModel();
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"action\":\"getCurrentStoreInventory\",\"arguments\":{\"drugId\":9}}"));
        when(toolService.getCurrentStoreInventory(9L, null)).thenReturn(Map.of("availableQty", 52));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("部分回答")),
                Flux.error(new RuntimeException("synthetic-private-token"))));
        List<PharmacyAiEvent> events = events();
        assertEquals(List.of("metadata", "tool_started", "tool_result", "delta", "error", "done"),
                events.stream().map(PharmacyAiEvent::getType).toList());
        assertEquals("answer_model", ((Map<?, ?>) events.get(4).getData()).get("stage"));
        assertEquals("error", ((Map<?, ?>) events.get(5).getData()).get("finishReason"));
        verifyNoInteractions(commandService);
    }

    @Test
    void normalEofWithLengthFinishPreservesPartialDataAndReportsTokenLimit() {
        ChatModel model = streamModel();
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"action\":\"getCurrentStoreInventory\",\"arguments\":{\"drugId\":9}}"));
        when(toolService.getCurrentStoreInventory(9L, null)).thenReturn(Map.of("availableQty", 52));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("部分回答", "LENGTH")));
        List<PharmacyAiEvent> events = events();
        assertEquals(List.of("metadata", "tool_started", "tool_result", "delta", "error", "done"),
                events.stream().map(PharmacyAiEvent::getType).toList());
        assertEquals("部分回答", ((Map<?, ?>) events.get(3).getData()).get("content"));
        assertEquals("UPSTREAM_TOKEN_LIMIT", ((Map<?, ?>) events.get(4).getData()).get("errorCode"));
        assertEquals("error", ((Map<?, ?>) events.get(5).getData()).get("finishReason"));
        verify(model, times(1)).stream(any(Prompt.class));
        verifyNoInteractions(commandService);
    }

    @Test
    void normalEofWithoutFinishMetadataCannotClaimSuccessfulAnswer() {
        ChatModel model = streamModel();
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"action\":\"getCurrentStoreInventory\",\"arguments\":{\"drugId\":9}}"));
        when(toolService.getCurrentStoreInventory(9L, null)).thenReturn(Map.of("availableQty", 52));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("部分回答"), new ChatResponse(List.of())));
        List<PharmacyAiEvent> events = events();
        assertEquals("tool_result", events.get(2).getType());
        assertEquals("部分回答", ((Map<?, ?>) events.get(3).getData()).get("content"));
        assertEquals("UPSTREAM_MISSING_FINISH", ((Map<?, ?>) events.get(4).getData()).get("errorCode"));
        assertEquals("answer_model", ((Map<?, ?>) events.get(4).getData()).get("stage"));
        assertEquals("error", ((Map<?, ?>) events.get(5).getData()).get("finishReason"));
        verify(model, times(1)).stream(any(Prompt.class));
        verifyNoInteractions(commandService);
    }

    @Test
    void previewDoesNotConfirmOrClaimBusinessWriteSuccess() {
        ChatModel model = streamModel();
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"action\":\"preparePurchaseDraft\",\"arguments\":{\"purchase\":{}}}"));
        when(commandService.prepare(eq("preparePurchaseDraft"), any(), eq(3L), eq("synthetic-message")))
                .thenReturn(new PharmacyAiCommandService.Preview(5L, "CREATE_PURCHASE_DRAFT", "PENDING", null,
                        objectMapper.createObjectNode(), LocalDateTime.now().plusMinutes(5), "synthetic-token", null));
        List<PharmacyAiEvent> events = events();
        assertEquals("tool_preview", events.get(3).getType());
        String delta = (String) ((Map<?, ?>) events.get(2).getData()).get("content");
        assertTrue(delta.contains("确认前不会创建"));
        assertEquals("tool_confirmation_required", ((Map<?, ?>) events.get(4).getData()).get("finishReason"));
        verify(commandService, never()).confirm(anyLong(), anyString());
        verifyNoInteractions(toolService);
    }

    @Test
    void cancelWhileRoutingInterruptsWorkWithoutToolExecutionOrErrorEvents() throws Exception {
        ChatModel model = streamModel();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException ex) { interrupted.countDown(); throw new CancellationException(); }
            return response("{\"action\":\"prepareUpdateDrug\",\"arguments\":{}}");
        });
        List<PharmacyAiEvent> received = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        var subscription = orchestrator.stream(request()).subscribe(received::add, failures::add);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("metadata"), received.stream().map(PharmacyAiEvent::getType).toList());
            assertTrue(failures.isEmpty());
            verifyNoInteractions(toolService, commandService);
        } finally { subscription.dispose(); }
    }

    @Test
    void explicitCancellationExceptionPropagatesWithoutAnErrorEvent() {
        ChatModel model = streamModel();
        when(model.call(any(Prompt.class))).thenThrow(new CancellationException());
        assertThrows(CancellationException.class, this::events);
        verify(model, times(1)).call(any(Prompt.class));
        verifyNoInteractions(toolService, commandService);
    }

    @Test
    void cancelledRequestCannotExecuteAProviderResponseThatArrivesLate() throws Exception {
        ChatModel model = streamModel();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* 模拟不响应线程中断的 HTTP 供应商。 */ }
            }
            returned.countDown();
            return response("{\"action\":\"prepareUpdateDrug\",\"arguments\":{}}");
        });
        var subscription = orchestrator.stream(request()).subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            verifyNoInteractions(toolService, commandService);
        } finally { release.countDown(); subscription.dispose(); }
    }

    @Test
    void providerWithUncontrolledSdkRetriesDoesNotGetAnExtraRouteRepairCall() {
        ChatModel model = streamModel();
        when(modelClient.forModel(any())).thenReturn(new PharmacyAiModelClient.Session(model, mock(ChatOptions.class), false));
        when(model.call(any(Prompt.class))).thenReturn(response("无法解析"));
        List<PharmacyAiEvent> events = events();
        assertEquals("ROUTE_INVALID", ((Map<?, ?>) events.get(1).getData()).get("errorCode"));
        verify(model, times(1)).call(any(Prompt.class));
        verifyNoInteractions(toolService, commandService);
    }

    @Test
    void inventoryAlertsRejectModelSuppliedScopeOrRuleOverrides() {
        ChatModel model = streamModel();
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"action\":\"getInventoryAlerts\",\"arguments\":{\"storeId\":999,\"threshold\":99}}"));
        List<PharmacyAiEvent> events = events();
        assertEquals("TOOL_ERROR", ((Map<?, ?>) events.get(1).getData()).get("errorCode"));
        verifyNoInteractions(toolService, commandService);
    }
}
