package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderLineDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderLineAllocDO;
import cn.iocoder.yudao.module.pay.dal.dataobject.order.PayOrderDO;
import cn.iocoder.yudao.module.pay.dal.dataobject.app.PayAppDO;
import cn.iocoder.yudao.module.pay.service.app.PayAppService;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.sale.*;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.sale.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderMapper;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production completion transaction and SQL mapper, H2 fixture; not HTTP/MySQL evidence. */
class WxOrderSalesPostingTransactionTest {
    JdbcTemplate db;
    WxOrderService service;
    @BeforeEach void setup() throws Exception {
        TenantContextHolder.setTenantId(7L);
        var source=new DriverManagerDataSource("jdbc:h2:mem:wx_post_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);
        table(WxOrderDO.class,"ph_wx_order"); table(PhSaleOrderDO.class,"ph_sale_order");
        table(PhSaleOrderLineDO.class,"ph_sale_order_line");table(PhSalePaymentDO.class,"ph_sale_payment");
        table(WxOrderLineDO.class,"ph_wx_order_line");table(WxOrderLineAllocDO.class,"ph_wx_order_line_alloc");table(PayOrderDO.class,"pay_order");
        db.execute("CREATE TABLE ph_inv_flow(id BIGINT PRIMARY KEY,tenant_id BIGINT,store_id BIGINT,biz_no VARCHAR(32),biz_line_id BIGINT,drug_id BIGINT,batch_id BIGINT,location_id BIGINT,flow_type INT,biz_type INT,deleted BOOLEAN,in_qty INT,out_qty INT,unit_cost DECIMAL(18,4),batch_no VARCHAR(64))");
        db.execute("CREATE TABLE ph_inv_batch(id BIGINT PRIMARY KEY,tenant_id BIGINT,store_id BIGINT,drug_id BIGINT,deleted BOOLEAN,expiry_date DATE,cost_price DECIMAL(18,4))");
        db.execute("CREATE TABLE ph_drug(id BIGINT PRIMARY KEY,tenant_id BIGINT,deleted BOOLEAN,is_rx INT)");
        db.execute("CREATE UNIQUE INDEX uk_sale_wx ON ph_sale_order(wx_order_id)");
        db.execute("CREATE TABLE test_points(order_no VARCHAR(32) PRIMARY KEY,earned INT)");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        for(var mapper:List.of(WxOrderMapper.class,WxOrderPaymentMapper.class,WxOrderLineMapper.class,WxOrderLineAllocMapper.class,WxOrderSaleSourceMapper.class,SaleOrderMapper.class,SaleOrderLineMapper.class,SalePaymentMapper.class))config.addMapper(mapper);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
        var session=new SqlSessionTemplate(factory.getObject());var target=new WxOrderServiceImpl();
        ReflectionTestUtils.setField(target,"wxOrderMapper",session.getMapper(WxOrderMapper.class));
        var points=mock(MemberPointSettlementService.class);
        when(points.earnSalePoints(anyLong(),anyString(),any())).thenAnswer(i->{db.update("INSERT INTO test_points VALUES (?,?)",i.getArgument(1),12);return 12;});
        ReflectionTestUtils.setField(target,"memberPointSettlementService",points);
        var apps=mock(PayAppService.class);var app=new PayAppDO();app.setId(9L);when(apps.validPayApp("firstsun")).thenReturn(app);
        var access=new WxOrderPaymentAccess(session.getMapper(WxOrderPaymentMapper.class),apps);ReflectionTestUtils.setField(access,"appKey","firstsun");
        ReflectionTestUtils.setField(target,"orderPaymentAccess",access);
        var posting=new WxOrderSalePostingService(access,session.getMapper(WxOrderLineMapper.class),session.getMapper(WxOrderLineAllocMapper.class),session.getMapper(WxOrderSaleSourceMapper.class),session.getMapper(SaleOrderMapper.class),session.getMapper(SaleOrderLineMapper.class),session.getMapper(SalePaymentMapper.class));
        ReflectionTestUtils.setField(target,"salePosting",posting);
        var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        service=(WxOrderService)proxy.getProxy();
        db.update("INSERT INTO ph_wx_order(id,order_no,store_id,member_id,order_type,status,pay_status,payable_amount,goods_amount,freight_amount,coupon_amount,discount_amount,point_deduct_amount,point_deduct,point_earned,paid_at,pay_no,pickup_code) VALUES (100,'WX-7-TEST',31,41,0,3,1,12,12,0,0,0,0,0,0,CURRENT_TIMESTAMP,'501','ABC123')");
        db.update("INSERT INTO pay_order(id,app_id,merchant_order_id,user_id,user_type,price,status,refund_price,success_time,channel_code,channel_order_no) VALUES (501,9,'WX-7-TEST',41,?,1200,10,0,CURRENT_TIMESTAMP,'mock','mock-501')",UserTypeEnum.MEMBER.getValue());
        db.update("INSERT INTO ph_wx_order_line(id,wx_order_id,drug_id,qty,price,line_amount,drug_name,specification,unit) VALUES (1001,100,61,1,12,12,'下单名称','原规格','盒')");
        db.update("INSERT INTO ph_wx_order_line_alloc(id,wx_order_id,wx_order_line_id,order_no,out_biz_line_id,drug_id,batch_id,location_id,qty,returned_qty,status) VALUES (3001,100,1001,'WX-7-TEST',1001,61,4001,5001,1,0,1)");
        db.update("INSERT INTO ph_drug VALUES (61,7,FALSE,0)");
        db.update("INSERT INTO ph_inv_batch VALUES (4001,7,31,61,FALSE,'2028-01-01',99)");
        db.update("INSERT INTO ph_inv_flow VALUES (2001,7,31,'WX-7-TEST',1001,61,4001,5001,20,2,FALSE,0,1,2.3456,'ORIGINAL-BATCH')");
    }
    @AfterEach void cleanup(){TenantContextHolder.clear();SecurityContextHolder.clearContext();}
    @Test void paidPickupCompletionMustCreateOneTraceableSale() {
        service.verifyWxOrder(100L,"ABC123",21L);
        assertEquals(4,db.queryForObject("SELECT status FROM ph_wx_order WHERE id=100",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ph_sale_order WHERE wx_order_id=100 AND source=1",Integer.class));
        assertEquals(1,count("ph_sale_order_line"));assertEquals(1,count("ph_sale_payment"));assertEquals(1,count("test_points"));
        assertEquals(501L,db.queryForObject("SELECT pay_order_id FROM ph_sale_payment",Long.class));
        assertEquals(new BigDecimal("2.3456"),db.queryForObject("SELECT cost_price FROM ph_sale_order_line",BigDecimal.class));
        assertEquals("下单名称",db.queryForObject("SELECT drug_name FROM ph_sale_order_line",String.class));
        assertEquals(12,db.queryForObject("SELECT points_earned FROM ph_sale_order",Integer.class));
        assertEquals(1,count("ph_inv_flow"));
    }
    void loginMember(long id){var user=new LoginUser();user.setId(id);user.setTenantId(7L);user.setUserType(UserTypeEnum.MEMBER.getValue());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));}
    int count(String name){return db.queryForObject("SELECT COUNT(*) FROM "+name,Integer.class);}
    @Test void deliveryUsesSamePostingAndRejectsAnotherMember() {
        db.update("UPDATE ph_wx_order SET order_type=1");loginMember(42L);
        assertThrows(RuntimeException.class,()->service.confirmReceiveWxOrderByMember(100L));assertEquals(0,count("ph_sale_order"));
        loginMember(41L);service.confirmReceiveWxOrderByMember(100L);service.confirmReceiveWxOrderByMember(100L);
        assertEquals(1,count("ph_sale_order"));assertEquals(1,count("test_points"));assertEquals(1,count("ph_inv_flow"));
    }
    @Test void paidOrderCanCompleteAfterDrugArchiveIsLogicallyDeleted() {
        db.update("UPDATE ph_drug SET deleted=TRUE");
        completeUsingRetainedArchives();
    }
    @Test void paidOrderCanCompleteAfterBatchArchiveIsLogicallyDeleted() {
        db.update("UPDATE ph_inv_batch SET deleted=TRUE");
        completeUsingRetainedArchives();
    }
    void completeUsingRetainedArchives() {
        service.verifyWxOrder(100L,"ABC123",21L);service.verifyWxOrder(100L,"ABC123",21L);
        assertEquals(4,db.queryForObject("SELECT status FROM ph_wx_order WHERE id=100",Integer.class));
        assertEquals(1,count("ph_sale_order"));assertEquals(1,count("ph_sale_order_line"));
        assertEquals(1,count("ph_sale_payment"));assertEquals(1,count("test_points"));assertEquals(1,count("ph_inv_flow"));
        assertEquals(501L,db.queryForObject("SELECT pay_order_id FROM ph_sale_payment",Long.class));
        assertEquals("下单名称",db.queryForObject("SELECT drug_name FROM ph_sale_order_line",String.class));
        assertEquals(0,db.queryForObject("SELECT is_rx FROM ph_sale_order_line",Integer.class));
        assertEquals("2028-01-01",db.queryForObject("SELECT CAST(expiry_date AS VARCHAR) FROM ph_sale_order_line",String.class));
        assertEquals(new BigDecimal("2.3456"),db.queryForObject("SELECT cost_price FROM ph_sale_order_line",BigDecimal.class));
    }
    @Test void trulyMissingArchivesStillRejectCompletion() {
        db.update("DELETE FROM ph_drug");rejectIncompleteSource();
        db.update("INSERT INTO ph_drug VALUES (61,7,TRUE,0)");
        db.update("DELETE FROM ph_inv_batch");rejectIncompleteSource();
    }
    @Test void retainedArchivesCannotCrossTenantStoreOrDrugScope() {
        db.update("UPDATE ph_drug SET deleted=TRUE");db.update("UPDATE ph_inv_batch SET deleted=TRUE");
        for(String mutation:List.of("UPDATE ph_drug SET tenant_id=8", "UPDATE ph_inv_batch SET tenant_id=8",
                "UPDATE ph_inv_batch SET store_id=32", "UPDATE ph_inv_batch SET drug_id=62")) {
            db.update(mutation);rejectIncompleteSource();
            db.update("UPDATE ph_drug SET tenant_id=7");db.update("UPDATE ph_inv_batch SET tenant_id=7,store_id=31,drug_id=61");
        }
    }
    @Test void missingArchiveClassificationIsNotInvented() {
        db.update("UPDATE ph_drug SET deleted=TRUE,is_rx=NULL");rejectIncompleteSource();
    }
    void rejectIncompleteSource() {
        assertThrows(RuntimeException.class,()->service.verifyWxOrder(100L,"ABC123",21L));
        assertEquals(3,db.queryForObject("SELECT status FROM ph_wx_order WHERE id=100",Integer.class));
        assertEquals(0,count("ph_sale_order"));assertEquals(0,count("test_points"));assertEquals(1,count("ph_inv_flow"));
    }
    @Test void prescriptionSplitsBatchesPreservingMoneyAndCostSnapshots() {
        db.update("UPDATE ph_wx_order SET presc_id=801,goods_amount=36,freight_amount=1.50,discount_amount=0.10,coupon_amount=0.20,point_deduct_amount=0.30,point_deduct=30,payable_amount=36.90");
        db.update("UPDATE pay_order SET price=3690");db.update("UPDATE ph_wx_order_line SET qty=3,line_amount=36");db.update("UPDATE ph_drug SET is_rx=1");
        db.update("INSERT INTO ph_inv_batch VALUES (4002,7,31,61,FALSE,'2029-01-01',88)");
        db.update("INSERT INTO ph_inv_flow VALUES (2002,7,31,'WX-7-TEST',3002,61,4002,5002,82,6,FALSE,0,2,1.1111,'SECOND-BATCH')");
        db.update("INSERT INTO ph_wx_order_line_alloc(id,wx_order_id,wx_order_line_id,order_no,out_biz_line_id,drug_id,batch_id,location_id,qty,returned_qty,status) VALUES (3002,100,1001,'WX-7-TEST',3002,61,4002,5002,2,0,1)");
        service.verifyWxOrder(100L,"ABC123",21L);service.verifyWxOrder(100L,"ABC123",21L);
        assertEquals(2,count("ph_sale_order_line"));assertEquals(3,db.queryForObject("SELECT total_qty FROM ph_sale_order",Integer.class));
        assertEquals(new BigDecimal("36.0000"),db.queryForObject("SELECT SUM(line_amount) FROM ph_sale_order_line",BigDecimal.class));
        assertEquals(new BigDecimal("36.9000"),db.queryForObject("SELECT paid_amount FROM ph_sale_order",BigDecimal.class));
        assertEquals(new BigDecimal("1.5000"),db.queryForObject("SELECT freight_amount FROM ph_sale_order",BigDecimal.class));
        assertEquals(new BigDecimal("4.5700"),db.queryForObject("SELECT cost_amount FROM ph_sale_order",BigDecimal.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ph_sale_order_line WHERE presc_id=801 AND is_rx=1",Integer.class));
        assertEquals(2,count("ph_inv_flow"));assertEquals(1,count("test_points"));
    }
    @Test void concurrentCompletionPostsAndEarnsOnce() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{var tasks=new ArrayList<java.util.concurrent.Future<?>>();for(int n=0;n<2;n++)tasks.add(pool.submit(()->{TenantContextHolder.setTenantId(7L);try{start.await();service.verifyWxOrder(100L,"ABC123",21L);}catch(Exception e){throw new RuntimeException(e);}finally{TenantContextHolder.clear();}}));start.countDown();for(var task:tasks)task.get();}
        finally{pool.shutdownNow();}
        assertEquals(1,count("ph_sale_order"));assertEquals(1,count("ph_sale_payment"));assertEquals(1,count("test_points"));assertEquals(1,count("ph_inv_flow"));
    }
    @Test void concurrentDeliveryConfirmationsSerializeBeforeStateChecks() throws Exception {
        db.update("UPDATE ph_wx_order SET order_type=1");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{var tasks=new ArrayList<java.util.concurrent.Future<?>>();for(int n=0;n<2;n++)tasks.add(pool.submit(()->{TenantContextHolder.setTenantId(7L);loginMember(41L);try{start.await();service.confirmReceiveWxOrderByMember(100L);}catch(Exception e){throw new RuntimeException(e);}finally{TenantContextHolder.clear();SecurityContextHolder.clearContext();}}));start.countDown();for(var task:tasks)task.get();}
        finally{pool.shutdownNow();}
        assertEquals(1,count("ph_sale_order"));assertEquals(1,count("test_points"));assertEquals(1,count("ph_inv_flow"));
    }
    @Test void detailInsertFailureRollsBackCompletionSaleAndPointsThenRetries() {failAndRetry("ph_sale_order_line","qty < 0");}
    @Test void paymentInsertFailureRollsBackCompletionSaleAndPointsThenRetries() {failAndRetry("ph_sale_payment","pay_amount < 0");}
    void failAndRetry(String table,String check){
        db.execute("ALTER TABLE "+table+" ADD CONSTRAINT reject_post CHECK ("+check+")");
        assertThrows(RuntimeException.class,()->service.verifyWxOrder(100L,"ABC123",21L));
        assertEquals(3,db.queryForObject("SELECT status FROM ph_wx_order WHERE id=100",Integer.class));
        assertEquals(0,count("ph_sale_order"));assertEquals(0,count("ph_sale_order_line"));assertEquals(0,count("ph_sale_payment"));assertEquals(0,count("test_points"));
        assertEquals(0,db.queryForObject("SELECT point_earned FROM ph_wx_order WHERE id=100",Integer.class));assertEquals(1,count("ph_inv_flow"));
        db.execute("ALTER TABLE "+table+" DROP CONSTRAINT reject_post");service.verifyWxOrder(100L,"ABC123",21L);
        assertEquals(1,count("ph_sale_order"));assertEquals(1,count("test_points"));assertEquals(1,count("ph_inv_flow"));
    }
    @Test void missingCostOrWrongAllocationFailsClosedWithoutInventedValues() {
        db.update("UPDATE ph_inv_flow SET unit_cost=NULL");assertThrows(RuntimeException.class,()->service.verifyWxOrder(100L,"ABC123",21L));
        assertEquals(0,count("ph_sale_order"));assertEquals(0,count("test_points"));
        db.update("UPDATE ph_inv_flow SET unit_cost=2.3456,out_qty=2");assertThrows(RuntimeException.class,()->service.verifyWxOrder(100L,"ABC123",21L));assertEquals(0,count("ph_sale_order"));
    }
    void table(Class<?> type,String name){
        var columns=new LinkedHashMap<String,String>();
        for(Class<?> c=type;c!=Object.class;c=c.getSuperclass()) for(var f:c.getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers())||f.getAnnotation(TableField.class)!=null&&!f.getAnnotation(TableField.class).exist())continue;
            String col=f.getName().replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase();
            Class<?> t=f.getType();String sql=t==Long.class?"BIGINT":t==Integer.class?"INT":t==Boolean.class?"BOOLEAN":t==BigDecimal.class?"DECIMAL(18,4)":t==java.time.LocalDateTime.class?"TIMESTAMP":t==java.time.LocalDate.class?"DATE":"VARCHAR(2000)";
            columns.put(col,sql+(col.equals("id")?" AUTO_INCREMENT PRIMARY KEY":col.equals("deleted")?" DEFAULT FALSE":""));
        }
        columns.put("tenant_id","BIGINT DEFAULT 7");
        db.execute("CREATE TABLE "+name+" ("+columns.entrySet().stream().map(e->e.getKey()+" "+e.getValue()).collect(java.util.stream.Collectors.joining(","))+")");
    }
}
