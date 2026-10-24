package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2ClientDO;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2AccessTokenMapper;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2RefreshTokenMapper;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2ClientService;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production token mappers/service, isolated H2 and an in-memory cache stub: no real Redis or HTTP. */
class MemberPasswordTokenRevocationTest {
    JdbcTemplate db;
    OAuth2TokenServiceImpl service;
    Map<String, OAuth2AccessTokenDO> cache;
    final int memberType=UserTypeEnum.MEMBER.getValue();
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:password_tokens_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);
        String common="id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,user_type INT,tenant_id BIGINT,client_id VARCHAR(64),scopes VARCHAR(500),expires_time TIMESTAMP,creator VARCHAR(64),updater VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOLEAN DEFAULT FALSE";
        db.execute("CREATE TABLE system_oauth2_refresh_token ("+common+",refresh_token VARCHAR(64))");
        db.execute("CREATE TABLE system_oauth2_access_token ("+common+",access_token VARCHAR(64),refresh_token VARCHAR(64),user_info VARCHAR(1000))");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OAuth2AccessTokenMapper.class);config.addMapper(OAuth2RefreshTokenMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
        var session=new SqlSessionTemplate(factory.getObject());service=new OAuth2TokenServiceImpl();
        ReflectionTestUtils.setField(service,"oauth2AccessTokenMapper",session.getMapper(OAuth2AccessTokenMapper.class));
        ReflectionTestUtils.setField(service,"oauth2RefreshTokenMapper",session.getMapper(OAuth2RefreshTokenMapper.class));
        cache=new HashMap<>();var redis=mock(OAuth2AccessTokenRedisDAO.class);
        when(redis.get(anyString())).thenAnswer(i->cache.get(i.<String>getArgument(0)));
        doAnswer(i->{var token=i.<OAuth2AccessTokenDO>getArgument(0);cache.put(token.getAccessToken(),token);return null;}).when(redis).set(any());
        doAnswer(i->{cache.remove(i.<String>getArgument(0));return null;}).when(redis).delete(anyString());
        doAnswer(i->{i.<Collection<String>>getArgument(0).forEach(cache::remove);return null;}).when(redis).deleteList(anyCollection());
        ReflectionTestUtils.setField(service,"oauth2AccessTokenRedisDAO",redis);
        var clients=mock(OAuth2ClientService.class);
        when(clients.validOAuthClientFromCache("default")).thenReturn(new OAuth2ClientDO().setClientId("default").setAccessTokenValiditySeconds(60).setRefreshTokenValiditySeconds(600));
        ReflectionTestUtils.setField(service,"oauth2ClientService",clients);TenantContextHolder.setTenantId(7L);
    }
    @AfterEach void clean(){TenantContextHolder.clear();}
    void refresh(String token,long user,int type,long tenant){
        db.update("INSERT INTO system_oauth2_refresh_token(user_id,user_type,tenant_id,client_id,refresh_token,expires_time) VALUES(?,?,?,'default',?,DATEADD('SECOND',600,CURRENT_TIMESTAMP))",user,type,tenant,token);
    }
    void access(String token,String refresh,long user,int type,long tenant){
        db.update("INSERT INTO system_oauth2_access_token(user_id,user_type,tenant_id,client_id,access_token,refresh_token,expires_time) VALUES(?,?,?,'default',?,?,DATEADD('SECOND',60,CURRENT_TIMESTAMP))",user,type,tenant,token,refresh);
    }
    @Test void refreshOnlySessionCannotSurviveRevocationOrMintAnotherAccessToken(){
        refresh("refresh-only",9,memberType,7);service.removeAccessToken(9L,memberType);
        assertAll(()->assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM system_oauth2_refresh_token WHERE deleted = FALSE",Integer.class)),
                ()->assertThrows(ServiceException.class,()->service.checkAccessToken("refresh-only")),
                ()->assertThrows(ServiceException.class,()->service.refreshAccessToken("refresh-only","default")));
    }
    @Test void cachedRefreshFallbackIsRemovedEvenWhenAccessRowIsAlreadyGone(){
        refresh("refresh-cached",9,memberType,7);
        assertNotNull(service.checkAccessToken("refresh-cached"));assertTrue(cache.containsKey("refresh-cached"));
        service.removeAccessToken(9L,memberType);
        assertFalse(cache.containsKey("refresh-cached"));
        assertThrows(ServiceException.class,()->service.checkAccessToken("refresh-cached"));
    }
    @Test void bothOldTokenKindsAreRejectedAfterRevocation(){
        refresh("R",9,memberType,7);access("A","R",9,memberType,7);service.checkAccessToken("A");service.checkAccessToken("R");
        service.removeAccessToken(9L,memberType);
        assertThrows(ServiceException.class,()->service.checkAccessToken("A"));
        assertThrows(ServiceException.class,()->service.checkAccessToken("R"));
        assertThrows(ServiceException.class,()->service.refreshAccessToken("R","default"));
    }
    @Test void otherTenantUserAndUserTypeArePreserved(){
        refresh("own-R",9,memberType,7);access("own-A","own-R",9,memberType,7);
        refresh("other-tenant-R",9,memberType,8);access("other-tenant-A","other-tenant-R",9,memberType,8);
        refresh("other-user-R",10,memberType,7);refresh("staff-R",9,UserTypeEnum.ADMIN.getValue(),7);
        service.removeAccessToken(9L,memberType);
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM system_oauth2_refresh_token WHERE deleted = FALSE",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM system_oauth2_access_token WHERE deleted = FALSE",Integer.class));
        assertTrue(cache.isEmpty());
    }
}
