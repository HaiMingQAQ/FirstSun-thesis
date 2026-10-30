package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberRegisterReqVO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.MemberUserMapper;
import cn.iocoder.yudao.module.system.api.logger.LoginLogApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberAccountSqlTest {
    JdbcTemplate db; MemberUserMapper mapper; MemberUserServiceImpl users; DriverManagerDataSource source;
    @BeforeEach void setup() throws Exception {
        source=new DriverManagerDataSource("jdbc:h2:mem:account_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);
        db.execute("CREATE TABLE member_user (id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,mobile VARCHAR(11),password VARCHAR(255),"
                +"email VARCHAR(255),nickname VARCHAR(30),avatar VARCHAR(512),sex INT,status INT,register_ip VARCHAR(32),register_terminal INT,"
                +"login_ip VARCHAR(32),login_date TIMESTAMP,name VARCHAR(30),area_id BIGINT,birthday TIMESTAMP,mark VARCHAR(255),"
                +"point INT,tag_ids VARCHAR(255),level_id BIGINT,experience INT,group_id BIGINT,creator VARCHAR(64),updater VARCHAR(64),"
                +"create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOLEAN DEFAULT FALSE,UNIQUE(tenant_id,mobile))");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(MemberUserMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(MemberUserMapper.class);
        users=new MemberUserServiceImpl();ReflectionTestUtils.setField(users,"memberUserMapper",mapper);TenantContextHolder.setTenantId(7L);
    }
    @AfterEach void clean(){TenantContextHolder.clear();}
    @Test void emailOmissionKeepsAndExplicitEmptyPersists() {
        var user=users.registerMember("13900001091",null,"keep@example.test",null);
        users.updateMemberUserProfile(user.getId(),"N",null,null);
        assertEquals("keep@example.test",db.queryForObject("SELECT email FROM member_user WHERE id=?",String.class,user.getId()));
        users.updateMemberUserProfile(user.getId(),"N",null,null,"");
        assertEquals("",db.queryForObject("SELECT email FROM member_user WHERE id=?",String.class,user.getId()));
        assertEquals(0,mapper.updateProfileEmail(user.getId(),8L,"other@example.test"));
    }
    @Test void parallelConditionalPasswordWritesHaveOneWinner()throws Exception {
        var user=users.registerMember("13900001091",null,null,null);
        var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            Callable<Integer> task=()->{gate.await();return mapper.updateInitialPassword(user.getId(),7L,user.getPassword(),"unit-"+Thread.currentThread().getId(),user.getMobile());};
            var a=executor.submit(task);var b=executor.submit(task);gate.countDown();
            assertEquals(1,a.get()+b.get());assertEquals(0,mapper.updateInitialPassword(user.getId(),7L,user.getPassword(),"overwrite",user.getMobile()));
            assertEquals(0,mapper.updateInitialPassword(user.getId(),8L,db.queryForObject("SELECT password FROM member_user WHERE id=?",String.class,user.getId()),"other",user.getMobile()));
        }finally{executor.shutdownNow();}
    }
    @Test void phoneChangeAfterCodeTargetReadPreventsFirstPasswordWrite() {
        var user=users.registerMember("13900001091",null,null,null);
        db.update("UPDATE member_user SET mobile=? WHERE id=?","13900001092",user.getId());
        assertEquals(0,mapper.updateInitialPassword(user.getId(),7L,user.getPassword(),"new-hash",user.getMobile()));
        assertEquals(user.getPassword(),db.queryForObject("SELECT password FROM member_user WHERE id=?",String.class,user.getId()));
    }
    @Test void tokenFailureRollsBackNewRegistration() {
        var target=new MemberAuthServiceImpl();var tokens=mock(OAuth2TokenCommonApi.class);
        ReflectionTestUtils.setField(target,"memberUserService",users);ReflectionTestUtils.setField(target,"memberSmsCodeService",mock(MemberSmsCodeService.class));
        ReflectionTestUtils.setField(target,"oauth2TokenApi",tokens);ReflectionTestUtils.setField(target,"loginLogApi",mock(LoginLogApi.class));
        when(tokens.createAccessToken(any())).thenThrow(new IllegalStateException("unit token failure"));
        var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        var service=(MemberAuthService)proxy.getProxy();var request=new AppMemberRegisterReqVO();request.setMobile("13900001091");
        assertThrows(IllegalStateException.class,()->service.register(request,"123456"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM member_user",Integer.class));
    }
}
