package cn.iocoder.yudao.module.ai.service.pharmacy;
import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultReqVO;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO;
import cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.CustomerAiConsultDO;
import cn.iocoder.yudao.module.ai.dal.mysql.pharmacy.CustomerAiConsultMapper;
import cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
@Service @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class CustomerAiConsultService {
    private final CustomerAiConsultMapper mapper;
    private final CustomerAiIntentService intent;
    private final CustomerAiCatalogueService catalogue;
    private final ObjectMapper json;
    private final CustomerAiAnswerService answers;
    private final CustomerAiHistoryService history;
    static final String NOTICE = "我可以解释常见问药问题和查询店内商品，但不能确诊、开处方或代下单。需要人工帮助时可以联系本店药师。";
    public CustomerAiConsultRespVO consult(CustomerAiConsultReqVO request) {
        return consult(request, text -> { });
    }
    private CustomerAiConsultRespVO consult(CustomerAiConsultReqVO request, java.util.function.Consumer<String> sink) {
        Long member = AppMemberAccess.requireMember();
        catalogue.requireStore(request.getStoreId());
        if (request.getTopicId() != null) {
            var topic = history.require(request.getTopicId(), member);
            if (!topic.getStoreId().equals(request.getStoreId())) throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException("话题门店不一致");
        }
        String hash = SecureUtil.sha256(request.getStoreId() + "\n" + request.getContent().trim()
                + (request.getTopicId() != null ? "\ntopic:" + request.getTopicId() : (request.getContext() == null || request.getContext().isEmpty() ? "" : "\n" + write(request.getContext()))));
        var existing = mapper.find(member, request.getClientMessageId());
        if (existing != null) return replay(existing, hash, request);
        var row = new CustomerAiConsultDO();
        row.setTenantId(TenantContextHolder.getTenantId());row.setMemberId(member);row.setStoreId(request.getStoreId());
        row.setClientMessageId(request.getClientMessageId());row.setRequestHash(hash);row.setStatus("PENDING");
        row.setExpiresAt(LocalDateTime.now().plusSeconds(90));
        try { if (request.getTopicId() == null) mapper.insert(row); else history.attach(row, request); }
        catch (DuplicateKeyException e) { return replay(mapper.find(member, request.getClientMessageId()), hash, request); }
        CustomerAiConsultRespVO response;
        String phase = "intent";
        try {
            var context = request.getTopicId() != null ? history.context(request.getTopicId(), member)
                    : request.getContext() == null ? List.<CustomerAiConsultReqVO.Turn>of() : request.getContext();
            request.setContext(context);
            var decision = context.isEmpty() ? intent.resolve(request.getContent()) : intent.resolve(request.getContent(), context);
            if ("refuse".equals(decision.action())) {
                sink.accept(NOTICE);
                response = response(request, "SUCCESS", NOTICE, List.of(), false);
            } else if ("urgent".equals(decision.action())) {
                String urgent = "这些描述可能需要及时就医。如果有突发剧烈疼痛、胸痛、呼吸困难或出血，请尽快前往急诊或联系当地急救，不要等待 AI 或模拟医生咨询。";
                sink.accept(urgent);
                response = response(request, "SUCCESS", urgent, List.of(), false);
            } else {
                CustomerAiCatalogueService.Result result;
                if ("advice".equals(decision.action())) result = catalogue.adviceCandidates(request.getStoreId(), decision.keyword());
                else if ("chat".equals(decision.action())) result = new CustomerAiCatalogueService.Result(List.of(), false);
                else result = catalogue.query(request.getStoreId(), decision.keyword(), decision.drugId());
                phase = "answer";
                String answer = answers.answer(request, result.products(), sink);
                if (result.truncated()) { String note = "\n匹配商品较多，请缩小查询范围。"; answer += note; sink.accept(note); }
                response = response(request, "SUCCESS", answer, result.products(), result.truncated());
            }
        } catch (Exception e) {
            log.warn("Customer AI failed: phase={}, category={}", phase, e.getClass().getSimpleName());
            // Provider errors may contain credentials/URLs. Never return or persist their raw text.
            response = response(request, "FAILED", "AI 查询暂时不可用，请稍后重试，也可以直接搜索药品。", List.of(), false);
        }
        if (mapper.finish(row.getId(), response.status(), write(response)) != 1)
            return response(request, "FAILED", "本次请求已失效，请重新发起咨询。", List.of(), false);
        return response;
    }
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(CustomerAiConsultReqVO request) {
        // Validate identity/store on the request thread before committing SSE headers.
        AppMemberAccess.requireMember(); catalogue.requireStore(request.getStoreId());
        if (request.getTopicId() != null) history.require(request.getTopicId(), AppMemberAccess.requireMember());
        Long tenant = TenantContextHolder.getTenantId();
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(75000L);
        var task = new java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable>();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable stop = () -> { closed.set(true); var running = task.get(); if (running != null) running.dispose(); };
        emitter.onTimeout(stop); emitter.onError(e -> stop.run()); emitter.onCompletion(stop);
        task.set(reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
            var security = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            security.setAuthentication(authentication);
            org.springframework.security.core.context.SecurityContextHolder.setContext(security);
            TenantContextHolder.clear();
            TenantContextHolder.setTenantId(tenant);
            try {
                send(emitter, "start", java.util.Map.of("clientMessageId", request.getClientMessageId()));
                var response = consult(request, text -> {
                    if (closed.get()) throw new java.util.concurrent.CancellationException();
                    send(emitter, "delta", java.util.Map.of("text", text));
                });
                if (!closed.get()) send(emitter, "done", response);
                emitter.complete();
            } catch (Exception e) {
                if (!closed.get()) {
                    try { send(emitter, "error", java.util.Map.of("message", "本次咨询暂不可用，请稍后重试。")); }
                    catch (Exception ignored) { }
                    emitter.complete();
                }
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext(); TenantContextHolder.clear();
            }
        }));
        if (closed.get()) task.get().dispose();
        return emitter;
    }
    private void send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter, String event, Object data) {
        try { emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name(event).data(data)); }
        catch (java.io.IOException e) { throw new java.util.concurrent.CancellationException(); }
    }
    private CustomerAiConsultRespVO replay(CustomerAiConsultDO row, String hash, CustomerAiConsultReqVO request) {
        if (row == null || !hash.equals(row.getRequestHash())) throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException("重复请求编号与原内容不一致");
        if ("DELETED".equals(row.getStatus())) return response(request, "FAILED", "该话题已删除，请开启新话题。", List.of(), false);
        if ("PENDING".equals(row.getStatus())) {
            if (row.getExpiresAt().isBefore(LocalDateTime.now())) {
                var failure = response(request, "FAILED", "本次请求超时，请重新发起咨询。", List.of(), false);
                if (mapper.finish(row.getId(), "FAILED", write(failure)) == 1) return failure;
                // A concurrent completion may have won; return its persisted result.
                var winner = mapper.find(row.getMemberId(), row.getClientMessageId());
                if (winner != null && "PENDING".equals(winner.getStatus()))
                    return response(request, "PENDING", "正在查询，请稍候。", List.of(), false);
                return replay(winner, hash, request);
            }
            return response(request, "PENDING", "正在查询，请稍候。", List.of(), false);
        }
        try { return json.readValue(row.getResultJson(), CustomerAiConsultRespVO.class); }
        catch (Exception e) { throw new IllegalStateException("查询记录暂不可用"); }
    }
    private CustomerAiConsultRespVO response(CustomerAiConsultReqVO request, String status, String answer,
            List<CustomerAiConsultRespVO.Product> products, boolean truncated) {
        return new CustomerAiConsultRespVO(request.getClientMessageId(), status, answer,
                "所选门店药品档案及可售库存；商品、价格和库存来自数据库，最终以实际下单校验为准", LocalDateTime.now(), truncated, products);
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("查询记录保存失败"); }
    }
}
