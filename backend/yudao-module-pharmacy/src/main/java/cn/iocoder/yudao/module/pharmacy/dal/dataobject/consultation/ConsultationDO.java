package cn.iocoder.yudao.module.pharmacy.dal.dataobject.consultation;
import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
@Data @EqualsAndHashCode(callSuper=true) @TableName("ph_consultation")
public class ConsultationDO extends TenantBaseDO {
    @TableId(type=IdType.AUTO) private Long id;
    private Long memberId;
    private Long storeId;
    private String kind;
    private Long staffId;
    private Long lastMessageId;
    private Long memberReadId;
    private Long staffReadId;
}
