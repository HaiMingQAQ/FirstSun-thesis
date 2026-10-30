package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenRespDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberEmailIdentityDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberUserDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.MemberEmailIdentityMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.MemberUserMapper;
import cn.iocoder.yudao.module.system.api.logger.LoginLogApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.UUID;
import java.util.concurrent.*;

import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.EMAIL_REGISTER_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberEmailIdentitySqlTest {
    JdbcTemplate db;
    DriverManagerDataSource source;
    MemberUserMapper members;
    MemberEmailIdentityMapper identities;
    MemberUserServiceImpl users;
    MemberEmailCodeService codes;
    OAuth2TokenCommonApi tokens;
    static final String EMAIL = "member@example.test";

    @BeforeEach void setup() throws Exception {
        source = new DriverManagerDataSource("jdbc:h2:mem:email_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE member_user (id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,mobile VARCHAR(11),password VARCHAR(255),"
                + "email VARCHAR(255),nickname VARCHAR(30),avatar VARCHAR(512),sex INT,status INT,register_ip VARCHAR(32),register_terminal INT,"
                + "login_ip VARCHAR(32),login_date TIMESTAMP,name VARCHAR(30),area_id BIGINT,birthday TIMESTAMP,mark VARCHAR(255),"
                + "point INT,tag_ids VARCHAR(255),level_id BIGINT,experience INT,group_id BIGINT,creator VARCHAR(64),updater VARCHAR(64),"
                + "create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOLEAN DEFAULT FALSE,UNIQUE(tenant_id,mobile))");
        db.execute("CREATE TABLE ph_member_email_identity (id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,"
                + "normalized_email VARCHAR(254) NOT NULL,member_id BIGINT NOT NULL,creator VARCHAR(64),updater VARCHAR(64),"
                + "create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOLEAN DEFAULT FALSE,"
                + "UNIQUE(tenant_id,normalized_email),UNIQUE(tenant_id,member_id))");
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MemberUserMapper.class); configuration.addMapper(MemberEmailIdentityMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        members = session.getMapper(MemberUserMapper.class); identities = session.getMapper(MemberEmailIdentityMapper.class);
        users = new MemberUserServiceImpl(); ReflectionTestUtils.setField(users, "memberUserMapper", members);
        codes = mock(MemberEmailCodeService.class); when(codes.isEnabled()).thenReturn(true);
        tokens = mock(OAuth2TokenCommonApi.class);
        when(tokens.createAccessToken(any())).thenAnswer(i -> {
            var request = i.<cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCreateReqDTO>getArgument(0);
            var token = new OAuth2AccessTokenRespDTO(); token.setUserId(request.getUserId()); token.setAccessToken("unit-only"); return token;
        });
        TenantContextHolder.setTenantId(7L);
    }
    @AfterEach void clean() { TenantContextHolder.clear(); }

    MemberEmailAuthService service(MemberUserService memberService, MemberEmailIdentityMapper identityMapper) {
        var target = new MemberEmailAuthServiceImpl();
        ReflectionTestUtils.setField(target, "memberUserService", memberService);
        ReflectionTestUtils.setField(target, "memberEmailIdentityMapper", identityMapper);
        ReflectionTestUtils.setField(target, "memberEmailCodeService", codes);
        ReflectionTestUtils.setField(target, "oauth2TokenApi", tokens);
        ReflectionTestUtils.setField(target, "loginLogApi", mock(LoginLogApi.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (MemberEmailAuthService) proxy.getProxy();
    }
    MemberEmailIdentityDO identity(Long tenant, Long member, String email) {
        var row = new MemberEmailIdentityDO(); row.setTenantId(tenant); row.setMemberId(member); row.setNormalizedEmail(email);
        identities.insert(row); return row;
    }
    MemberUserDO member() {
        return users.createEmailMember(7L, EMAIL, null, "127.0.0.1");
    }

    @Test void oldUnverifiedContactDoesNotBecomeTheLoginAccount() {
        var legacy = users.registerMember("13900001091", "old12345", EMAIL, null);
        var auth = service(users, identities);
        assertThrows(ServiceException.class, () -> auth.login(EMAIL, "123456", "127.0.0.1"));
        var registration = auth.register(" MEMBER@example.test ", null, "123456", "127.0.0.1");
        assertNotEquals(legacy.getId(), registration.getUserId());
        assertNull(members.selectById(registration.getUserId()).getMobile());
        assertEquals(EMAIL, members.selectById(legacy.getId()).getEmail());
        assertTrue(users.isPasswordMatch("old12345", members.selectById(legacy.getId()).getPassword()));
        assertEquals(registration.getUserId(), identities.selectByEmail(7L, EMAIL).getMemberId());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM member_user", Integer.class));
    }
    @Test void profileContactChangesCannotModifyOrClaimLoginIdentity() {
        var auth = service(users, identities);
        var registered = auth.register(EMAIL, null, "123456", "127.0.0.1");
        users.updateMemberUserProfile(registered.getUserId(), null, null, null, "other@example.test");
        assertEquals(EMAIL, auth.getLoginEmail(registered.getUserId()));
        assertThrows(ServiceException.class, () -> auth.login("other@example.test", "123456", "127.0.0.1"));
        users.updateMemberUserProfile(registered.getUserId(), null, null, null, "");
        assertEquals(EMAIL, auth.getLoginEmail(registered.getUserId()));
    }
    @Test void databaseEnforcesEmailAndMemberUniquenessWithinTenant() {
        var first = member(); var second = member();
        identity(7L, first.getId(), EMAIL);
        assertThrows(DuplicateKeyException.class, () -> identity(7L, second.getId(), EMAIL));
        assertThrows(DuplicateKeyException.class, () -> identity(7L, first.getId(), "other@example.test"));
        assertDoesNotThrow(() -> identity(8L, first.getId(), EMAIL));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM member_user WHERE mobile IS NULL", Integer.class));
        assertNotEquals(identities.selectByEmail(7L, EMAIL).getId(), identities.selectByEmail(8L, EMAIL).getId());
    }
    @Test void wrongTenantCannotReadIdentityOrUseItForPasswordWrite() {
        var member = member(); var binding = identity(7L, member.getId(), EMAIL);
        assertNull(identities.selectByMember(8L, member.getId()));
        assertNull(identities.selectByEmail(8L, EMAIL));
        assertEquals(0, members.updateInitialEmailPassword(member.getId(), 8L, "", "new-hash", binding.getId(), EMAIL));
    }
    @Test void firstPasswordCasRejectsChangedEmailMemberOrDeletedIdentity() {
        var member = member(); var binding = identity(7L, member.getId(), EMAIL);
        db.update("UPDATE ph_member_email_identity SET normalized_email=? WHERE id=?", "changed@example.test", binding.getId());
        assertEquals(0, members.updateInitialEmailPassword(member.getId(), 7L, "", "new-hash", binding.getId(), EMAIL));
        db.update("UPDATE ph_member_email_identity SET normalized_email=?,member_id=? WHERE id=?", EMAIL, member.getId() + 10, binding.getId());
        assertEquals(0, members.updateInitialEmailPassword(member.getId(), 7L, "", "new-hash", binding.getId(), EMAIL));
        db.update("UPDATE ph_member_email_identity SET member_id=?,deleted=TRUE WHERE id=?", member.getId(), binding.getId());
        assertEquals(0, members.updateInitialEmailPassword(member.getId(), 7L, "", "new-hash", binding.getId(), EMAIL));
        assertEquals("", members.selectById(member.getId()).getPassword());
    }
    @Test void firstPasswordCasRejectsDisabledDeletedOrAlreadyChangedMember() {
        var member = member(); var binding = identity(7L, member.getId(), EMAIL);
        db.update("UPDATE member_user SET status=1 WHERE id=?", member.getId());
        assertEquals(0, members.updateInitialEmailPassword(member.getId(), 7L, "", "new-hash", binding.getId(), EMAIL));
        db.update("UPDATE member_user SET status=0,deleted=TRUE WHERE id=?", member.getId());
        assertEquals(0, members.updateInitialEmailPassword(member.getId(), 7L, "", "new-hash", binding.getId(), EMAIL));
        db.update("UPDATE member_user SET deleted=FALSE,password='winner' WHERE id=?", member.getId());
        assertEquals(0, members.updateInitialEmailPassword(member.getId(), 7L, "", "overwrite", binding.getId(), EMAIL));
    }
    @Test void parallelFirstPasswordWritesHaveOneWinner() throws Exception {
        var member = member(); var binding = identity(7L, member.getId(), EMAIL);
        var executor = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            Callable<Integer> task = () -> { gate.await(); return members.updateInitialEmailPassword(member.getId(), 7L, "", "unit-" + Thread.currentThread().getId(), binding.getId(), EMAIL); };
            var first = executor.submit(task); var second = executor.submit(task); gate.countDown();
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    @Test void tokenFailureRollsBackBothNewMemberAndIdentity() {
        doThrow(new IllegalStateException("unit token failure")).when(tokens).createAccessToken(any());
        assertThrows(IllegalStateException.class, () -> service(users, identities).register(EMAIL, null, "123456", "127.0.0.1"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM member_user", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ph_member_email_identity", Integer.class));
    }
    @Test void firstPasswordEnablesEmailPasswordLoginWithoutPhone() {
        var auth = service(users, identities);
        var registered = auth.register(EMAIL, null, "123456", "127.0.0.1");
        assertThrows(ServiceException.class, () -> auth.passwordLogin(EMAIL, "new12345", "127.0.0.1"));
        auth.sendFirstPasswordCode(registered.getUserId(), "127.0.0.1");
        auth.setFirstPassword(registered.getUserId(), "new12345", "123456", "127.0.0.1");
        assertEquals(registered.getUserId(), auth.passwordLogin(EMAIL, "new12345", "127.0.0.1").getUserId());
        assertThrows(ServiceException.class, () -> auth.setFirstPassword(registered.getUserId(), "overwrite12", "123456", "127.0.0.1"));
        assertNull(members.selectById(registered.getUserId()).getMobile());
        verify(codes).sendCode(EMAIL, "first-password", "127.0.0.1");
        verify(codes).verifyAndConsume(EMAIL, "first-password", "123456", "127.0.0.1");
    }
    @Test void uniqueCollisionRollsBackCandidateMemberWithoutClaimingWinner() {
        var winner = member(); var existing = identity(7L, winner.getId(), EMAIL);
        var racing = mock(MemberEmailIdentityMapper.class);
        when(racing.insert(any(MemberEmailIdentityDO.class))).thenAnswer(i -> identities.insert(i.<MemberEmailIdentityDO>getArgument(0)));
        var error = assertThrows(ServiceException.class, () -> service(users, racing).register(EMAIL, null, "123456", "127.0.0.1"));
        assertEquals(EMAIL_REGISTER_EXISTS.getCode(), error.getCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM member_user", Integer.class));
        assertEquals(existing.getId(), identities.selectByEmail(7L, EMAIL).getId());
        verifyNoInteractions(tokens);
    }
}
