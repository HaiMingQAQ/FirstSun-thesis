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
    @Test void browsingKeepsUnavailableCardsAndSortsAvailableFirstWithoutChangingStock() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "browse-stock-test"), DrugDO.class);
        var drugs=mock(DrugMapper.class); var inventory=mock(AppAvailableInventoryMapper.class);
        var stores=mock(StoreService.class); var categories=mock(CategoryService.class);
        var store=new StoreDO(); store.setStatus(1); when(stores.getStore(1L)).thenReturn(store);
        var missing=new DrugDO(); missing.setId(1L); missing.setGenericName("缺货资料");
        var available=new DrugDO(); available.setId(2L); available.setGenericName("有货资料");
        var zero=new DrugDO(); zero.setId(3L); zero.setGenericName("零库存资料");
        when(drugs.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(missing,available,zero));
        var a=new cn.iocoder.yudao.module.pharmacy.controller.app.pharmacy.vo.AppInventoryAvailableRespVO(); a.setDrugId(2L); a.setQtyAvail(4);
        var z=new cn.iocoder.yudao.module.pharmacy.controller.app.pharmacy.vo.AppInventoryAvailableRespVO(); z.setDrugId(3L); z.setQtyAvail(0);
        when(inventory.selectAvailable(any(),eq(1L),anyList())).thenReturn(List.of(a,z));
        var catalogue=new CustomerAiCatalogueService(drugs,inventory,stores,categories);
        var result=catalogue.browseCandidates(1L,"感冒");
        assertEquals(List.of(2L,1L,3L),result.products().stream().map(p->p.id()).toList());
        assertEquals(List.of(4,0,0),result.products().stream().map(p->p.availableQty()).toList());
        assertTrue(result.products().stream().allMatch(p->p.storeId()==1L)); assertFalse(result.truncated());
        assertEquals(List.of(2L),catalogue.query(1L,"感冒",null).products().stream().map(p->p.id()).toList());
        when(drugs.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(missing));
        assertTrue(catalogue.query(1L,null,1L).products().isEmpty());
    }
    @Test void allUnavailableBrowsingRemainsBoundedAndMarksTruncation() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "browse-limit-test"), DrugDO.class);
        var drugs=mock(DrugMapper.class); var inventory=mock(AppAvailableInventoryMapper.class); var stores=mock(StoreService.class);
        var store=new StoreDO(); store.setStatus(1); when(stores.getStore(1L)).thenReturn(store);
        var found=java.util.stream.LongStream.rangeClosed(1,21).mapToObj(id->{var d=new DrugDO();d.setId(id);d.setGenericName("资料"+id);return d;}).toList();
        when(drugs.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(found);
        when(inventory.selectAvailable(any(),eq(1L),anyList())).thenReturn(List.of());
        var result=new CustomerAiCatalogueService(drugs,inventory,stores,mock(CategoryService.class)).browseCandidates(1L,"感冒");
        assertEquals(20,result.products().size()); assertTrue(result.truncated());
        assertTrue(result.products().stream().allMatch(p->p.availableQty()==0));
    }
}
