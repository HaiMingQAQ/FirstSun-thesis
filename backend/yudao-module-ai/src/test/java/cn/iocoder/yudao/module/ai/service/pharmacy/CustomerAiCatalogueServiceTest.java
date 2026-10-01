package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.StoreDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.base.DrugMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.AppAvailableInventoryMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomerAiCatalogueServiceTest {
    @Test void whiteListAllowsOnlyOtcTypesBeforeCandidateLimit() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"catalogue-test"),DrugDO.class);
        var drugs=mock(DrugMapper.class); var inventory=mock(AppAvailableInventoryMapper.class); var stores=mock(StoreService.class);
        var store=new StoreDO(); store.setStatus(1); when(stores.getStore(1L)).thenReturn(store);
        when(drugs.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call->{
            LambdaQueryWrapper<DrugDO> query=call.getArgument(0);
            String sql=query.getSqlSegment();
            var match=Pattern.compile("drug_type IN \\(#\\{ew.paramNameValuePairs.([^}]+)},#\\{ew.paramNameValuePairs.([^}]+)}\\)").matcher(sql);
            assertTrue(match.find(),sql);
            assertEquals(1,query.getParamNameValuePairs().get(match.group(1)));
            assertEquals(2,query.getParamNameValuePairs().get(match.group(2)));
            assertTrue(sql.indexOf("drug_type")<sql.indexOf("LIMIT 21"));
            return List.of();
        });
        assertTrue(new CustomerAiCatalogueService(drugs,inventory,stores).query(1L,"药品",null).products().isEmpty());
        verifyNoInteractions(inventory);
    }
}
