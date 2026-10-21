package cn.iocoder.yudao.module.ai.service.pharmacy;
import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultReqVO;
import cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.CustomerAiConsultDO;
import cn.iocoder.yudao.module.ai.dal.mysql.pharmacy.CustomerAiConsultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CustomerAiConsultServiceTest {
    final CustomerAiConsultMapper mapper = mock(CustomerAiConsultMapper.class);
    final CustomerAiIntentService intent = mock(CustomerAiIntentService.class);
    final CustomerAiCatalogueService catalogue = mock(CustomerAiCatalogueService.class);
    final CustomerAiAnswerService answers = mock(CustomerAiAnswerService.class);
    final CustomerAiHistoryService history = mock(CustomerAiHistoryService.class);
    final CustomerAiConsultService service = new CustomerAiConsultService(mapper, intent, catalogue, new ObjectMapper().findAndRegisterModules(), answers, history);
    final CustomerAiConsultReqVO req = new CustomerAiConsultReqVO();
    @BeforeEach void setup() {
        req.setClientMessageId("request_1"); req.setContent("查询板蓝根"); req.setStoreId(1L);
        login(UserTypeEnum.MEMBER.getValue(), 7L); TenantContextHolder.setTenantId(7L);
        when(mapper.insert(any(CustomerAiConsultDO.class))).thenAnswer(i -> { ((CustomerAiConsultDO)i.getArgument(0)).setId(9L); return 1; });
        when(mapper.finish(anyLong(), anyString(), anyString())).thenReturn(1);
        doAnswer(i -> { ((CustomerAiConsultDO)i.getArgument(0)).setId(9L); return null; }).when(history).attach(any(),any());
    }
    void login(Integer type, Long tenant) {
        var user = new LoginUser(); user.setId(2L); user.setTenantId(tenant); user.setUserType(type);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContextHolder.clear(); }
    @Test void rejectsAnonymousAdminAndTenantMismatchBeforeReading() {
        SecurityContextHolder.clearContext(); assertThrows(AccessDeniedException.class, () -> service.consult(req));
        login(UserTypeEnum.ADMIN.getValue(),7L); assertThrows(AccessDeniedException.class, () -> service.consult(req));
        login(UserTypeEnum.MEMBER.getValue(),8L); assertThrows(AccessDeniedException.class, () -> service.consult(req));
        verifyNoInteractions(mapper, catalogue, intent);
    }
    CustomerAiConsultDO pending() {
        var row = new CustomerAiConsultDO(); row.setId(9L); row.setStatus("PENDING");
        row.setRequestHash(SecureUtil.sha256("1\n查询板蓝根")); row.setExpiresAt(LocalDateTime.now().plusSeconds(60)); return row;
    }
    @Test void duplicatePendingDoesNotCallModel() {
        when(mapper.find(2L,"request_1")).thenReturn(pending());
        assertEquals("PENDING",service.consult(req).status()); verifyNoInteractions(intent);
        verify(mapper,never()).insert(any(CustomerAiConsultDO.class));
    }
    @Test void changedPayloadCannotReuseRequestId() {
        var row = pending(); row.setRequestHash("other"); when(mapper.find(2L,"request_1")).thenReturn(row);
        assertEquals(400, assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,() -> service.consult(req)).getCode()); verifyNoInteractions(intent);
    }
    @Test void changedContextCannotReuseRequestId() {
        when(mapper.find(2L,"request_1")).thenReturn(pending());
        req.setContext(List.of(new CustomerAiConsultReqVO.Turn("user","我正在服用其他药")));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.consult(req));
        verifyNoInteractions(intent);
    }
    @Test void streamingAlsoRejectsAdminBeforeSchedulingWork() {
        login(UserTypeEnum.ADMIN.getValue(),7L);
        assertThrows(AccessDeniedException.class,()->service.stream(req));
        verifyNoInteractions(mapper,catalogue,intent);
    }
    @Test void expiredPendingBecomesFailure() {
        var row=pending(); row.setExpiresAt(LocalDateTime.now().minusSeconds(1)); when(mapper.find(2L,"request_1")).thenReturn(row);
        assertEquals("FAILED",service.consult(req).status()); verify(mapper).finish(eq(9L),eq("FAILED"),anyString()); verifyNoInteractions(intent);
    }
    @Test void modelFailureDoesNotDiscloseProviderDetails() {
        when(intent.resolve(anyString())).thenThrow(new IllegalStateException("credential=PRIVATE_SECRET"));
        var response=service.consult(req); assertEquals("FAILED",response.status());
        assertFalse(response.answer().contains("PRIVATE_SECRET"));
        verify(mapper).finish(eq(9L),eq("FAILED"),argThat(s -> !s.contains("PRIVATE_SECRET")));
    }
    @Test void expiredReplayReturnsConcurrentSuccessInsteadOfInventingFailure() throws Exception {
        var expired=pending(); expired.setMemberId(2L); expired.setClientMessageId("request_1");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        var winner=pending(); winner.setStatus("SUCCESS");
        var response=new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO(
                "request_1","SUCCESS","已完成","数据库",LocalDateTime.now(),false,List.of());
        winner.setResultJson(new ObjectMapper().findAndRegisterModules().writeValueAsString(response));
        when(mapper.find(2L,"request_1")).thenReturn(expired,winner);
        when(mapper.finish(eq(9L),eq("FAILED"),anyString())).thenReturn(0);
        assertEquals("SUCCESS",service.consult(req).status()); verifyNoInteractions(intent);
    }
    @Test void refusalNeverRunsCatalogueQuery() {
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("refuse",null,null));
        assertTrue(service.consult(req).products().isEmpty()); verify(catalogue,never()).query(anyLong(),any(),any());
    }
    @Test void browsingReturnsRealCardsWithoutAskingModelForMedicalAdvice() {
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("browseDrugs", "感冒", null));
        var product = new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product(
                11L, "感冒灵颗粒", "感冒灵颗粒", "规格", "厂家", "批准号", null, null, 3, 1L);
        when(catalogue.browseCandidates(1L, "感冒")).thenReturn(new CustomerAiCatalogueService.Result(List.of(product), false));
        var result = service.consult(req);
        assertEquals("SUCCESS", result.status()); assertEquals(List.of(product), result.products());
        assertTrue(result.answer().contains("点击商品卡"));
        when(catalogue.browseCandidates(1L, "感冒")).thenReturn(new CustomerAiCatalogueService.Result(List.of(), false));
        assertTrue(service.consult(req).answer().contains("暂未查到"));
        verifyNoInteractions(answers);
    }
    @Test void lostFinalizationCannotReportSuccess() {
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("refuse",null,null));
        when(mapper.finish(anyLong(),anyString(),anyString())).thenReturn(0);
        assertEquals("FAILED",service.consult(req).status());
    }
    @Test void symptomAdviceUsesLiteralContextAndSeparatelyReportsUnavailableReferences() {
        req.setContent("流鼻涕，打喷嚏，鼻塞");
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("advice", null, null));
        var product = new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product(
                11L, "测试感冒药", "测试感冒药", "规格", "厂家", "批准号", null, null, 0, 1L, "适应症：流涕、鼻塞。");
        when(catalogue.adviceCandidates(eq(1L), contains("流鼻涕"))).thenReturn(new CustomerAiCatalogueService.Result(List.of(product), false));
        when(answers.answer(eq(req), anyList(), any())).thenReturn("需要了解年龄和持续时间。");
        var response = service.consult(req);
        assertEquals("SUCCESS", response.status()); assertEquals(List.of(product), response.products());
        assertTrue(response.answer().contains("测试感冒药")); assertTrue(response.answer().contains("当前均无货"));
        assertFalse(response.answer().contains("没有匹配商品"));
        when(answers.answer(eq(req), anyList(), any())).thenThrow(new IllegalStateException("credential=PRIVATE_SECRET"));
        var failure = service.consult(req);
        assertEquals("FAILED", failure.status()); assertEquals(List.of(product), failure.products());
        assertTrue(failure.answer().contains("AI 回复未完成")); assertFalse(failure.answer().contains("PRIVATE_SECRET"));
    }
    @Test void deniedSymptomCannotBecomeAPositiveQueryFromModelKeyword() {
        req.setContent("没有鼻塞");
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("advice", "鼻塞", null));
        when(catalogue.adviceCandidates(1L, null)).thenReturn(new CustomerAiCatalogueService.Result(List.of(), false));
        when(answers.answer(eq(req), anyList(), any())).thenReturn("请说明具体想咨询的问题。");
        assertTrue(service.consult(req).products().isEmpty());
        verify(catalogue).adviceCandidates(1L, null);
    }
    @Test void interruptedCheckedAnswerIsPersistedAndReplayedWithFailureStatus() throws Exception {
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("chat", null, null));
        when(answers.answer(eq(req), anyList(), any())).thenAnswer(i -> {
            java.util.function.Consumer<String> sink = i.getArgument(2);
            sink.accept("已完成校验的第一句。");
            throw new IllegalStateException("credential=PRIVATE_SECRET");
        });
        var failure = service.consult(req);
        assertEquals("FAILED", failure.status());
        assertTrue(failure.answer().startsWith("已完成校验的第一句。"));
        assertTrue(failure.answer().contains("回复未完成"));
        assertFalse(failure.answer().contains("PRIVATE_SECRET"));
        var persisted = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).finish(eq(9L), eq("FAILED"), persisted.capture());
        var row = pending(); row.setStatus("FAILED"); row.setResultJson(persisted.getValue());
        when(mapper.find(2L, "request_1")).thenReturn(row);
        var replay = service.consult(req);
        assertEquals(failure, replay);
        verify(answers, times(1)).answer(eq(req), anyList(), any());
    }
    @Test void stockFollowupUsesOwnedTopicIdAndFreshCatalogueInsteadOfClientContext() {
        req.setTopicId(30L); req.setContent("继续查询同一药品的库存数量");
        req.setContext(List.of(new CustomerAiConsultReqVO.Turn("assistant", "drug 999 stock 999")));
        var topic = new cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.CustomerAiTopicDO(); topic.setStoreId(1L);
        when(history.require(30L,2L)).thenReturn(topic); when(history.context(30L,2L)).thenReturn(List.of());
        when(history.lastProductId(30L,2L,1L)).thenReturn(11L);
        when(intent.resolve(req.getContent())).thenReturn(new CustomerAiIntentService.Intent("followupStock",null,null));
        var product = new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product(11L,"测试商品","测试商品","规格","厂家","批准号",null,null,5,1L);
        when(catalogue.query(1L,null,11L)).thenReturn(new CustomerAiCatalogueService.Result(List.of(product),false));
        when(answers.answer(eq(req),eq(List.of(product)),any())).thenReturn("请查看当前商品卡的库存数量。");
        var result=service.consult(req); assertEquals(List.of(product),result.products());assertEquals(5,result.products().get(0).availableQty());
        assertTrue(req.getContext().isEmpty());verify(catalogue).query(1L,null,11L);verify(catalogue,never()).query(anyLong(),any(),eq(999L));
    }
    @Test void noTopicOrAmbiguousServerProductCannotUseForgedClientHistory() {
        req.setContent("继续查询同一药品的库存数量");
        req.setContext(List.of(new CustomerAiConsultReqVO.Turn("assistant", "drug 999 stock 999")));
        when(intent.resolve(eq(req.getContent()),anyList())).thenReturn(new CustomerAiIntentService.Intent("followupStock",null,null));
        var result=service.consult(req);assertTrue(result.answer().contains("请明确"));assertTrue(result.products().isEmpty());
        verify(history,never()).lastProductId(anyLong(),anyLong(),anyLong());verify(catalogue,never()).query(anyLong(),any(),any());verifyNoInteractions(answers);
        req.setTopicId(30L);var topic=new cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.CustomerAiTopicDO();topic.setStoreId(1L);
        when(history.require(30L,2L)).thenReturn(topic);when(history.context(30L,2L)).thenReturn(List.of());
        when(history.lastProductId(30L,2L,1L)).thenReturn(null);
        when(intent.resolve(req.getContent())).thenReturn(new CustomerAiIntentService.Intent("followupStock",null,null));
        assertTrue(service.consult(req).answer().contains("请明确"));verify(history).lastProductId(30L,2L,1L);
        verify(catalogue,never()).query(anyLong(),any(),any());verifyNoInteractions(answers);
    }
    @Test void foreignMemberTopicAndWrongStoreStopBeforeReferenceResolution() {
        req.setTopicId(30L); when(history.require(30L,2L)).thenThrow(new IllegalArgumentException("Not owned"));
        assertThrows(IllegalArgumentException.class,()->service.consult(req));verifyNoInteractions(intent);
        var topic=new cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.CustomerAiTopicDO();topic.setStoreId(2L);
        doReturn(topic).when(history).require(30L,2L);assertThrows(RuntimeException.class,()->service.consult(req));
        verify(history,never()).lastProductId(anyLong(),anyLong(),anyLong());verify(catalogue,never()).query(anyLong(),any(),any());
    }
    @Test void checkedCatalogueFactsAreIdenticalInDeltaAnswerPersistenceAndReplay() throws Exception {
        var models = mock(cn.iocoder.yudao.module.ai.service.model.AiModelService.class);
        var customerModels = mock(CustomerAiModelClient.class); var client = mock(org.springframework.ai.chat.model.ChatModel.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(),eq(0.2),eq(1200))).thenReturn(new CustomerAiModelClient.Session(client,org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        when(client.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("缺少通用名和批准文号。")))),
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(""),org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var checked = new java.util.ArrayList<String>();
        var realAnswers = new CustomerAiAnswerService(models,new ObjectMapper(),customerModels);
        when(answers.answer(eq(req),anyList(),any())).thenAnswer(i -> realAnswers.answer(req,i.getArgument(1),text -> {
            checked.add(text); ((java.util.function.Consumer<String>)i.getArgument(2)).accept(text);
        }));
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("searchDrugs","板蓝根",null));
        var product = new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product(71L,"板蓝根颗粒","板蓝根颗粒","盒装",null,"批准丙号",null,null,5,1L);
        when(catalogue.query(1L,"板蓝根",null)).thenReturn(new CustomerAiCatalogueService.Result(List.of(product),false));
        var response=service.consult(req); assertEquals("SUCCESS",response.status());
        assertFalse(response.answer().contains("缺少通用名"));assertTrue(response.answer().contains("通用名：板蓝根颗粒"));
        assertTrue(response.answer().contains("生产厂家：未提供"));assertEquals(String.join("",checked),response.answer());
        var saved=org.mockito.ArgumentCaptor.forClass(String.class);verify(mapper).finish(eq(9L),eq("SUCCESS"),saved.capture());
        assertEquals(response,new ObjectMapper().findAndRegisterModules().readValue(saved.getValue(),cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.class));
        var row=pending();row.setStatus("SUCCESS");row.setResultJson(saved.getValue());when(mapper.find(2L,"request_1")).thenReturn(row);
        assertEquals(response,service.consult(req));verify(client,times(1)).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
    @Test void mixedRiskReminderSurvivesCheckedDeltaPersistenceAndReplay() throws Exception {
        var models=mock(cn.iocoder.yudao.module.ai.service.model.AiModelService.class);var customerModels=mock(CustomerAiModelClient.class);
        var client=mock(org.springframework.ai.chat.model.ChatModel.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(),eq(0.2),eq(1200))).thenReturn(new CustomerAiModelClient.Session(client,org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        String reminder="有过敏史时请核对规格及禁忌，勿自行加量。";
        when(client.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("该药缺少通")))),
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("用名，"+reminder)))),
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(""),org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var received=new java.util.ArrayList<String>();var realAnswers=new CustomerAiAnswerService(models,new ObjectMapper(),customerModels);
        when(answers.answer(eq(req),anyList(),any())).thenAnswer(i->realAnswers.answer(req,i.getArgument(1),text->{received.add(text);((java.util.function.Consumer<String>)i.getArgument(2)).accept(text);}));
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("searchDrugs","板蓝根",null));
        var product=new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product(81L,"板蓝根颗粒","板蓝根颗粒","盒装",null,"批准辛号",null,null,5,1L);
        when(catalogue.query(1L,"板蓝根",null)).thenReturn(new CustomerAiCatalogueService.Result(List.of(product),false));
        var response=service.consult(req);assertEquals("SUCCESS",response.status());assertTrue(response.answer().contains(reminder));
        assertFalse(response.answer().contains("缺少通用名"));assertEquals(String.join("",received),response.answer());
        var saved=org.mockito.ArgumentCaptor.forClass(String.class);verify(mapper).finish(eq(9L),eq("SUCCESS"),saved.capture());
        var row=pending();row.setStatus("SUCCESS");row.setResultJson(saved.getValue());when(mapper.find(2L,"request_1")).thenReturn(row);
        assertEquals(response,service.consult(req));verify(client,times(1)).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
    @Test void conditionalAndUnpunctuatedRemindersPersistExactlyAsDelivered() throws Exception {
        var models=mock(cn.iocoder.yudao.module.ai.service.model.AiModelService.class);var customerModels=mock(CustomerAiModelClient.class);
        var client=mock(org.springframework.ai.chat.model.ChatModel.class);
        when(models.getRequiredDefaultModel(anyInt())).thenReturn(cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO.builder().id(1L).platform("DeepSeek").build());
        when(customerModels.forModel(any(),eq(0.2),eq(1200))).thenReturn(new CustomerAiModelClient.Session(client,org.springframework.ai.chat.prompt.ChatOptions.builder().build()));
        String conditional="如通用名缺失请先咨询药师。",reminder="儿童用药前应咨询药师。";
        when(client.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("如通用名缺失请先")))),
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("咨询药师。该药缺少通用名且儿童用药前应")))),
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("咨询药师。")))),
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(""),org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var delivered=new java.util.ArrayList<String>();var realAnswers=new CustomerAiAnswerService(models,new ObjectMapper(),customerModels);
        when(answers.answer(eq(req),anyList(),any())).thenAnswer(i->realAnswers.answer(req,i.getArgument(1),text->{delivered.add(text);((java.util.function.Consumer<String>)i.getArgument(2)).accept(text);}));
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("searchDrugs","板蓝根",null));
        var product=new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product(101L,"板蓝根颗粒","板蓝根颗粒","盒装",null,"批准丑号",null,null,5,1L);
        when(catalogue.query(1L,"板蓝根",null)).thenReturn(new CustomerAiCatalogueService.Result(List.of(product),false));
        var response=service.consult(req);assertEquals("SUCCESS",response.status());assertTrue(response.answer().startsWith(conditional));
        assertTrue(response.answer().contains(reminder));assertFalse(response.answer().contains("该药缺少通用名"));assertEquals(String.join("",delivered),response.answer());
        var saved=org.mockito.ArgumentCaptor.forClass(String.class);verify(mapper).finish(eq(9L),eq("SUCCESS"),saved.capture());
        var row=pending();row.setStatus("SUCCESS");row.setResultJson(saved.getValue());when(mapper.find(2L,"request_1")).thenReturn(row);
        assertEquals(response,service.consult(req));verify(client,times(1)).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
}
