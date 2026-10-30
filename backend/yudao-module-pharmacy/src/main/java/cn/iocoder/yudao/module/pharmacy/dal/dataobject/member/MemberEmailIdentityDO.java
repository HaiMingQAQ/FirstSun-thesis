package cn.iocoder.yudao.module.pharmacy.dal.dataobject.member;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 已通过邮箱验证码验证的登录身份，与可编辑的会员联系方式分离。 */
@TableName("ph_member_email_identity")
@KeySequence("ph_member_email_identity_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class MemberEmailIdentityDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String normalizedEmail;
    private Long memberId;
}
