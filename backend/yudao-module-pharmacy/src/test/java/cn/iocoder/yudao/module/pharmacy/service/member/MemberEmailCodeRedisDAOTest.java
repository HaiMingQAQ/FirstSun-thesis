package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.module.pharmacy.dal.redis.member.MemberEmailCodeRedisDAO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 离线验证 Redis 调用边界、key 隔离及脚本接线；不连接真实 Redis。 */
@SuppressWarnings({"unchecked", "rawtypes"})
class MemberEmailCodeRedisDAOTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final MemberEmailCodeRedisDAO dao = new MemberEmailCodeRedisDAO(template);

    @Test
    void resendCooldownIsSharedAcrossPurposesButCodesAndTenantsAreIsolated() {
        dao.beginSend(1L, "a@example.com", "register", "192.0.2.1", "v1", "d1");
        dao.beginSend(1L, "a@example.com", "login", "192.0.2.1", "v2", "d2");
        dao.beginSend(2L, "a@example.com", "register", "192.0.2.1", "v3", "d3");
        var keys = ArgumentCaptor.forClass(List.class);
        var scripts = ArgumentCaptor.forClass(RedisScript.class);
        verify(template, times(3)).execute(scripts.capture(), keys.capture(), anyString(), anyString());
        List<String> first = keys.getAllValues().get(0);
        List<String> second = keys.getAllValues().get(1);
        List<String> third = keys.getAllValues().get(2);
        assertNotEquals(first.get(0), second.get(0));
        assertEquals(first.get(1), second.get(1));
        assertNotEquals(first.get(0), third.get(0));
        assertTrue(first.stream().allMatch(k -> k.contains("{1}")));
        assertTrue(third.stream().allMatch(k -> k.contains("{2}")));
        assertTrue(first.stream().noneMatch(k -> k.contains("a@example.com") || k.contains("192.0.2.1")));
        assertTrue(scripts.getValue().getScriptAsString().contains("'pending'"));
    }

    @Test
    void consumptionPassesExpectedVersionAndDigestToOneAtomicScript() {
        when(template.execute(any(RedisScript.class), anyList(), eq("v1"), eq("digest"))).thenReturn(1L);
        assertTrue(dao.consume(7L, "a@example.com", "first-password", "v1", "digest"));
        var script = ArgumentCaptor.forClass(RedisScript.class);
        var keys = ArgumentCaptor.forClass(List.class);
        verify(template).execute(script.capture(), keys.capture(), eq("v1"), eq("digest"));
        assertTrue(((String) keys.getValue().get(0)).contains("{7}:code:first-password:"));
        String source = script.getValue().getScriptAsString();
        assertTrue(source.contains("'version') ~= ARGV[1]"));
        assertTrue(source.contains("'digest') ~= ARGV[2]"));
        assertTrue(source.contains("attempts >= 5"));
        assertTrue(source.contains("redis.call('DEL', KEYS[1])"));
        verifyNoMoreInteractions(template);
    }

    @Test
    void nullRedisScriptResultNeverSignalsSuccess() {
        assertFalse(dao.beginSend(1, "a@example.com", "login", null, "v", "d"));
        assertFalse(dao.activate(1, "a@example.com", "login", "v"));
        assertFalse(dao.consume(1, "a@example.com", "login", "v", "d"));
        assertFalse(dao.allowVerify(1, null));
        assertFalse(dao.allowPasswordLogin(1, "a@example.com", null));
    }

    @Test
    void abortIsBoundToItsVersionAndPasswordRateHasSeparateKeys() {
        dao.abort(1, "a@example.com", "login", "old-version");
        dao.allowPasswordLogin(1, "a@example.com", "192.0.2.1");
        var abortScript = ArgumentCaptor.forClass(RedisScript.class);
        verify(template).execute(abortScript.capture(), anyList(), eq("old-version"));
        assertTrue(abortScript.getValue().getScriptAsString().contains("'version') ~= ARGV[1]"));
        var rateKeys = ArgumentCaptor.forClass(List.class);
        verify(template).execute(any(RedisScript.class), rateKeys.capture());
        assertTrue(((String) rateKeys.getValue().get(0)).contains("password-email:"));
        assertTrue(((String) rateKeys.getValue().get(1)).contains("password-ip:"));
    }
}
