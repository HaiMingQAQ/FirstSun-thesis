package cn.iocoder.yudao.module.pharmacy.dal.redis.member;

import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 仅服务会员邮箱验证码，Redis 只保存摘要、版本及有限时的计数。 */
@Repository
public class MemberEmailCodeRedisDAO {

    private static final DefaultRedisScript<Long> BEGIN = script("begin", Long.class);
    private static final DefaultRedisScript<Long> ACTIVATE = script("activate", Long.class);
    private static final DefaultRedisScript<Long> ABORT = script("abort", Long.class);
    private static final DefaultRedisScript<String> VERSION = script("version", String.class);
    private static final DefaultRedisScript<Long> CONSUME = script("consume", Long.class);
    private static final DefaultRedisScript<Long> VERIFY_RATE = script("verify-rate", Long.class);
    private static final DefaultRedisScript<Long> PASSWORD_RATE = script("password-rate", Long.class);

    private final StringRedisTemplate redisTemplate;

    public MemberEmailCodeRedisDAO(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean beginSend(long tenantId, String email, String scene, String clientIp, String version, String digest) {
        String prefix = prefix(tenantId);
        return one(redisTemplate.execute(BEGIN, List.of(codeKey(tenantId, email, scene),
                prefix + "send-email:" + hash(email), prefix + "send-ip:" + hashIp(clientIp)), version, digest));
    }

    public boolean activate(long tenantId, String email, String scene, String version) {
        return one(redisTemplate.execute(ACTIVATE, List.of(codeKey(tenantId, email, scene)), version));
    }

    public void abort(long tenantId, String email, String scene, String version) {
        redisTemplate.execute(ABORT, List.of(codeKey(tenantId, email, scene)), version);
    }

    public boolean allowVerify(long tenantId, String clientIp) {
        return one(redisTemplate.execute(VERIFY_RATE, List.of(prefix(tenantId) + "verify-ip:" + hashIp(clientIp))));
    }

    public boolean allowPasswordLogin(long tenantId, String email, String clientIp) {
        String prefix = prefix(tenantId);
        return one(redisTemplate.execute(PASSWORD_RATE, List.of(prefix + "password-email:" + hash(email),
                prefix + "password-ip:" + hashIp(clientIp))));
    }

    public String getActiveVersion(long tenantId, String email, String scene) {
        return redisTemplate.execute(VERSION, List.of(codeKey(tenantId, email, scene)));
    }

    public boolean consume(long tenantId, String email, String scene, String version, String digest) {
        return one(redisTemplate.execute(CONSUME, List.of(codeKey(tenantId, email, scene)), version, digest));
    }

    private static String prefix(long tenantId) {
        return "pharmacy:email:{" + tenantId + "}:";
    }

    private static String codeKey(long tenantId, String email, String scene) {
        return prefix(tenantId) + "code:" + scene + ":" + hash(email);
    }

    private static String hashIp(String clientIp) {
        return hash(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim());
    }

    private static String hash(String value) {
        return DigestUtil.sha256Hex(value);
    }

    private static boolean one(Long result) {
        return Long.valueOf(1L).equals(result);
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/member-email-" + name + ".lua"));
        script.setResultType(resultType);
        return script;
    }
}
