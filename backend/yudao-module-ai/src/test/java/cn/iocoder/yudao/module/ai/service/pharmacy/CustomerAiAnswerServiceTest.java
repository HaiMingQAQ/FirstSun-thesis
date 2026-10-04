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
}
