package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.StoreDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.base.DrugMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.AppAvailableInventoryMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import cn.iocoder.yudao.module.pharmacy.service.base.CategoryService;
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
    @Test void showsGenericNameAndBrandTogether() {
        var drug=new DrugDO();drug.setGenericName("板蓝根颗粒");drug.setTradeName("青叶");
        assertEquals("板蓝根颗粒（青叶）",CustomerAiCatalogueService.displayName(drug));
        drug.setTradeName(" ");assertEquals("板蓝根颗粒",CustomerAiCatalogueService.displayName(drug));
    }
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
        assertTrue(new CustomerAiCatalogueService(drugs,inventory,stores,mock(CategoryService.class)).query(1L,"药品",null).products().isEmpty());
        verifyNoInteractions(inventory);
    }
    @Test void categoryCandidatesMatchDescriptionOrMedicineNameWithinOtcBoundary() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "category-test"), DrugDO.class);
        var drugs = mock(DrugMapper.class); var inventory = mock(AppAvailableInventoryMapper.class); var stores = mock(StoreService.class);
        var store = new StoreDO(); store.setStatus(1); when(stores.getStore(1L)).thenReturn(store);
        when(drugs.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call -> {
            LambdaQueryWrapper<DrugDO> query = call.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("description LIKE") && sql.contains("OR generic_name LIKE") && sql.contains("OR trade_name LIKE"), sql);
            for (String boundary : List.of("is_rx =", "is_special =", "is_pseudoephedrine =", "approve_status =", "saleable_online ="))
                assertTrue(sql.contains(boundary), sql);
            return List.of();
        });
        var categories = mock(CategoryService.class);
        var catalogue = new CustomerAiCatalogueService(drugs, inventory, stores, categories);
        verifyNoInteractions(categories);
        var category = new cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.CategoryDO();
        category.setId(11L); category.setCatName("感冒用药");
        when(categories.getEnabledCategoryList()).thenReturn(List.of(category));
        assertTrue(catalogue.browseCandidates(1L, "感冒").products().isEmpty());
        verify(drugs).selectList(argThat((com.baomidou.mybatisplus.core.conditions.Wrapper<DrugDO> w) -> w.getSqlSegment().contains("OR category_id IN")));
        verifyNoInteractions(inventory);
    }
    @Test void symptomAliasesMatchIndicationsAndKeepUnavailableReferencesWithoutInventingStock() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "symptom-test"), DrugDO.class);
        var drugs = mock(DrugMapper.class); var inventory = mock(AppAvailableInventoryMapper.class); var stores = mock(StoreService.class);
        var categories = mock(CategoryService.class);
        var store = new StoreDO(); store.setStatus(1); when(stores.getStore(1L)).thenReturn(store);
        var unavailable = new DrugDO(); unavailable.setId(1L); unavailable.setGenericName("测试感冒药");
        unavailable.setDescription("适应症/功能主治：感冒引起的鼻塞、流涕。\n注意事项：仅为单元测试。");
        var available = new DrugDO(); available.setId(2L); available.setGenericName("测试有货药"); available.setDescription("适应症：流涕、喷嚏。");
        var unrelated = new DrugDO(); unrelated.setId(3L); unrelated.setDescription("适应症：关节痛。\n禁忌：鼻塞时禁用。");
        var noEvidence = new DrugDO(); noEvidence.setId(4L); noEvidence.setGenericName("感冒药");
        when(drugs.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call -> {
            LambdaQueryWrapper<DrugDO> query = call.getArgument(0); String sql = query.getSqlSegment();
            assertTrue(sql.contains("is_rx =") && sql.contains("is_special =") && sql.contains("approve_status ="), sql);
            assertTrue(query.getParamNameValuePairs().containsValue("%流涕%"));
            return List.of(unavailable, available, unrelated, noEvidence);
        });
        var stock = new cn.iocoder.yudao.module.pharmacy.controller.app.pharmacy.vo.AppInventoryAvailableRespVO();
        stock.setDrugId(2L); stock.setQtyAvail(7);
        when(inventory.selectAvailable(any(), eq(1L), anyList())).thenReturn(List.of(stock));
        var result = new CustomerAiCatalogueService(drugs, inventory, stores, categories).adviceCandidates(1L, "流鼻涕，打喷嚏，鼻塞");
        assertEquals(List.of(2L, 1L), result.products().stream().map(p -> p.id()).toList());
        assertEquals(List.of(7, 0), result.products().stream().map(p -> p.availableQty()).toList());
        assertFalse(result.truncated()); verifyNoInteractions(categories);
        assertFalse(CustomerAiCatalogueService.symptomTerms("流鼻涕").contains("鼻炎"));
        assertEquals(List.of(), CustomerAiCatalogueService.symptomTerms("肚子疼"));
        assertEquals(List.of("鼻塞"), CustomerAiCatalogueService.symptomTerms("没有流鼻涕，无发烧，鼻塞"));
    }
}
