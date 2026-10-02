package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomerAiIntentServiceTest {
    final AiModelService models = mock(AiModelService.class);
    final CustomerAiIntentService service = new CustomerAiIntentService(models, new ObjectMapper());
    @Test void onlyAcceptsReadOnlyJson() {
        assertEquals("板蓝根", service.parse("{\"action\":\"searchDrugs\",\"keyword\":\"板蓝根\"}").keyword());
        assertEquals(10L, service.parse("{\"action\":\"getDrugDetail\",\"drugId\":10}").drugId());
        for (String invalid : List.of("null", "[]", "```json {} ```", "{\"action\":\"pay\"}",
                "{\"action\":\"searchDrugs\",\"keyword\":\"x\",\"tenantId\":2}",
                "{\"action\":\"getDrugDetail\",\"drugId\":1.5}",
                "{\"action\":\"refuse\"} {}", "{\"action\":\"refuse\",\"action\":\"pay\"}"))
            assertThrows(IllegalArgumentException.class, () -> service.parse(invalid), invalid);
    }
    @Test void rejectsSensitiveActionsBeforeModelCall() {
        assertEquals("refuse", service.resolve("帮我下单并支付").action());
        assertEquals("refuse", service.resolve("请给我开方").action());
        verifyNoInteractions(models);
    }
    void output(String content) {
        var model = AiModelDO.builder().id(1L).platform("DeepSeek").model("deepseek-flash").build();
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(model);
        var client = mock(ChatModel.class);
        when(models.getChatModel(1L)).thenReturn(client);
        when(client.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }
    @Test void rejectsInventedKeyword() {
        output("{\"action\":\"searchDrugs\",\"keyword\":\"阿莫西林\"}");
        assertThrows(IllegalArgumentException.class, () -> service.resolve("查询板蓝根"));
    }
    @Test void rejectsInventedProductAndPartialId() {
        output("{\"action\":\"getDrugDetail\",\"drugId\":10}");
        assertThrows(IllegalArgumentException.class, () -> service.resolve("查询编号100"));
    }
    @Test void acceptsExplicitKeyword() {
        output("{\"action\":\"searchDrugs\",\"keyword\":\"板蓝根\"}");
        assertEquals("板蓝根", service.resolve("查询板蓝根").keyword());
    }
    @Test void symptomsCanRequestAdviceAndUrgentSymptomsAreNotSentToModel() {
        output("{\"action\":\"advice\",\"keyword\":\"感冒\"}");
        assertEquals("advice",service.resolve("我感冒了有什么用药建议").action());
        assertEquals("urgent",service.resolve("我突然剧烈腹痛").action());
    }
    @Test void ungroundedSymptomCanOnlyContinueWithoutCatalogueQuery() {
        output("{\"action\":\"advice\",\"keyword\":\"胃炎\"}");
        var intent = service.resolve("肚子疼");
        assertEquals("chat", intent.action());
        assertNull(intent.keyword());
        assertNull(intent.drugId());
    }
    @Test void malformedFollowupIntentCanOnlyContinueWithoutCatalogueQuery() {
        output("not allowed JSON");
        var context = List.of(new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultReqVO.Turn("user", "我感冒了"));
        var intent = service.resolve("成人，刚开始，没有过敏", context);
        assertEquals("chat", intent.action()); assertNull(intent.keyword()); assertNull(intent.drugId());
        assertThrows(IllegalArgumentException.class, () -> service.resolve("成人，刚开始，没有过敏"));
    }
}
