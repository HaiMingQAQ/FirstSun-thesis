package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.WxOrderController;
import cn.iocoder.yudao.module.pharmacy.api.payment.PaymentFacade;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.base.EmployeeMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.base.StoreMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.EmployeeServiceImpl;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreServiceImpl;
import cn.iocoder.yudao.module.pharmacy.service.permission.PharmacyStoreDataAccess;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** In-memory SQL and HTTP dispatch with the real production mappers and tenant interceptor. */
class AdminWxOrderSqlHttpTest {

    private JdbcTemplate db;
    private MockMvc mvc;
    private PermissionApi permissions;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wx_order_scope_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(dataSource);
        db.execute("CREATE TABLE ph_store (id BIGINT PRIMARY KEY, store_code VARCHAR(32), store_name VARCHAR(100),"
                + " address VARCHAR(255), phone VARCHAR(32), manage_scope VARCHAR(255), license_no VARCHAR(64),"
                + " license_expire DATE, is_medical INT, business_hours VARCHAR(100), status INT, dept_id BIGINT,"
                + " creator VARCHAR(64), create_time TIMESTAMP, updater VARCHAR(64), update_time TIMESTAMP,"
                + " deleted BOOLEAN, tenant_id BIGINT)");
        db.execute("CREATE TABLE ph_employee (id BIGINT PRIMARY KEY, emp_no VARCHAR(32), emp_name VARCHAR(100),"
                + " phone VARCHAR(255), store_id BIGINT, position INT, pharmacist_no VARCHAR(64),"
                + " license_expire DATE, health_cert_expire DATE, hire_date DATE, status INT, user_id BIGINT,"
                + " creator VARCHAR(64), create_time TIMESTAMP, updater VARCHAR(64), update_time TIMESTAMP,"
                + " deleted BOOLEAN, tenant_id BIGINT)");
        db.execute("CREATE TABLE ph_wx_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32), member_id BIGINT,"
                + " store_id BIGINT, order_type INT, goods_amount DECIMAL(18,2), coupon_amount DECIMAL(18,2),"
                + " freight_amount DECIMAL(18,2), discount_amount DECIMAL(18,2), point_deduct INT,"
                + " point_deduct_amount DECIMAL(18,2), point_earned INT, payable_amount DECIMAL(18,2),"
                + " pay_no VARCHAR(32), pay_status INT, paid_at TIMESTAMP, presc_id BIGINT, status INT,"
                + " cancel_reason VARCHAR(200), address_snapshot VARCHAR(300), remark VARCHAR(500),"
                + " finish_at TIMESTAMP, pay_order_id BIGINT, expire_at TIMESTAMP, pickup_code VARCHAR(32),"
                + " verify_by BIGINT, verify_at TIMESTAMP, creator VARCHAR(64), create_time TIMESTAMP,"
                + " updater VARCHAR(64), update_time TIMESTAMP, deleted BOOLEAN, tenant_id BIGINT,"
                + " CONSTRAINT uk_pickup_code UNIQUE (pickup_code))");
        db.update("INSERT INTO ph_store (id, store_code, store_name, status, deleted, tenant_id) VALUES"
                + " (11, 'A', 'A', 1, false, 100), (12, 'B', 'B', 1, false, 100),"
                + " (21, 'C', 'C', 1, false, 200)");
        db.update("INSERT INTO ph_employee (id, emp_no, emp_name, store_id, status, user_id, deleted, tenant_id) VALUES"
                + " (101, 'EA', 'A staff', 11, 1, 1001, false, 100),"
                + " (102, 'EB', 'B staff', 12, 1, 1002, false, 100),"
                + " (201, 'EC', 'C staff', 21, 1, 2001, false, 200)");
        db.update("INSERT INTO ph_wx_order (id, order_no, member_id, store_id, order_type,"
                + " pay_status, status, remark, deleted, tenant_id) VALUES"
                + " (10001, 'WX-A', 1, 11, 0, 0, 0, 'A', false, 100),"
                + " (10002, 'WX-B', 1, 12, 0, 0, 0, 'B', false, 100),"
                + " (20001, 'WX-C', 2, 21, 0, 0, 0, 'C', false, 200)");

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(StoreMapper.class);
        configuration.addMapper(EmployeeMapper.class);
        configuration.addMapper(WxOrderMapper.class);
        var plugin = new MybatisPlusInterceptor();
        plugin.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantDatabaseInterceptor(new TenantProperties())));
        plugin.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(plugin);
        var session = new SqlSessionTemplate(factory.getObject());

        var stores = new StoreServiceImpl();
        ReflectionTestUtils.setField(stores, "storeMapper", session.getMapper(StoreMapper.class));
        var employees = new EmployeeServiceImpl();
        ReflectionTestUtils.setField(employees, "employeeMapper", session.getMapper(EmployeeMapper.class));
        permissions = mock(PermissionApi.class);
        var access = new PharmacyStoreDataAccess(permissions, employees, stores);
        var orderService = new WxOrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "wxOrderMapper", session.getMapper(WxOrderMapper.class));
        PaymentFacade paymentFacade = mock(PaymentFacade.class);
        when(paymentFacade.createPayOrder(any())).thenReturn("500");
        ReflectionTestUtils.setField(orderService, "paymentFacade", paymentFacade);
        ReflectionTestUtils.setField(orderService, "memberPointSettlementService",
                mock(MemberPointSettlementService.class));
        var adminService = new AdminWxOrderService(access, orderService, mock(WxOrderLineService.class),
                session.getMapper(WxOrderMapper.class), employees);
        var controller = new WxOrderController();
        ReflectionTestUtils.setField(controller, "adminWxOrderService", adminService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        login(1001, 100);
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        db.execute("SHUTDOWN");
    }

    @Test
    void staffSqlListAndHttpDetailStayInOwnStoreAndTenant() throws Exception {
        mvc.perform(get("/pharmacy/member/order/page"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(10001));
        mvc.perform(get("/pharmacy/member/order/get").param("id", "10001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeId").value(11));
        assertDenied(() -> mvc.perform(get("/pharmacy/member/order/get").param("id", "10002")));
        assertDenied(() -> mvc.perform(get("/pharmacy/member/order/page").param("storeId", "12")));
        mvc.perform(get("/pharmacy/member/order/get").param("id", "20001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM ph_wx_order", Integer.class));
    }

    @Test
    void tenantAdminCanUseBothStoresButNotAnotherTenant() throws Exception {
        login(1003, 100);
        when(permissions.hasAnyRoles(1003L, "tenant_admin")).thenReturn(true);
        mvc.perform(get("/pharmacy/member/order/page"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/pharmacy/member/order/get").param("id", "10002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeId").value(12));
        mvc.perform(get("/pharmacy/member/order/get").param("id", "20001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        assertDenied(() -> mvc.perform(get("/pharmacy/member/order/page").param("storeId", "21")));

        TenantContextHolder.setTenantId(200L);
        assertDenied(() -> mvc.perform(get("/pharmacy/member/order/page")));
        login(2001, 200);
        mvc.perform(get("/pharmacy/member/order/get").param("id", "20001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeId").value(21));
    }

    @Test
    void unboundUserCannotReadStoreData() {
        login(1004, 100);
        assertDenied(() -> mvc.perform(get("/pharmacy/member/order/page")));
        assertDenied(() -> mvc.perform(get("/pharmacy/member/order/get").param("id", "10001")));
    }

    @Test
    void httpUpdateAllowsRemarkButCannotOverwriteManagedFieldsOrOtherStore() throws Exception {
        mvc.perform(put("/pharmacy/member/order/update").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":10001,\"remark\":\"legitimate\",\"status\":4,"
                                + "\"payStatus\":1,\"payOrderId\":909,\"verifyBy\":102}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        var own = db.queryForMap("SELECT remark,status,pay_status,pay_order_id,verify_by FROM ph_wx_order WHERE id=10001");
        assertEquals("legitimate", own.get("REMARK"));
        assertEquals(0, own.get("STATUS"));
        assertEquals(0, own.get("PAY_STATUS"));
        assertNull(own.get("PAY_ORDER_ID"));
        assertNull(own.get("VERIFY_BY"));

        assertDenied(() -> mvc.perform(put("/pharmacy/member/order/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":10002,\"remark\":\"tampered\"}")));
        assertEquals("B", db.queryForObject("SELECT remark FROM ph_wx_order WHERE id=10002", String.class));
    }

    @Test
    void httpVerificationUsesCurrentEmployeesSqlIdentity() throws Exception {
        db.update("UPDATE ph_wx_order SET status=3, pay_status=1, pickup_code='ABC', payable_amount=12 WHERE id=10001");
        db.update("UPDATE ph_wx_order SET status=3, pay_status=1, pickup_code='DEF', payable_amount=12 WHERE id=10002");
        db.update("UPDATE ph_wx_order SET status=3, pay_status=1, pickup_code='GHI', payable_amount=12 WHERE id=20001");
        assertDenied(() -> mvc.perform(put("/pharmacy/member/order/verify")
                .param("id", "10002").param("pickupCode", "DEF").param("verifyBy", "101")));
        assertEquals(3, db.queryForObject("SELECT status FROM ph_wx_order WHERE id=10002", Integer.class));

        Exception crossTenant = assertThrows(Exception.class, () -> mvc.perform(put("/pharmacy/member/order/verify")
                .param("id", "20001").param("pickupCode", "GHI")));
        while (crossTenant.getCause() instanceof Exception cause) crossTenant = cause;
        assertInstanceOf(ServiceException.class, crossTenant);
        assertEquals(3, db.queryForObject("SELECT status FROM ph_wx_order WHERE id=20001", Integer.class));
        TenantContextHolder.setTenantId(200L);
        assertDenied(() -> mvc.perform(put("/pharmacy/member/order/verify")
                .param("id", "20001").param("pickupCode", "GHI")));
        TenantContextHolder.setTenantId(100L);

        login(2001, 100); // This user has only a tenant-200 employee row.
        assertDenied(() -> mvc.perform(put("/pharmacy/member/order/verify")
                .param("id", "10001").param("pickupCode", "ABC").param("verifyBy", "201")));

        login(1001, 100);
        mvc.perform(put("/pharmacy/member/order/verify")
                        .param("id", "10001").param("pickupCode", "ABC").param("verifyBy", "102"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        var verified = db.queryForMap("SELECT status,verify_by FROM ph_wx_order WHERE id=10001");
        assertEquals(4, verified.get("STATUS"));
        assertEquals(101L, verified.get("VERIFY_BY"));
    }

    @Test
    void databaseRejectsDuplicatePickupCodeAcrossStoresAndTenants() {
        db.update("UPDATE ph_wx_order SET pickup_code='SAME01' WHERE id=10001");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> db.update("UPDATE ph_wx_order SET pickup_code='SAME01' WHERE id=10002"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> db.update("UPDATE ph_wx_order SET pickup_code='SAME01' WHERE id=20001"));
        assertNull(db.queryForObject("SELECT pickup_code FROM ph_wx_order WHERE id=10002", String.class));
        assertNull(db.queryForObject("SELECT pickup_code FROM ph_wx_order WHERE id=20001", String.class));
    }

    @Test
    void httpCreatePickupIgnoresPresetCodeAndAllowsMissingCode() throws Exception {
        mvc.perform(post("/pharmacy/member/order/create").contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderJson("WX-NEW-1", ",\"pickupCode\":\"KNOWN1\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isNumber());
        mvc.perform(post("/pharmacy/member/order/create").contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderJson("WX-NEW-2", "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isNumber());

        var first = db.queryForMap("SELECT pay_status,status,pickup_code FROM ph_wx_order WHERE order_no='WX-NEW-1'");
        var second = db.queryForMap("SELECT pay_status,status,pickup_code FROM ph_wx_order WHERE order_no='WX-NEW-2'");
        assertEquals(0, first.get("PAY_STATUS"));
        assertEquals(0, first.get("STATUS"));
        assertNotEquals("KNOWN1", first.get("PICKUP_CODE"));
        assertTrue(((String) first.get("PICKUP_CODE")).matches("[A-HJ-NP-Z2-9]{6}"));
        assertTrue(((String) second.get("PICKUP_CODE")).matches("[A-HJ-NP-Z2-9]{6}"));
        assertNotEquals(first.get("PICKUP_CODE"), second.get("PICKUP_CODE"));
    }

    private String createOrderJson(String orderNo, String extra) {
        return "{\"orderNo\":\"" + orderNo + "\",\"memberId\":1,\"storeId\":11,\"orderType\":0,"
                + "\"goodsAmount\":12,\"payableAmount\":12,\"expireAt\":\"2026-09-29T00:00:00\""
                + extra + "}";
    }

    private void login(long userId, long tenantId) {
        TenantContextHolder.setTenantId(tenantId);
        var user = new LoginUser();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private void assertDenied(ThrowingAction action) {
        Exception error = assertThrows(Exception.class, action::run);
        while (error.getCause() != null) error = (Exception) error.getCause();
        assertInstanceOf(AccessDeniedException.class, error);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
