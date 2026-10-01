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
@Service @RequiredArgsConstructor
public class CustomerAiConsultService {
    private final CustomerAiConsultMapper mapper;
    private final CustomerAiIntentService intent;
    private final CustomerAiCatalogueService catalogue;
    private final ObjectMapper json;
    static final String NOTICE = "AI 仅辅助查询店内商品档案，不诊断、不开方或代下单。用药问题请咨询药师。";
    public CustomerAiConsultRespVO consult(CustomerAiConsultReqVO request) {
        Long member = AppMemberAccess.requireMember();
        catalogue.requireStore(request.getStoreId());
        String hash = SecureUtil.sha256(request.getStoreId() + "\n" + request.getContent().trim());
        var existing = mapper.find(member, request.getClientMessageId());
        if (existing != null) return replay(existing, hash, request);
        var row = new CustomerAiConsultDO();
        row.setTenantId(TenantContextHolder.getTenantId());row.setMemberId(member);row.setStoreId(request.getStoreId());
        row.setClientMessageId(request.getClientMessageId());row.setRequestHash(hash);row.setStatus("PENDING");
        row.setExpiresAt(LocalDateTime.now().plusSeconds(90));
        try { mapper.insert(row); }
        catch (DuplicateKeyException e) { return replay(mapper.find(member, request.getClientMessageId()), hash, request); }
        CustomerAiConsultRespVO response;
        try {
            var decision = intent.resolve(request.getContent());
            if ("refuse".equals(decision.action())) response = response(request, "SUCCESS", NOTICE, List.of(), false);
            else {
                var result = catalogue.query(request.getStoreId(), decision.keyword(), decision.drugId());
                String answer = result.products().isEmpty() ? "当前查询范围内未找到符合条件且有货的普通非处方商品。" : "以下为所选门店的商品档案查询结果，请自主查看详情。";
                if (result.truncated()) answer += "查询范围已截断，请用更具体的药品名称继续搜索。";
                response = response(request, "SUCCESS", answer + NOTICE, result.products(), result.truncated());
            }
        } catch (Exception e) {
            // Provider errors may contain credentials/URLs. Never return or persist their raw text.
            response = response(request, "FAILED", "AI 查询暂时不可用，请稍后重试，也可以直接搜索药品。", List.of(), false);
        }
        if (mapper.finish(row.getId(), response.status(), write(response)) != 1)
            return response(request, "FAILED", "本次请求已失效，请重新发起咨询。", List.of(), false);
        return response;
    }
    private CustomerAiConsultRespVO replay(CustomerAiConsultDO row, String hash, CustomerAiConsultReqVO request) {
        if (row == null || !hash.equals(row.getRequestHash())) throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException("重复请求编号与原内容不一致");
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
