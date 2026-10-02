package cn.iocoder.yudao.module.ai.service.pharmacy;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.*;
import cn.iocoder.yudao.module.ai.dal.mysql.pharmacy.*;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.*;
import cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
@Service @RequiredArgsConstructor
public class CustomerAiHistoryService {
    private final CustomerAiTopicMapper topics;
    private final CustomerAiConsultMapper consults;
    private final CustomerAiCatalogueService catalogue;
    private final ObjectMapper json;
    public record Turn(Long id, String clientMessageId, String content, CustomerAiConsultRespVO response) { }
    public record Detail(CustomerAiTopicDO topic, List<Turn> turns, boolean hasMore) { }
    public CustomerAiTopicDO require(Long id, Long member) {
        var topic = topics.selectById(id);
        if (topic == null || !member.equals(topic.getMemberId()) || !Objects.equals(topic.getTenantId(), TenantContextHolder.getTenantId()))
            throw invalidParamException("话题不存在或无权访问");
        return topic;
    }
    public CustomerAiTopicDO create(Long storeId) {
        Long member = AppMemberAccess.requireMember(); catalogue.requireStore(storeId);
        var topic = new CustomerAiTopicDO(); topic.setMemberId(member); topic.setStoreId(storeId);
        topic.setTenantId(TenantContextHolder.getTenantId()); topic.setTitle("新话题"); topics.insert(topic); return topic;
    }
    public List<CustomerAiTopicDO> list(Long beforeId) {
        Long member = AppMemberAccess.requireMember();
        var q = new LambdaQueryWrapperX<CustomerAiTopicDO>().eq(CustomerAiTopicDO::getMemberId, member);
        if (beforeId != null) q.lt(CustomerAiTopicDO::getId, beforeId);
        return topics.selectList(q.orderByDesc(CustomerAiTopicDO::getId).last("LIMIT 30"));
    }
    public Detail detail(Long id, Long beforeId) {
        Long member = AppMemberAccess.requireMember(); var topic = require(id, member);
        var q = new LambdaQueryWrapperX<CustomerAiConsultDO>().eq(CustomerAiConsultDO::getMemberId, member).eq(CustomerAiConsultDO::getTopicId, id);
        if (beforeId != null) q.lt(CustomerAiConsultDO::getId, beforeId);
        var rows = consults.selectList(q.orderByDesc(CustomerAiConsultDO::getId).last("LIMIT 51"));
        boolean more = rows.size() > 50;
        var turns = new ArrayList<Turn>();
        for (var row : rows.subList(0, Math.min(50, rows.size()))) {
            CustomerAiConsultRespVO response = null;
            if (row.getResultJson() != null) {
                try { response = json.readValue(row.getResultJson(), CustomerAiConsultRespVO.class); }
                catch (Exception ignored) { }
            }
            turns.add(new Turn(row.getId(), row.getClientMessageId(), row.getQuestion(), response));
        }
        Collections.reverse(turns); return new Detail(topic, turns, more);
    }
    public List<CustomerAiConsultReqVO.Turn> context(Long topicId, Long member) {
        require(topicId, member);
        var rows = consults.selectList(new LambdaQueryWrapperX<CustomerAiConsultDO>()
                .eq(CustomerAiConsultDO::getMemberId, member).eq(CustomerAiConsultDO::getTopicId, topicId)
                .eq(CustomerAiConsultDO::getStatus, "SUCCESS").orderByDesc(CustomerAiConsultDO::getId).last("LIMIT 3"));
        var context = new ArrayList<CustomerAiConsultReqVO.Turn>(); Collections.reverse(rows);
        for (var row : rows) {
            if (row.getQuestion() == null) continue;
            try {
                var response = json.readValue(row.getResultJson(), CustomerAiConsultRespVO.class);
                context.add(new CustomerAiConsultReqVO.Turn("user", row.getQuestion()));
                context.add(new CustomerAiConsultReqVO.Turn("assistant", response.answer().substring(0, Math.min(1000, response.answer().length()))));
            } catch (Exception ignored) { }
        }
        return context;
    }
    @Transactional
    public void attach(CustomerAiConsultDO row, CustomerAiConsultReqVO request) {
        var topic = topics.lock(request.getTopicId(), TenantContextHolder.getTenantId(), row.getMemberId());
        if (topic == null || !topic.getStoreId().equals(request.getStoreId())) throw invalidParamException("话题不存在或门店不一致");
        row.setTopicId(topic.getId()); row.setQuestion(request.getContent().trim()); consults.insert(row);
        if ("新话题".equals(topic.getTitle())) { topic.setTitle(row.getQuestion().substring(0, Math.min(40, row.getQuestion().length()))); topics.updateById(topic); }
    }
    @Transactional
    public void delete(Long id) {
        Long member = AppMemberAccess.requireMember();
        if (topics.lock(id, TenantContextHolder.getTenantId(), member) == null) throw invalidParamException("话题不存在或无权访问");
        // Keep the request hash/unique key as a tombstone, erase medical text and block late completion.
        consults.update(null, new LambdaUpdateWrapper<CustomerAiConsultDO>().eq(CustomerAiConsultDO::getMemberId, member).eq(CustomerAiConsultDO::getTopicId, id)
                .set(CustomerAiConsultDO::getQuestion, null).set(CustomerAiConsultDO::getResultJson, null).set(CustomerAiConsultDO::getStatus, "DELETED"));
        topics.erase(id, TenantContextHolder.getTenantId(), member);
    }
    @Transactional
    public void clear() {
        Long member = AppMemberAccess.requireMember();
        for (var topic : topics.selectList(new LambdaQueryWrapperX<CustomerAiTopicDO>().eq(CustomerAiTopicDO::getMemberId, member))) delete(topic.getId());
        consults.update(null, new LambdaUpdateWrapper<CustomerAiConsultDO>().eq(CustomerAiConsultDO::getMemberId, member).isNull(CustomerAiConsultDO::getTopicId)
                .set(CustomerAiConsultDO::getQuestion, null).set(CustomerAiConsultDO::getResultJson, null).set(CustomerAiConsultDO::getStatus, "DELETED"));
    }
}
