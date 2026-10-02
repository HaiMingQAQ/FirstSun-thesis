package cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy;
import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("ph_customer_ai_topic")
public class CustomerAiTopicDO extends TenantBaseDO {
    @TableId private Long id;
    private Long memberId;
    private Long storeId;
    private String title;
}
