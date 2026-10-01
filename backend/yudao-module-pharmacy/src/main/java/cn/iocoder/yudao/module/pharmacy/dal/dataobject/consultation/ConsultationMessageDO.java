package cn.iocoder.yudao.module.pharmacy.dal.dataobject.consultation;
import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
@Data @EqualsAndHashCode(callSuper=true) @TableName("ph_consultation_message")
public class ConsultationMessageDO extends TenantBaseDO {
    @TableId(type=IdType.AUTO) private Long id;
    private Long conversationId;
    private Long senderId;
    private String senderType;
    private String senderName;
    private String clientRequestId;
    private String content;
    private String contentHash;
}
