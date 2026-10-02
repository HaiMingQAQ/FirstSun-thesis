package cn.iocoder.yudao.module.pharmacy.dal.dataobject.prescription;
import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("ph_private_material")
public class PrivateMaterialDO extends TenantBaseDO {
    @TableId(type = IdType.AUTO) private Long id;
    private Long memberId;
    private Long storeId;
    private String sha256;
    private String mimeType;
    private byte[] content;
}
