package cn.iocoder.yudao.module.pharmacy.service.consultation;

import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.consultation.ConsultationRequests;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.consultation.*;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.EmployeeDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.consultation.*;
import cn.iocoder.yudao.module.pharmacy.service.base.*;
import cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess;
import cn.iocoder.yudao.module.pharmacy.service.permission.PharmacyStoreDataAccess;
import cn.iocoder.yudao.module.pharmacy.service.prescription.PrescriptionStaffAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;

/** Durable text messages. Database row locks serialize claim/send/read; no broadcast or automatic reply. */
@Service @RequiredArgsConstructor
public class ConsultationService {
    private final ConsultationMapper conversations;
    private final ConsultationMessageMapper messages;
    private final StoreService stores;
    private final EmployeeService employees;
    private final PharmacyStoreDataAccess storeAccess;
    private final PrescriptionStaffAccess pharmacists;
    public record Conversation(Long id,Long storeId,String kind,boolean attended,boolean mine,Long unread,LocalDateTime updateTime) { }
    public record Message(Long id,String senderType,String senderName,String content,LocalDateTime createTime) { }
    public record Thread(Conversation conversation,List<Message> list,boolean hasMore) { }

    @Transactional(rollbackFor=Exception.class)
    public Long open(ConsultationRequests.Open request) {
        Long member=AppMemberAccess.requireMember(); requireActiveStore(request.storeId());
        if(!Set.of("PHARMACIST","SERVICE").contains(request.kind()))throw invalidParamException("咨询类型无效");
        var existing=conversations.owned(member,request.storeId(),request.kind());if(existing!=null)return existing.getId();
        var row=new ConsultationDO();row.setTenantId(TenantContextHolder.getTenantId());row.setMemberId(member);
        row.setStoreId(request.storeId());row.setKind(request.kind());row.setLastMessageId(0L);row.setMemberReadId(0L);row.setStaffReadId(0L);
        try { conversations.insert(row);return row.getId(); }
        catch(DuplicateKeyException e) {var winner=conversations.ownedAfterConflict(member,request.storeId(),request.kind());if(winner==null)throw e;return winner.getId();}
    }
    public PageResult<Conversation> page(boolean staff,Long store,Integer pageNo) {
        var query=new LambdaQueryWrapperX<ConsultationDO>();
        if(staff){var employee=requireEmployee(store);query.eq(ConsultationDO::getStoreId,employee.getStoreId())
                .and(q->q.isNull(ConsultationDO::getStaffId).or().eq(ConsultationDO::getStaffId,employee.getUserId()));
            boolean qualified=Objects.equals(employee.getPosition(),2)&&employee.getPharmacistNo()!=null&&!employee.getPharmacistNo().isBlank()
                    &&employee.getLicenseExpire()!=null&&!employee.getLicenseExpire().isBefore(java.time.LocalDate.now());
            if(!qualified)query.eq(ConsultationDO::getKind,"SERVICE");
        }else query.eq(ConsultationDO::getMemberId,AppMemberAccess.requireMember());
        var page=new PageParam();page.setPageNo(pageNo);page.setPageSize(20);
        var result=conversations.selectPage(page,query.orderByDesc(ConsultationDO::getUpdateTime).orderByDesc(ConsultationDO::getId));
        return new PageResult<>(result.getList().stream().map(c->summary(c,staff)).toList(),result.getTotal());
    }
    @Transactional(rollbackFor=Exception.class)
    public boolean claim(Long id) {
        var row=authorize(id,true,true);Long staff=SecurityFrameworkUtils.getLoginUserId();
        if(row.getStaffId()!=null&&!Objects.equals(row.getStaffId(),staff))throw new AccessDeniedException("会话已由其他接待人员领取");
        if(row.getStaffId()==null){row.setStaffId(staff);conversations.updateById(row);}return true;
    }
    @Transactional(rollbackFor=Exception.class)
    public Thread thread(Long id,boolean staff,Long after,Long before) {
        // Keep authorization and message read atomic with claim/send on the same row.
        var row=authorize(id,staff,true);
        if(after>0&&before!=null)throw invalidParamException("消息分页方向无效");
        var query=new LambdaQueryWrapperX<ConsultationMessageDO>().eq(ConsultationMessageDO::getConversationId,id);
        boolean backwards=after==0;
        if(after>0)query.gt(ConsultationMessageDO::getId,after).orderByAsc(ConsultationMessageDO::getId);
        else {if(before!=null)query.lt(ConsultationMessageDO::getId,before);query.orderByDesc(ConsultationMessageDO::getId);}
        var rows=new ArrayList<>(messages.selectList(query.last("LIMIT 51")));boolean more=rows.size()>50;
        if(more)rows.remove(50);if(backwards)Collections.reverse(rows);
        return new Thread(summary(row,staff),rows.stream().map(m->new Message(m.getId(),m.getSenderType(),m.getSenderName(),m.getContent(),m.getCreateTime())).toList(),more);
    }
    @Transactional(rollbackFor=Exception.class)
    public Long send(ConsultationRequests.Send request,boolean staff) {
        var row=authorize(request.conversationId(),staff,true);requireActiveStore(row.getStoreId());
        Long sender=staff?SecurityFrameworkUtils.getLoginUserId():AppMemberAccess.requireMember();String type=staff?"STAFF":"MEMBER";
        String content=request.content().trim();if(content.isEmpty()||content.length()>1000)throw invalidParamException("请输入 1–1000 字文字");
        String hash=cn.hutool.crypto.digest.DigestUtil.sha256Hex(content);
        var previous=messages.selectOne(new LambdaQueryWrapperX<ConsultationMessageDO>().eq(ConsultationMessageDO::getConversationId,row.getId())
                .eq(ConsultationMessageDO::getSenderId,sender).eq(ConsultationMessageDO::getSenderType,type).eq(ConsultationMessageDO::getClientRequestId,request.clientRequestId()));
        if(previous!=null){if(!Objects.equals(previous.getContentHash(),hash))throw invalidParamException("重复编号的内容已变化");return previous.getId();}
        if(staff&&!Objects.equals(row.getStaffId(),sender))throw new AccessDeniedException("请先领取会话再回复");
        var message=new ConsultationMessageDO();message.setTenantId(TenantContextHolder.getTenantId());message.setConversationId(row.getId());
        message.setSenderId(sender);message.setSenderType(type);message.setContent(content);message.setContentHash(hash);message.setClientRequestId(request.clientRequestId());
        message.setSenderName(staff?requireEmployee(row.getStoreId()).getEmpName():"顾客");messages.insert(message);
        row.setLastMessageId(message.getId());conversations.updateById(row);return message.getId();
    }
    @Transactional(rollbackFor=Exception.class)
    public boolean read(ConsultationRequests.Read request,boolean staff) {
        var row=authorize(request.conversationId(),staff,true);
        if(staff&&!Objects.equals(row.getStaffId(),SecurityFrameworkUtils.getLoginUserId()))throw new AccessDeniedException("仅接待本人可更新已读");
        var message=messages.selectById(request.throughId());
        if(message==null||!Objects.equals(message.getConversationId(),row.getId()))throw new AccessDeniedException("消息不属于该会话");
        if(staff)row.setStaffReadId(Math.max(row.getStaffReadId(),message.getId()));else row.setMemberReadId(Math.max(row.getMemberReadId(),message.getId()));
        conversations.updateById(row);return true;
    }
    private ConsultationDO authorize(Long id,boolean staff,boolean lock) {
        // Authenticate before any private row lookup; tenant interceptor remains mandatory.
        if(staff)storeAccess.scopeStoreId(null);else AppMemberAccess.requireMember();
        var row=lock?conversations.lock(id):conversations.selectById(id);
        if(row==null)throw new AccessDeniedException("无会话访问权限");
        if(staff){requireEmployee(row.getStoreId());if("PHARMACIST".equals(row.getKind()))pharmacists.requirePharmacist(row.getStoreId());
            if(row.getStaffId()!=null&&!Objects.equals(row.getStaffId(),SecurityFrameworkUtils.getLoginUserId()))throw new AccessDeniedException("会话属于其他接待人员");
        }else if(!Objects.equals(row.getMemberId(),AppMemberAccess.requireMember()))throw new AccessDeniedException("会话不属于当前会员");
        return row;
    }
    private EmployeeDO requireEmployee(Long store) {
        var employee=employees.getEmployeeByUserId(SecurityFrameworkUtils.getLoginUserId());
        if(employee==null)throw new AccessDeniedException("需要本门店在职接待人员");
        Long scoped=storeAccess.scopeStoreId(store==null?employee.getStoreId():store);
        if(!Objects.equals(employee.getUserId(),SecurityFrameworkUtils.getLoginUserId())||!Objects.equals(employee.getStoreId(),scoped)
                ||!Objects.equals(employee.getTenantId(),TenantContextHolder.getTenantId())||!Objects.equals(employee.getStatus(),1))
            throw new AccessDeniedException("需要本门店在职接待人员");return employee;
    }
    private void requireActiveStore(Long id) {
        var store=stores.getStore(id);if(store==null||!Objects.equals(store.getStatus(),1))throw invalidParamException("门店暂不可接待");
    }
    private Conversation summary(ConsultationDO row,boolean staff) {
        Long unread=messages.selectCount(new LambdaQueryWrapperX<ConsultationMessageDO>().eq(ConsultationMessageDO::getConversationId,row.getId())
                .eq(ConsultationMessageDO::getSenderType,staff?"MEMBER":"STAFF").gt(ConsultationMessageDO::getId,staff?row.getStaffReadId():row.getMemberReadId()));
        return new Conversation(row.getId(),row.getStoreId(),row.getKind(),row.getStaffId()!=null,
                staff&&Objects.equals(row.getStaffId(),SecurityFrameworkUtils.getLoginUserId()),unread,row.getUpdateTime());
    }
}
