package cn.iocoder.yudao.module.pharmacy.dal.mysql.member;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberEmailIdentityDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberEmailIdentityMapper extends BaseMapperX<MemberEmailIdentityDO> {
    default MemberEmailIdentityDO selectByEmail(Long tenantId, String email) {
        return selectOne(new LambdaQueryWrapperX<MemberEmailIdentityDO>()
                .eq(MemberEmailIdentityDO::getTenantId, tenantId)
                .eq(MemberEmailIdentityDO::getNormalizedEmail, email));
    }

    default MemberEmailIdentityDO selectByMember(Long tenantId, Long memberId) {
        return selectOne(new LambdaQueryWrapperX<MemberEmailIdentityDO>()
                .eq(MemberEmailIdentityDO::getTenantId, tenantId)
                .eq(MemberEmailIdentityDO::getMemberId, memberId));
    }
}
