package cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy;
import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
@Data @EqualsAndHashCode(callSuper = true) @TableName("ph_customer_ai_consult")
public class CustomerAiConsultDO extends TenantBaseDO {
    @TableId private Long id;
    private Long memberId;
    private Long storeId;
    private String clientMessageId;
    private String requestHash;
    private String status;
    private String resultJson;
    private Long topicId;
    private String question;
    private LocalDateTime expiresAt;
}
