package cn.iocoder.yudao.module.ai.service.pharmacy;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultReqVO;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product;
import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO;
import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CustomerAiAnswerServiceTest {
    @Test void rejectsDiagnosisDoseUrlsAndUngroundedRecommendation() {
        for (String text : List.of("你患有胃炎。", "服用10mg。", "推荐阿莫西林。", "已接通真实医生。", "请看https://example.invalid"))
            assertEquals("具体用药请让本店药师结合症状和药品说明核对。\n", CustomerAiAnswerService.safeSentence(text,List.of()));
        assertEquals("疼痛持续多久了？",CustomerAiAnswerService.safeSentence("疼痛持续多久了？",List.of()));
        for (String text : List.of("有没有过敏、怀孕或正在服用的药物？", "你是否服用其他药物？", "信息不足时不直接推荐品牌。"))
            assertEquals(text, CustomerAiAnswerService.safeSentence(text, List.of()));
    }
    @Test void streamsCheckedSentencesBeforeProviderCompletionAndJoinsSplitChunks() {
        var models=mock(AiModelService.class);var client=mock(ChatModel.class);
        var customerModels=mock(CustomerAiModelClient.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(AiModelDO.builder().id(1L).platform("DeepSeek").model("deepseek-flash").build());
        when(customerModels.forModel(any(), eq(0.2), eq(1200))).thenReturn(new CustomerAiModelClient.Session(client, org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        var received=new ArrayList<String>();
        when(client.stream(any(Prompt.class))).thenReturn(Flux.create(sink->{
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("请问疼痛持续")))));
            assertTrue(received.isEmpty());
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("多久了？")))));
            assertEquals(List.of("请问疼痛持续多久了？"),received);
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("可以联系门店药师。")))));
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(""), org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build()))));
            sink.complete();
        }));
        var request=new CustomerAiConsultReqVO();request.setContent("肚子疼");
        String answer=new CustomerAiAnswerService(models,new ObjectMapper(),customerModels).answer(request,List.of(),received::add);
        assertEquals("请问疼痛持续多久了？可以联系门店药师。",answer);
        verify(client,never()).call(any(Prompt.class));
    }
    @Test void mixedRecommendationRendersOnlyCanonicalDatabaseNames() {
        var product = new Product(1L, "板蓝根颗粒（青叶）", "板蓝根颗粒", "10g", "厂商", "批准号", null, null, 10, null);
        String answer = CustomerAiAnswerService.safeSentence("可考虑板蓝根颗粒，也推荐阿莫西林。", List.of(product));
        assertTrue(answer.contains(product.name()));
        assertFalse(answer.contains("阿莫西林"));
        assertTrue(answer.contains("下方匹配的非处方商品"));
    }
    @Test void truncatedOrInterruptedModelNeverReportsACompleteAnswerAndOnlyEmitsCheckedSentences() {
        var models = mock(AiModelService.class); var client = mock(ChatModel.class);
        var customerModels = mock(CustomerAiModelClient.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(), eq(0.2), eq(1200))).thenReturn(new CustomerAiModelClient.Session(client, org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        var checked = new ChatResponse(List.of(new Generation(new AssistantMessage("注意休息。未完成的后半句"))));
        var terminal = new ChatResponse(List.of(new Generation(new AssistantMessage(""), org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("length").build())));
        when(client.stream(any(Prompt.class))).thenReturn(Flux.just(checked, terminal));
        var request = new CustomerAiConsultReqVO(); request.setContent("流鼻涕");
        var received = new ArrayList<String>();
        var service = new CustomerAiAnswerService(models, new ObjectMapper(), customerModels);
        assertEquals("模型回答未完成", assertThrows(IllegalStateException.class, () -> service.answer(request, List.of(), received::add)).getMessage());
        assertEquals(List.of("注意休息。"), received);
        received.clear();
        when(client.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(checked), Flux.error(new IllegalStateException("provider-private-error"))));
        assertThrows(IllegalStateException.class, () -> service.answer(request, List.of(), received::add));
        assertEquals(List.of("注意休息。"), received);
        received.clear(); when(client.stream(any(Prompt.class))).thenReturn(Flux.just(checked));
        assertEquals("模型回答未完成", assertThrows(IllegalStateException.class, () -> service.answer(request, List.of(), received::add)).getMessage());
        assertEquals(List.of("注意休息。"), received);
        received.clear(); when(client.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(""))))));
        assertEquals("模型未返回回答", assertThrows(IllegalStateException.class, () -> service.answer(request, List.of(), received::add)).getMessage());
        assertTrue(received.isEmpty());
    }
    @Test void catalogueFieldClaimsRenderEachVerifiedFieldIncludingTrueMissingValues() {
        var complete = new Product(31L, "清宁片（青叶）", "清宁片", "盒装", "甲制药", "批准甲号", null, null, 4, 8L);
        var partial = new Product(32L, "舒润颗粒", "舒润颗粒", null, "乙制药", "  ", null, null, 3, 8L);
        var absent = new Product(33L, "商品丙", null, " ", null, null, null, null, 2, 8L);
        var blank = new Product(34L, "商品丁", "  ", null, "\t", "", null, null, 1, 8L);
        for (List<Product> products : List.of(List.of(complete), List.of(partial), List.of(absent), List.of(blank), List.of(complete, partial, absent))) {
            for (String claim : List.of("这些药品缺少通用名、生产厂家和批准文号。", "药品资料完整，厂家是模型编造的丙公司。", "商品信息不完整。")) {
                String safe = CustomerAiAnswerService.safeSentence(claim, products);
                assertFalse(safe.contains("缺少")); assertFalse(safe.contains("模型编造")); assertFalse(safe.contains("信息不完整"));
                for (Product product : products) {
                    assertTrue(safe.contains("商品：" + product.name()));
                    assertTrue(safe.contains("通用名：" + expectedField(product.genericName())));
                    assertTrue(safe.contains("规格：" + expectedField(product.specification())));
                    assertTrue(safe.contains("生产厂家：" + expectedField(product.manufacturer())));
                    assertTrue(safe.contains("批准文号：" + expectedField(product.approvalNo())));
                }
            }
        }
    }
    static String expectedField(String value) { return value == null || value.isBlank() ? "未提供" : value.strip(); }
    @Test void contradictoryFieldClaimsSplitAcrossChunksNeverReachTheSink() {
        var models = mock(AiModelService.class); var client = mock(ChatModel.class);
        var customerModels = mock(CustomerAiModelClient.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(), eq(0.2), eq(1200))).thenReturn(new CustomerAiModelClient.Session(client, org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        var product = new Product(41L, "明净片", "明净片", "盒装", null, "批准乙号", null, null, 6, 8L);
        var received = new ArrayList<String>();
        when(client.stream(any(Prompt.class))).thenReturn(Flux.create(sink -> {
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("该药缺少通")))));
            assertTrue(received.isEmpty());
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("用名称和批准文号。")))));
            assertFalse(String.join("", received).contains("缺少通"));
            assertTrue(String.join("", received).contains("通用名：明净片"));
            assertTrue(String.join("", received).contains("生产厂家：未提供"));
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("厂家是捏造的公司。")))));
            assertFalse(String.join("", received).contains("捏造"));
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(""), org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build()))));
            sink.complete();
        }));
        var request = new CustomerAiConsultReqVO(); request.setContent("查询明净片资料");
        String answer = new CustomerAiAnswerService(models, new ObjectMapper(), customerModels).answer(request, List.of(product), received::add);
        assertEquals(String.join("", received), answer);
        assertFalse(answer.contains("缺少通")); assertFalse(answer.contains("捏造"));
        verify(client, never()).call(any(Prompt.class));
    }
    @Test void explicitCatalogQuestionsRenderServerFactsWithoutSuppressingOtherConsultation() {
        var models=mock(AiModelService.class);var client=mock(ChatModel.class);var customerModels=mock(CustomerAiModelClient.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(),eq(0.2),eq(1200))).thenReturn(new CustomerAiModelClient.Session(client,org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        var stop=new ChatResponse(List.of(new Generation(new AssistantMessage(""),org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())));
        when(client.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("未注明通用药物名称和制造单位。")))),stop));
        var product=new Product(57L,"清宁片","清宁片","盒装",null,"批准丁号",null,null,3,8L);
        var service=new CustomerAiAnswerService(models,new ObjectMapper(),customerModels);var request=new CustomerAiConsultReqVO();
        request.setContent("请查询编号 57 的药品名称、价格和本店可售库存。");var received=new ArrayList<String>();
        String facts=service.answer(request,List.of(product),received::add);
        assertFalse(facts.contains("未注明"));assertTrue(facts.contains("通用名：清宁片"));assertTrue(facts.contains("生产厂家：未提供"));
        assertEquals(facts,String.join("",received));verifyNoInteractions(models,customerModels);verify(client,never()).stream(any(Prompt.class));
        request.setContent("肚子疼");received.clear();
        when(client.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("疼痛持续多久了？")))),stop));
        assertEquals("疼痛持续多久了？",service.answer(request,List.of(product),received::add));
        verify(client,times(1)).stream(any(Prompt.class));assertEquals(List.of("疼痛持续多久了？"),received);
    }
}
