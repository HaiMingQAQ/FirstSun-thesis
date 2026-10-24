package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.config.MemberEmailProperties;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.*;
import cn.iocoder.yudao.module.pharmacy.dal.redis.member.MemberEmailCodeRedisDAO;
import cn.iocoder.yudao.module.system.api.logger.LoginLogApi;
import cn.iocoder.yudao.module.system.api.oauth2.OAuth2TokenApiImpl;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.*;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.*;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.service.oauth2.*;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static cn.iocoder.yudao.framework.common.enums.UserTypeEnum.MEMBER;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual password/identity/token SQL + transactions + digest service, with only cache/mail transport stubbed. */
class MemberEmailPasswordSecuritySqlTest {
    DriverManagerDataSource source;JdbcTemplate db;MemberUserMapper members;MemberEmailIdentityMapper identities;
    MemberUserServiceImpl users;MemberEmailAuthService auth;MemberEmailCodeServiceImpl codes;
    OAuth2TokenService tokenService;OAuth2TokenApiImpl tokenApi;OAuth2AccessTokenRedisDAO cacheDao;
    final Map<String,OAuth2AccessTokenDO> cache=new ConcurrentHashMap<>();
    final Map<String,String[]> challenges=new ConcurrentHashMap<>();
    final AtomicReference<String> lastCode=new AtomicReference<>();
    ResendEmailSender sender;MemberUserDO member;MemberEmailIdentityDO identity;
    static final String EMAIL="member@example.test";
    String key(Object t,Object e,Object s){return t+":"+e+":"+s;}
    @BeforeEach void setup() throws Exception{
        source=new DriverManagerDataSource("jdbc:h2:mem:password_security_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        db=new JdbcTemplate(source);
        db.execute("CREATE TABLE member_user (id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,mobile VARCHAR(11),password VARCHAR(255),email VARCHAR(255),nickname VARCHAR(30),avatar VARCHAR(512),sex INT,status INT,register_ip VARCHAR(32),register_terminal INT,login_ip VARCHAR(32),login_date TIMESTAMP,name VARCHAR(30),area_id BIGINT,birthday TIMESTAMP,mark VARCHAR(255),point INT,tag_ids VARCHAR(255),level_id BIGINT,experience INT,group_id BIGINT,creator VARCHAR(64),updater VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOLEAN DEFAULT FALSE)");
        db.execute("CREATE TABLE ph_member_email_identity (id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,member_id BIGINT,normalized_email VARCHAR(254),creator VARCHAR(64),updater VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOLEAN DEFAULT FALSE,UNIQUE(tenant_id,normalized_email),UNIQUE(tenant_id,member_id))");
        String common="id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,user_type INT,tenant_id BIGINT,client_id VARCHAR(64),scopes VARCHAR(500),expires_time TIMESTAMP,creator VARCHAR(64),updater VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOLEAN DEFAULT FALSE";
        db.execute("CREATE TABLE system_oauth2_refresh_token ("+common+",refresh_token VARCHAR(64))");
        db.execute("CREATE TABLE system_oauth2_access_token ("+common+",access_token VARCHAR(64),refresh_token VARCHAR(64),user_info VARCHAR(1000))");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MemberUserMapper.class);config.addMapper(MemberEmailIdentityMapper.class);config.addMapper(OAuth2AccessTokenMapper.class);config.addMapper(OAuth2RefreshTokenMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);var session=new SqlSessionTemplate(factory.getObject());
        members=session.getMapper(MemberUserMapper.class);identities=session.getMapper(MemberEmailIdentityMapper.class);
        users=new MemberUserServiceImpl();ReflectionTestUtils.setField(users,"memberUserMapper",members);
        var tokenTarget=new OAuth2TokenServiceImpl();ReflectionTestUtils.setField(tokenTarget,"oauth2AccessTokenMapper",session.getMapper(OAuth2AccessTokenMapper.class));ReflectionTestUtils.setField(tokenTarget,"oauth2RefreshTokenMapper",session.getMapper(OAuth2RefreshTokenMapper.class));
        cacheDao=mock(OAuth2AccessTokenRedisDAO.class);when(cacheDao.get(anyString())).thenAnswer(i->cache.get(i.<String>getArgument(0)));
        doAnswer(i->{var token=i.<OAuth2AccessTokenDO>getArgument(0);cache.put(token.getAccessToken(),token);return null;}).when(cacheDao).set(any());
        doAnswer(i->{cache.remove(i.<String>getArgument(0));return null;}).when(cacheDao).delete(anyString());
        ReflectionTestUtils.setField(tokenTarget,"oauth2AccessTokenRedisDAO",cacheDao);
        var clients=mock(OAuth2ClientService.class);when(clients.validOAuthClientFromCache("default")).thenReturn(new OAuth2ClientDO().setClientId("default").setAccessTokenValiditySeconds(60).setRefreshTokenValiditySeconds(600));ReflectionTestUtils.setField(tokenTarget,"oauth2ClientService",clients);
        tokenService=transactional(tokenTarget,OAuth2TokenService.class);tokenApi=new OAuth2TokenApiImpl();ReflectionTestUtils.setField(tokenApi,"oauth2TokenService",tokenService);
        var properties=new MemberEmailProperties();properties.setEnabled(true);properties.setApiKey("offline-only");properties.setFrom("sender@example.test");
        var redis=mock(MemberEmailCodeRedisDAO.class);sender=mock(ResendEmailSender.class);
        when(redis.beginSend(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{challenges.put(key(i.getArgument(0),i.getArgument(1),i.getArgument(2)),new String[]{i.getArgument(4),i.getArgument(5),"pending"});return true;});
        when(redis.activate(anyLong(),anyString(),anyString(),anyString())).thenAnswer(i->{var r=challenges.get(key(i.getArgument(0),i.getArgument(1),i.getArgument(2)));r[2]="active";return true;});
        when(redis.allowVerify(anyLong(),anyString())).thenReturn(true);when(redis.allowPasswordLogin(anyLong(),anyString(),anyString())).thenReturn(true);
        when(redis.getActiveVersion(anyLong(),anyString(),anyString())).thenAnswer(i->{var r=challenges.get(key(i.getArgument(0),i.getArgument(1),i.getArgument(2)));return r!=null&&r[2].equals("active")?r[0]:null;});
        when(redis.consume(anyLong(),anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{String k=key(i.getArgument(0),i.getArgument(1),i.getArgument(2));var r=challenges.get(k);boolean ok=r!=null&&r[0].equals(i.getArgument(3))&&r[1].equals(i.getArgument(4));if(ok)challenges.remove(k);return ok;});
        doAnswer(i->{lastCode.set(i.getArgument(2));return null;}).when(sender).sendCode(anyString(),anyString(),anyString());
        codes=new MemberEmailCodeServiceImpl(properties,redis,sender);TenantContextHolder.setTenantId(7L);
        member=users.createEmailMember(7L,EMAIL,"old12345","ip");identity=new MemberEmailIdentityDO();identity.setTenantId(7L);identity.setMemberId(member.getId());identity.setNormalizedEmail(EMAIL);identities.insert(identity);
        auth=auth(users);
    }
    @AfterEach void clean(){TenantContextHolder.clear();}
    <T>T transactional(Object target,Class<T> type){var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));return type.cast(proxy.getProxy());}
    MemberEmailAuthService auth(MemberUserService service){var target=new MemberEmailAuthServiceImpl();ReflectionTestUtils.setField(target,"memberUserService",service);ReflectionTestUtils.setField(target,"memberEmailIdentityMapper",identities);ReflectionTestUtils.setField(target,"memberEmailCodeService",codes);ReflectionTestUtils.setField(target,"oauth2TokenApi",tokenApi);ReflectionTestUtils.setField(target,"loginLogApi",mock(LoginLogApi.class));return transactional(target,MemberEmailAuthService.class);}
    void oldSessions(){
        db.update("INSERT INTO system_oauth2_refresh_token(user_id,user_type,tenant_id,client_id,refresh_token,expires_time) VALUES(?,?,7,'default','R',DATEADD('SECOND',600,CURRENT_TIMESTAMP)),(?,?,7,'default','REFRESH_ONLY',DATEADD('SECOND',600,CURRENT_TIMESTAMP))",member.getId(),MEMBER.getValue(),member.getId(),MEMBER.getValue());
        db.update("INSERT INTO system_oauth2_access_token(user_id,user_type,tenant_id,client_id,access_token,refresh_token,expires_time) VALUES(?,?,7,'default','A','R',DATEADD('SECOND',60,CURRENT_TIMESTAMP))",member.getId(),MEMBER.getValue());
        tokenService.checkAccessToken("A");tokenService.checkAccessToken("R");tokenService.checkAccessToken("REFRESH_ONLY");
    }
    void assertRevoked(){for(String token:List.of("A","R","REFRESH_ONLY"))assertThrows(ServiceException.class,()->tokenService.checkAccessToken(token));for(String token:List.of("R","REFRESH_ONLY"))assertThrows(ServiceException.class,()->tokenService.refreshAccessToken(token,"default"));}
    @Test void changeUpdatesHashRejectsOldPasswordAndRevokesAllOldKinds(){
        oldSessions();auth.changePassword(member.getId(),"old12345","new12345","ip");assertRevoked();
        var saved=members.selectById(member.getId());assertFalse(users.isPasswordMatch("old12345",saved.getPassword()));assertTrue(users.isPasswordMatch("new12345",saved.getPassword()));
        assertThrows(ServiceException.class,()->auth.passwordLogin(EMAIL,"old12345","ip"));assertNotNull(auth.passwordLogin(EMAIL,"new12345","ip").getAccessToken());verifyNoInteractions(sender);
    }
    @Test void resetUsesRealBoundDigestConsumesOnceAndDoesNotClaimContactEmail(){
        oldSessions();var contact=users.registerMember("13900001091","contact12",EMAIL,"ip");
        auth.sendResetCode(EMAIL,"ip");String code=lastCode.get();auth.resetPassword(EMAIL,"new12345",code,"ip");assertRevoked();
        assertTrue(users.isPasswordMatch("new12345",members.selectById(member.getId()).getPassword()));assertTrue(users.isPasswordMatch("contact12",members.selectById(contact.getId()).getPassword()));
        assertThrows(ServiceException.class,()->auth.resetPassword(EMAIL,"another12",code,"ip"));
    }
    @Test void wrongOldPasswordLeavesPasswordAndSessionsIntact(){
        oldSessions();String before=members.selectById(member.getId()).getPassword();
        assertThrows(ServiceException.class,()->auth.changePassword(member.getId(),"wrong","new12345","ip"));
        assertEquals(before,members.selectById(member.getId()).getPassword());assertNotNull(tokenService.checkAccessToken("A"));verifyNoInteractions(sender);
    }
    @Test void legacyShortOldPasswordCanBeChangedWithoutRelaxingNewPasswordPolicy(){
        String shortHash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("short");
        db.update("UPDATE member_user SET password=? WHERE id=?",shortHash,member.getId());
        auth.changePassword(member.getId(),"short","new12345","ip");
        assertTrue(users.isPasswordMatch("new12345",members.selectById(member.getId()).getPassword()));
        assertFalse(users.isPasswordMatch("short",members.selectById(member.getId()).getPassword()));
    }
    @Test void identityChangeBetweenValidationAndWriteLosesCasWithoutRevokingSessions()throws Exception{
        oldSessions();String before=members.selectById(member.getId()).getPassword();var spy=spy(users);
        doAnswer(i->{CompletableFuture.runAsync(()->db.update("UPDATE ph_member_email_identity SET normalized_email=? WHERE id=?","moved@example.test",identity.getId())).get(5,TimeUnit.SECONDS);return i.callRealMethod();})
                .when(spy).replaceEmailPassword(any(),anyString(),anyLong(),anyString());
        assertEquals(EMAIL_PASSWORD_STATE_CHANGED.getCode(),assertThrows(ServiceException.class,()->auth(spy).changePassword(member.getId(),"old12345","new12345","ip")).getCode());
        assertEquals(before,members.selectById(member.getId()).getPassword());assertNotNull(tokenService.checkAccessToken("A"));
        assertEquals("moved@example.test",db.queryForObject("SELECT normalized_email FROM ph_member_email_identity WHERE id=?",String.class,identity.getId()));
    }
    @Test void changedIdentityOrCredentialAfterSendMakesResetProofInvalid(){
        auth.sendResetCode(EMAIL,"ip");String firstCode=lastCode.get();
        db.update("UPDATE ph_member_email_identity SET update_time=DATEADD('SECOND',1,CURRENT_TIMESTAMP) WHERE id=?",identity.getId());
        assertEquals(EMAIL_CODE_INVALID.getCode(),assertThrows(ServiceException.class,()->auth.resetPassword(EMAIL,"new12345",firstCode,"ip")).getCode());
        assertTrue(users.isPasswordMatch("old12345",members.selectById(member.getId()).getPassword()));
        auth.sendResetCode(EMAIL,"ip");final String issued=lastCode.get();
        String changed=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("changed12");db.update("UPDATE member_user SET password=? WHERE id=?",changed,member.getId());
        assertThrows(ServiceException.class,()->auth.resetPassword(EMAIL,"new12345",issued,"ip"));assertEquals(changed,members.selectById(member.getId()).getPassword());
    }
    @Test void tenantAndDisabledStateCannotSelectAnotherResetTarget(){
        oldSessions();auth.sendResetCode(EMAIL,"ip");String code=lastCode.get();TenantContextHolder.setTenantId(8L);
        assertThrows(ServiceException.class,()->auth.resetPassword(EMAIL,"new12345",code,"ip"));assertThrows(ServiceException.class,()->auth.changePassword(member.getId(),"old12345","new12345","ip"));
        TenantContextHolder.setTenantId(7L);db.update("UPDATE member_user SET status=1 WHERE id=?",member.getId());
        assertThrows(ServiceException.class,()->auth.resetPassword(EMAIL,"new12345",code,"ip"));assertNotNull(tokenService.checkAccessToken("A"));
    }
    @Test void cacheRevocationFailureRollsBackPasswordAndSqlTokenDeletes(){
        oldSessions();String before=members.selectById(member.getId()).getPassword();
        doThrow(new IllegalStateException("offline cache failure")).when(cacheDao).delete("A");
        assertThrows(IllegalStateException.class,()->auth.changePassword(member.getId(),"old12345","new12345","ip"));
        assertEquals(before,members.selectById(member.getId()).getPassword());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM system_oauth2_access_token WHERE deleted=FALSE",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM system_oauth2_refresh_token WHERE deleted=FALSE",Integer.class));
    }
    @Test void concurrentChangesHaveOneCasWinnerAndNeverOverwriteIt() throws Exception{
        var both=new CountDownLatch(2);var gate=new CountDownLatch(1);var spy=spy(users);
        doAnswer(i->{var result=i.callRealMethod();both.countDown();assertTrue(gate.await(5,TimeUnit.SECONDS));return result;}).when(spy).getMemberUserByIdAndTenantId(member.getId(),7L);
        var concurrent=auth(spy);var executor=Executors.newFixedThreadPool(2);
        try{
            java.util.function.Function<String,Callable<Boolean>> change=password->()->{TenantContextHolder.setTenantId(7L);try{concurrent.changePassword(member.getId(),"old12345",password,"ip");return true;}catch(ServiceException expected){assertEquals(EMAIL_PASSWORD_STATE_CHANGED.getCode(),expected.getCode());return false;}finally{TenantContextHolder.clear();}};
            var a=executor.submit(change.apply("winner-one"));var b=executor.submit(change.apply("winner-two"));assertTrue(both.await(5,TimeUnit.SECONDS));gate.countDown();
            assertEquals(1,(a.get()?1:0)+(b.get()?1:0));var saved=members.selectById(member.getId()).getPassword();assertTrue(users.isPasswordMatch("winner-one",saved)||users.isPasswordMatch("winner-two",saved));
        }finally{gate.countDown();executor.shutdownNow();}
    }
}
