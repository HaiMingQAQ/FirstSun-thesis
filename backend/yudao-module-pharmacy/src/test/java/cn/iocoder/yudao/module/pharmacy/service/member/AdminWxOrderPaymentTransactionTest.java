package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.module.pay.api.order.PayOrderApiImpl;
import cn.iocoder.yudao.module.pay.dal.dataobject.app.PayAppDO;
import cn.iocoder.yudao.module.pay.dal.mysql.order.PayOrderMapper;
import cn.iocoder.yudao.module.pay.service.app.PayAppService;
import cn.iocoder.yudao.module.pay.service.order.PayOrderServiceImpl;
import cn.iocoder.yudao.module.pharmacy.api.payment.PaymentFacadeAdapter;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderSaveReqVO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Same-datasource H2 transaction checks with the production order and payment mappers. */
class AdminWxOrderPaymentTransactionTest {

    private JdbcTemplate db;
    private WxOrderService service;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:admin_wx_pay_"
                + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE ph_wx_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32),"
                + " member_id BIGINT, store_id BIGINT, order_type INT, goods_amount DECIMAL(18,2),"
                + " coupon_amount DECIMAL(18,2), freight_amount DECIMAL(18,2), discount_amount DECIMAL(18,2),"
                + " point_deduct INT, point_deduct_amount DECIMAL(18,2), point_earned INT,"
                + " payable_amount DECIMAL(18,2), pay_no VARCHAR(32), pay_status INT, paid_at TIMESTAMP,"
                + " presc_id BIGINT, status INT, cancel_reason VARCHAR(200), address_snapshot VARCHAR(300),"
                + " remark VARCHAR(500), finish_at TIMESTAMP, pay_order_id BIGINT, expire_at TIMESTAMP,"
                + " pickup_code VARCHAR(32), verify_by BIGINT, verify_at TIMESTAMP, creator VARCHAR(64),"
                + " create_time TIMESTAMP, updater VARCHAR(64), update_time TIMESTAMP, deleted BOOLEAN DEFAULT FALSE,"
                + " tenant_id BIGINT, CONSTRAINT uk_pickup_code UNIQUE (pickup_code))");
        db.execute("CREATE TABLE pay_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, app_id BIGINT,"
                + " channel_id BIGINT, channel_code VARCHAR(32), merchant_order_id VARCHAR(64),"
                + " subject VARCHAR(32), body VARCHAR(128), notify_url VARCHAR(1024), price INT,"
                + " channel_fee_rate DOUBLE, channel_fee_price INT, status INT, user_ip VARCHAR(50),"
                + " expire_time TIMESTAMP, success_time TIMESTAMP, extension_id BIGINT, no VARCHAR(64),"
                + " refund_price INT, channel_user_id VARCHAR(255), channel_order_no VARCHAR(64),"
                + " creator VARCHAR(64), create_time TIMESTAMP, updater VARCHAR(64), update_time TIMESTAMP,"
                + " deleted BOOLEAN DEFAULT FALSE, tenant_id BIGINT, user_id BIGINT, user_type INT,"
                + " CONSTRAINT uk_pay_merchant UNIQUE (app_id, merchant_order_id))");

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(WxOrderMapper.class);
        configuration.addMapper(PayOrderMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());

        var appService = mock(PayAppService.class);
        var app = new PayAppDO();
        app.setId(9L);
        app.setAppKey("firstsun");
        app.setOrderNotifyUrl("http://localhost/pay-notify");
        when(appService.getAppList()).thenReturn(List.of(app));
        when(appService.validPayApp("firstsun")).thenReturn(app);
        var payService = new PayOrderServiceImpl();
        ReflectionTestUtils.setField(payService, "orderMapper", session.getMapper(PayOrderMapper.class));
        ReflectionTestUtils.setField(payService, "appService", appService);
        var payApi = new PayOrderApiImpl();
        ReflectionTestUtils.setField(payApi, "payOrderService", payService);
        var facade = new PaymentFacadeAdapter();
        ReflectionTestUtils.setField(facade, "payOrderApi", payApi);
        ReflectionTestUtils.setField(facade, "payAppService", appService);

        var target = new WxOrderServiceImpl();
        ReflectionTestUtils.setField(target, "wxOrderMapper", session.getMapper(WxOrderMapper.class));
        ReflectionTestUtils.setField(target, "paymentFacade", facade);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (WxOrderService) proxy.getProxy();
    }

    @Test
    void invalidAmountIsRejectedBeforeEitherTableIsWritten() {
        for (String amount : List.of("0", "-1", "21474836.48")) {
            assertThrows(RuntimeException.class, () -> service.createWxOrder(order(amount)));
            assertTablesEmpty();
        }
    }

    @Test
    void paymentInsertFailureRollsBackCreatedOrder() {
        db.execute("ALTER TABLE pay_order ADD CONSTRAINT ck_reject_payment CHECK (price < 1)");

        assertThrows(RuntimeException.class, () -> service.createWxOrder(order("12.34")));
        assertTablesEmpty();
    }

    @Test
    void associationWriteFailureRollsBackBothOrders() {
        db.execute("ALTER TABLE ph_wx_order ADD CONSTRAINT ck_reject_link"
                + " CHECK (pay_no IS NULL AND pay_order_id IS NULL)");

        assertThrows(RuntimeException.class, () -> service.createWxOrder(order("12.34")));
        assertTablesEmpty();
    }

    @Test
    void successfulCreateStoresPaymentAssociation() {
        Long id = service.createWxOrder(order("12.34"));

        assertEquals(1, count("ph_wx_order"));
        assertEquals(1, count("pay_order"));
        assertEquals(1234, db.queryForObject("SELECT price FROM pay_order", Integer.class));
        Long paymentId = db.queryForObject("SELECT id FROM pay_order", Long.class);
        var row = db.queryForMap("SELECT id, pay_no, pay_order_id, pay_status, status FROM ph_wx_order");
        assertEquals(id, ((Number) row.get("ID")).longValue());
        assertEquals(paymentId.toString(), row.get("PAY_NO"));
        assertEquals(0, ((Number) row.get("PAY_STATUS")).intValue());
        assertEquals(0, ((Number) row.get("STATUS")).intValue());
    }

    private WxOrderSaveReqVO order(String amount) {
        var order = new WxOrderSaveReqVO();
        order.setOrderNo("WX-TX-" + UUID.randomUUID().toString().substring(0, 8));
        order.setMemberId(1L);
        order.setStoreId(11L);
        order.setOrderType(0);
        order.setGoodsAmount(new BigDecimal("12.34"));
        order.setPayableAmount(new BigDecimal(amount));
        order.setExpireAt(LocalDateTime.now().plusMinutes(30));
        return order;
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertTablesEmpty() {
        assertEquals(0, count("ph_wx_order"));
        assertEquals(0, count("pay_order"));
    }
}
