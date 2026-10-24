package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.config.MemberEmailProperties;
import cn.iocoder.yudao.module.pharmacy.dal.redis.member.MemberEmailCodeRedisDAO;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.*;
import static cn.iocoder.yudao.module.pharmacy.service.member.MemberEmailCodeService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real digest and scene logic with a stateful challenge-store/sender stub; no live Redis or mail. */
class MemberPasswordResetCodeTest {
    final MemberEmailProperties properties=new MemberEmailProperties();
    final MemberEmailCodeRedisDAO redis=mock(MemberEmailCodeRedisDAO.class);
    final ResendEmailSender sender=mock(ResendEmailSender.class);
    final MemberEmailCodeServiceImpl service=new MemberEmailCodeServiceImpl(properties,redis,sender);
    final Map<String,String[]> records=new HashMap<>();
    final Map<String,String> sent=new HashMap<>();
    static final String EMAIL="member@example.test";
    String key(Object tenant,Object email,Object scene){return tenant+":"+email+":"+scene;}
    @BeforeEach void setup(){
        TenantContextHolder.setTenantId(7L);properties.setEnabled(true);properties.setFrom("sender@example.test");properties.setApiKey("offline-only");
        when(redis.beginSend(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{records.put(key(i.getArgument(0),i.getArgument(1),i.getArgument(2)),new String[]{i.getArgument(4),i.getArgument(5),"pending"});return true;});
        when(redis.activate(anyLong(),anyString(),anyString(),anyString())).thenAnswer(i->{var r=records.get(key(i.getArgument(0),i.getArgument(1),i.getArgument(2)));if(r==null||!r[0].equals(i.getArgument(3)))return false;r[2]="active";return true;});
        doAnswer(i->{records.remove(key(i.getArgument(0),i.getArgument(1),i.getArgument(2)));return null;}).when(redis).abort(anyLong(),anyString(),anyString(),anyString());
        when(redis.allowVerify(anyLong(),anyString())).thenReturn(true);
        when(redis.getActiveVersion(anyLong(),anyString(),anyString())).thenAnswer(i->{var r=records.get(key(i.getArgument(0),i.getArgument(1),i.getArgument(2)));return r!=null&&r[2].equals("active")?r[0]:null;});
        when(redis.consume(anyLong(),anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{String k=key(i.getArgument(0),i.getArgument(1),i.getArgument(2));var r=records.get(k);boolean ok=r!=null&&r[2].equals("active")&&r[0].equals(i.getArgument(3))&&r[1].equals(i.getArgument(4));if(ok)records.remove(k);return ok;});
        doAnswer(i->{sent.put(i.getArgument(1),i.getArgument(2));return null;}).when(sender).sendCode(anyString(),anyString(),anyString());
    }
    @AfterEach void clean(){TenantContextHolder.clear();}
    String send(String binding){service.sendPasswordResetCode(EMAIL,binding,"ip");return sent.get(SCENE_PASSWORD_RESET);}
    @Test void resetCodeIsDigestOnlyAndConsumableExactlyOnce(){
        String code=send("identity-password-snapshot");var r=records.get(key(7,EMAIL,SCENE_PASSWORD_RESET));
        assertNotEquals(code,r[1]);assertEquals(64,r[1].length());
        service.verifyPasswordResetCode(EMAIL,"identity-password-snapshot",code,"ip");
        assertThrows(ServiceException.class,()->service.verifyPasswordResetCode(EMAIL,"identity-password-snapshot",code,"ip"));
    }
    @Test void wrongCodeExpiredCodeAndChangedBindingAreRejected(){
        String code=send("old-snapshot");String wrong=String.format("%06d",(Integer.parseInt(code)+1)%1000000);
        assertThrows(ServiceException.class,()->service.verifyPasswordResetCode(EMAIL,"old-snapshot",wrong,"ip"));
        assertThrows(ServiceException.class,()->service.verifyPasswordResetCode(EMAIL,"new-snapshot",code,"ip"));
        records.clear();assertThrows(ServiceException.class,()->service.verifyPasswordResetCode(EMAIL,"old-snapshot",code,"ip"));
    }
    @Test void registrationLoginAndFirstPasswordChallengesNeverServeAsResetProof(){
        for(String scene:List.of(SCENE_REGISTER,SCENE_LOGIN,SCENE_FIRST_PASSWORD)){
            service.sendCode(EMAIL,scene,"ip");String code=sent.get(scene);
            assertThrows(ServiceException.class,()->service.verifyPasswordResetCode(EMAIL,"identity",code,"ip"));
        }
        assertThrows(ServiceException.class,()->service.sendCode(EMAIL,SCENE_PASSWORD_RESET,"ip"));
        assertThrows(ServiceException.class,()->service.verifyAndConsume(EMAIL,SCENE_PASSWORD_RESET,"123456","ip"));
    }
    @Test void resetCodeIsTenantScopedAndCannotLoginOrFirstSet(){
        String code=send("snapshot");TenantContextHolder.setTenantId(8L);
        assertThrows(ServiceException.class,()->service.verifyPasswordResetCode(EMAIL,"snapshot",code,"ip"));
        TenantContextHolder.setTenantId(7L);
        assertThrows(ServiceException.class,()->service.verifyAndConsume(EMAIL,SCENE_LOGIN,code,"ip"));
        assertThrows(ServiceException.class,()->service.verifyAndConsume(EMAIL,SCENE_FIRST_PASSWORD,code,"ip"));
        service.verifyPasswordResetCode(EMAIL,"snapshot",code,"ip");
    }
    @Test void ineligibleAddressHasRateLimitedSendButNoMailOrActiveCredential(){
        service.sendPasswordResetCode(EMAIL,null,"ip");
        verify(redis).beginSend(eq(7L),eq(EMAIL),eq(SCENE_PASSWORD_RESET),eq("ip"),anyString(),anyString());
        verify(redis).abort(eq(7L),eq(EMAIL),eq(SCENE_PASSWORD_RESET),anyString());
        verifyNoInteractions(sender);assertTrue(records.isEmpty());
        assertThrows(ServiceException.class,()->service.verifyPasswordResetCode(EMAIL,"invented","123456","ip"));
    }
    @Test void ineligibleAndEligibleAddressesShareRateLimits(){
        when(redis.beginSend(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(false);
        for(String binding:Arrays.asList(null,"snapshot"))assertEquals(EMAIL_SEND_TOO_FAST.getCode(),assertThrows(ServiceException.class,()->service.sendPasswordResetCode(EMAIL,binding,"ip")).getCode());
        verifyNoInteractions(sender);
    }
    @Test void sendFailureIsGenericAndLeavesNoUsableResetChallenge(){
        doThrow(new IllegalStateException("offline provider failure")).when(sender).sendCode(anyString(),anyString(),anyString());
        assertDoesNotThrow(()->service.sendPasswordResetCode(EMAIL,"snapshot","ip"));assertTrue(records.isEmpty());
    }
    @Test void disabledConfigurationNeverTouchesChallengeStoreOrSender(){
        properties.setEnabled(false);assertThrows(ServiceException.class,()->service.sendPasswordResetCode(EMAIL,"snapshot","ip"));
        assertThrows(ServiceException.class,()->service.verifyPasswordResetCode(EMAIL,"snapshot","123456","ip"));
        verifyNoInteractions(redis,sender);
    }
}
