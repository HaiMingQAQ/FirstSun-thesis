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
    @Test void compoundUseAndAllergyQuestionAddsOnlyItsMissingIndicationBeforeGeneration() {
        var models = mock(AiModelService.class);
        var client = mock(ChatModel.class);
        var customerModels = mock(CustomerAiModelClient.class);
        var knowledge = mock(PharmacyKnowledgeService.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(), eq(0.2), eq(1200))).thenReturn(
                new CustomerAiModelClient.Session(client, org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        String question = "硝酸咪康唑乳膏主要用于什么问题？对咪康唑过敏时有哪些禁忌或注意事项？";
        var cream = new Product(1163202L, "硝酸咪康唑乳膏", "硝酸咪康唑乳膏", "20g",
                "厂家", "国药准字H61020001", null, null, 24, 9101L, "说明书修订日期；演示资料。");
        var contraindication = new PharmacyKnowledgeService.Hit(cream.id(), cream.name(), "禁忌", "咪康唑过敏者禁用。", 0.9);
        var caution = new PharmacyKnowledgeService.Hit(cream.id(), cream.name(), "注意事项", "念珠菌病避免密封包扎。", 0.8);
        var special = new PharmacyKnowledgeService.Hit(cream.id(), cream.name(), "特殊人群", "孕哺期先咨询医生。", 0.7);
        var wrongForm = new PharmacyKnowledgeService.Hit(1163210L, "硝酸咪康唑栓", "适应症", "其他剂型的用途。", 0.99);
        var indication = new PharmacyKnowledgeService.Hit(cream.id(), cream.name(), "适应症", "皮肤及指趾甲真菌感染。", 0.6);
        when(knowledge.search(question, 4, true)).thenReturn(List.of(contraindication, caution, special, wrongForm));
        when(knowledge.search(cream.name() + " 适应症", 4, true)).thenReturn(List.of(wrongForm, indication));
        when(client.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            String input = prompt.getUserMessage().getText();
            assertTrue(input.contains(indication.text()));
            assertTrue(input.contains(contraindication.text()));
            assertTrue(input.contains(caution.text()));
            assertFalse(input.contains(wrongForm.text()));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "说明书适应症列有皮肤及指趾甲真菌感染；咪康唑过敏者禁用。"),
                    org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build()))));
        });
        var request = new CustomerAiConsultReqVO();
        request.setContent(question);
        var received = new ArrayList<String>();
        String answer = new CustomerAiAnswerService(models, new ObjectMapper(), customerModels, knowledge)
                .answer(request, List.of(cream), received::add);
        assertEquals(String.join("", received), answer);
        assertTrue(answer.contains("依据说明书：硝酸咪康唑乳膏·适应症、禁忌、注意事项、特殊人群"));
        assertFalse(answer.contains("硝酸咪康唑栓"));
        verify(client).stream(any(Prompt.class));
    }
    @Test void missingSameProductIndicationDoesNotInferUseFromCautionOrCallTheModel() {
        var models = mock(AiModelService.class);
        var customerModels = mock(CustomerAiModelClient.class);
        var knowledge = mock(PharmacyKnowledgeService.class);
        var cream = new Product(1163202L, "硝酸咪康唑乳膏", "硝酸咪康唑乳膏", "20g",
                "厂家", "国药准字H61020001", null, null, 24, 9101L, "说明书修订日期；演示资料。");
        String question = "硝酸咪康唑乳膏主要用于什么问题？对咪康唑过敏时有哪些禁忌或注意事项？";
        var caution = new PharmacyKnowledgeService.Hit(cream.id(), cream.name(), "注意事项", "念珠菌病避免密封包扎。", 0.8);
        var wrongForm = new PharmacyKnowledgeService.Hit(1163210L, "硝酸咪康唑栓", "适应症", "其他剂型的用途。", 0.99);
        when(knowledge.search(question, 4, true)).thenReturn(List.of(caution));
        when(knowledge.search(cream.name() + " 适应症", 4, true)).thenReturn(List.of(wrongForm));
        var request = new CustomerAiConsultReqVO();
        request.setContent(question);
        var received = new ArrayList<String>();
        String answer = new CustomerAiAnswerService(models, new ObjectMapper(), customerModels, knowledge)
                .answer(request, List.of(cream), received::add);
        assertEquals(String.join("", received), answer);
        assertTrue(answer.contains("无法说明用途"));
        assertTrue(answer.contains(caution.text()));
        assertTrue(answer.contains("依据说明书：硝酸咪康唑乳膏·注意事项"));
        assertFalse(answer.contains("硝酸咪康唑栓"));
        assertFalse(answer.contains("用于念珠菌"));
        verifyNoInteractions(models, customerModels);
    }
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
        String answer=new CustomerAiAnswerService(models,new ObjectMapper(),customerModels, NoKnowledgeService.INSTANCE).answer(request,List.of(),received::add);
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
        var service = new CustomerAiAnswerService(models, new ObjectMapper(), customerModels, NoKnowledgeService.INSTANCE);
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
        String answer = new CustomerAiAnswerService(models, new ObjectMapper(), customerModels, NoKnowledgeService.INSTANCE).answer(request, List.of(product), received::add);
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
        var service=new CustomerAiAnswerService(models,new ObjectMapper(),customerModels, NoKnowledgeService.INSTANCE);var request=new CustomerAiConsultReqVO();
        request.setContent("请查询编号 57 的药品名称、价格和本店可售库存。");var received=new ArrayList<String>();
        String facts=service.answer(request,List.of(product),received::add);
        assertFalse(facts.contains("未注明"));assertTrue(facts.contains("通用名：清宁片"));assertTrue(facts.contains("生产厂家：未提供"));
        assertEquals(facts,String.join("",received));verifyNoInteractions(models,customerModels);verify(client,never()).stream(any(Prompt.class));
        request.setContent("肚子疼");received.clear();
        when(client.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("疼痛持续多久了？")))),stop));
        assertEquals("疼痛持续多久了？",service.answer(request,List.of(product),received::add));
        verify(client,times(1)).stream(any(Prompt.class));assertEquals(List.of("疼痛持续多久了？"),received);
    }
    @Test void fieldCheckingRiskRemindersArePreservedWithoutBecomingFacts() {
        var product=new Product(61L,"清宁片","清宁片","盒装",null,"批准戊号",null,null,3,8L);
        for(String reminder:List.of("儿童用药前请核对通用名、规格及禁忌，勿自行加量。",
                "孕哺期用药前请核对生产厂家、批准文号和禁忌。",
                "有过敏史时应确认通用名、规格是否一致，再咨询药师。"))
            assertEquals(reminder,CustomerAiAnswerService.safeSentence(reminder,List.of(product)));
    }
    @Test void falseFieldClausesDoNotEraseIndependentRiskReminders() {
        var product=new Product(62L,"明净片","明净片","盒装",null,"批准己号",null,null,3,8L);
        var mixed=Map.of("该药缺少通用名，儿童用药前请核对规格及禁忌，勿自行加量。","儿童用药前请核对规格及禁忌，勿自行加量。",
                "厂家是捏造公司；孕哺期请核对通用名、批准文号并咨询药师。","孕哺期请核对通用名、批准文号并咨询药师。",
                "商品信息不完整，使用前请确认通用名、规格是否一致。","使用前请确认通用名、规格是否一致。");
        for(var entry:mixed.entrySet()){
            String safe=CustomerAiAnswerService.safeSentence(entry.getKey(),List.of(product));
            assertTrue(safe.contains(entry.getValue()));assertFalse(safe.contains("缺少通用名"));
            assertFalse(safe.contains("捏造公司"));assertFalse(safe.contains("信息不完整"));
            assertTrue(safe.contains("通用名：明净片"));assertTrue(safe.contains("生产厂家：未提供"));
        }
    }
    @Test void mixedFieldAndRiskChunksReachTheSinkWithoutLosingTheReminder() {
        var models=mock(AiModelService.class);var client=mock(ChatModel.class);var customerModels=mock(CustomerAiModelClient.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(),eq(0.2),eq(1200))).thenReturn(new CustomerAiModelClient.Session(client,org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        var received=new ArrayList<String>();String reminder="儿童用药前请核对规格及禁忌，勿自行加量。";
        when(client.stream(any(Prompt.class))).thenReturn(Flux.create(sink->{
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("该药缺少通")))));assertTrue(received.isEmpty());
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("用名，儿童用药前请核对规")))));assertTrue(received.isEmpty());
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("格及禁忌，勿自行加量。")))));
            assertFalse(String.join("",received).contains("缺少通用名"));assertTrue(String.join("",received).contains(reminder));
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(""),org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build()))));sink.complete();
        }));
        var product=new Product(63L,"舒润片","舒润片","盒装",null,"批准庚号",null,null,3,8L);var req=new CustomerAiConsultReqVO();req.setContent("用药前要注意什么");
        String answer=new CustomerAiAnswerService(models,new ObjectMapper(),customerModels, NoKnowledgeService.INSTANCE).answer(req,List.of(product),received::add);
        assertEquals(String.join("",received),answer);assertTrue(answer.contains(reminder));assertFalse(answer.contains("缺少通用名"));
    }
    @Test void conditionalFieldRisksAreNotAssertionsAboutCurrentProducts() {
        var product=new Product(91L,"清宁片","清宁片","盒装",null,"批准壬号",null,null,3,8L);
        for(String conditional:List.of("如通用名缺失请先咨询药师。","若批准文号缺失请核对说明书。",
                "如果厂家信息不全请先咨询药师。","假如该药缺少规格请先核对资料。"))
            assertEquals(conditional,CustomerAiAnswerService.safeSentence(conditional,List.of(product)));
        String namedProduct=CustomerAiAnswerService.safeSentence("如意片缺少通用名。",List.of(product));
        assertFalse(namedProduct.contains("缺少通用名"));assertTrue(namedProduct.contains("通用名：清宁片"));
    }
    @Test void localFieldAssertionsKeepUnpunctuatedPrecautions() {
        var product=new Product(92L,"明净片","明净片","盒装",null,"批准癸号",null,null,3,8L);
        var cases=Map.of("该药缺少通用名且儿童用药前应咨询药师。","儿童用药前应咨询药师。",
                "批准文号缺失并且有过敏史时需先咨询药师。","有过敏史时需先咨询药师。",
                "该药缺少通用名使用前请核对禁忌。","使用前请核对禁忌。",
                "厂家是但马制药且儿童用药前应咨询药师。","儿童用药前应咨询药师。");
        for(var entry:cases.entrySet()){
            String checked=CustomerAiAnswerService.safeSentence(entry.getKey(),List.of(product));
            assertTrue(checked.contains(entry.getValue()));assertFalse(checked.contains("该药缺少通用名"));
            assertFalse(checked.contains("批准文号缺失"));assertFalse(checked.contains("但马制药"));
            assertTrue(checked.contains("通用名：明净片"));assertTrue(checked.contains("生产厂家：未提供"));
        }
    }
    @Test void conditionalAndUnpunctuatedRiskChunksAreCheckedBeforeDelivery() {
        var models=mock(AiModelService.class);var client=mock(ChatModel.class);var customerModels=mock(CustomerAiModelClient.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(),eq(0.2),eq(1200))).thenReturn(new CustomerAiModelClient.Session(client,org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        var received=new ArrayList<String>();String conditional="如通用名缺失请先咨询药师。",reminder="儿童用药前应咨询药师。";
        when(client.stream(any(Prompt.class))).thenReturn(Flux.create(sink->{
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("如通用名缺")))));assertTrue(received.isEmpty());
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("失请先咨询药师。")))));assertEquals(conditional,String.join("",received));
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("该药缺少通用名且儿童用药前应")))));
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("咨询药师。")))));
            assertFalse(String.join("",received).contains("该药缺少通用名"));assertTrue(String.join("",received).contains(reminder));
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(""),org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build()))));sink.complete();
        }));
        var product=new Product(93L,"舒润片","舒润片","盒装",null,"批准子号",null,null,3,8L);var req=new CustomerAiConsultReqVO();req.setContent("用药前要注意什么");
        String answer=new CustomerAiAnswerService(models,new ObjectMapper(),customerModels, NoKnowledgeService.INSTANCE).answer(req,List.of(product),received::add);
        assertEquals(String.join("",received),answer);assertTrue(answer.startsWith(conditional));assertTrue(answer.contains(reminder));
        assertFalse(answer.contains("该药缺少通用名"));
    }
}
