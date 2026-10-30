package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.config.MemberEmailProperties;
import cn.iocoder.yudao.module.pharmacy.dal.redis.member.MemberEmailCodeRedisDAO;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.*;

@Service
public class MemberEmailCodeServiceImpl implements MemberEmailCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SCENES = Set.of(SCENE_REGISTER, SCENE_LOGIN, SCENE_FIRST_PASSWORD, SCENE_PASSWORD_RESET);
    private final MemberEmailProperties properties;
    private final MemberEmailCodeRedisDAO redis;
    private final ResendEmailSender sender;

    public MemberEmailCodeServiceImpl(MemberEmailProperties properties, MemberEmailCodeRedisDAO redis,
                                     ResendEmailSender sender) {
        this.properties = properties;
        this.redis = redis;
        this.sender = sender;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public void ensureAvailable() {
        properties.ensureAvailable();
    }

    @Override
    public void checkPasswordLoginAttempt(String email, String clientIp) {
        ensureAvailable();
        String normalized = MemberEmailAddress.normalize(email);
        try {
            if (!redis.allowPasswordLogin(tenantId(), normalized, clientIp)) {
                throw exception(EMAIL_SEND_TOO_FAST);
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ignored) {
            throw exception(EMAIL_UNAVAILABLE);
        }
    }

    @Override
    public void sendCode(String email, String scene, String clientIp) {
        if (SCENE_PASSWORD_RESET.equals(scene)) {
            throw exception(EMAIL_SCENE_INVALID);
        }
        sendCode(email, scene, clientIp, null);
    }

    @Override
    public void sendPasswordResetCode(String email, String binding, String clientIp) {
        sendCode(email, SCENE_PASSWORD_RESET, clientIp, binding);
    }

    private void sendCode(String email, String scene, String clientIp, String binding) {
        ensureAvailable();
        String normalized = MemberEmailAddress.normalize(email);
        validateScene(scene);
        if (properties.getTestRecipient() != null && !properties.getTestRecipient().isBlank()
                && !normalized.equals(MemberEmailAddress.normalize(properties.getTestRecipient()))) {
            throw exception(EMAIL_TEST_RECIPIENT_ONLY);
        }
        long tenantId = tenantId();
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String version = HexFormat.of().formatHex(nonce);
        try {
            if (!redis.beginSend(tenantId, normalized, scene, clientIp, version,
                    digest(tenantId, normalized, scene, version, code, binding))) {
                throw exception(EMAIL_SEND_TOO_FAST);
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ignored) {
            throw exception(EMAIL_UNAVAILABLE);
        }
        if (SCENE_PASSWORD_RESET.equals(scene) && binding == null) {
            try {
                redis.abort(tenantId, normalized, scene, version);
            } catch (RuntimeException ignored) {
                // A pending challenge is never consumable and expires on its own.
            }
            return;
        }
        try {
            sender.sendCode(normalized, scene, code);
            if (!redis.activate(tenantId, normalized, scene, version)) {
                throw exception(EMAIL_SEND_FAILED);
            }
        } catch (RuntimeException ignored) {
            try {
                redis.abort(tenantId, normalized, scene, version);
            } catch (RuntimeException unavailable) {
                // pending 不可消费且自动过期；清理异常也不输出底层细节。
            }
            if (!SCENE_PASSWORD_RESET.equals(scene)) {
                throw exception(EMAIL_SEND_FAILED);
            }
            // Reset-send responses must not disclose whether an eligible identity was found.
        }
    }

    @Override
    public void verifyAndConsume(String email, String scene, String code, String clientIp) {
        if (SCENE_PASSWORD_RESET.equals(scene)) {
            throw exception(EMAIL_SCENE_INVALID);
        }
        verifyAndConsume(email, scene, code, clientIp, null);
    }

    @Override
    public void verifyPasswordResetCode(String email, String binding, String code, String clientIp) {
        if (binding == null || binding.isBlank()) {
            throw exception(EMAIL_CODE_INVALID);
        }
        verifyAndConsume(email, SCENE_PASSWORD_RESET, code, clientIp, binding);
    }

    private void verifyAndConsume(String email, String scene, String code, String clientIp, String binding) {
        ensureAvailable();
        String normalized = MemberEmailAddress.normalize(email);
        validateScene(scene);
        long tenantId = tenantId();
        try {
            if (!redis.allowVerify(tenantId, clientIp)) {
                throw exception(EMAIL_SEND_TOO_FAST);
            }
            if (code == null || !code.matches("[0-9]{6}")) {
                throw exception(EMAIL_CODE_INVALID);
            }
            String version = redis.getActiveVersion(tenantId, normalized, scene);
            if (version == null || !redis.consume(tenantId, normalized, scene, version,
                    digest(tenantId, normalized, scene, version, code, binding))) {
                throw exception(EMAIL_CODE_INVALID);
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ignored) {
            throw exception(EMAIL_UNAVAILABLE);
        }
    }

    private static void validateScene(String scene) {
        if (scene == null || !SCENES.contains(scene)) {
            throw exception(EMAIL_SCENE_INVALID);
        }
    }

    private static long tenantId() {
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId < 0) {
            throw exception(EMAIL_UNAVAILABLE);
        }
        return tenantId;
    }

    private static String digest(long tenantId, String email, String scene, String version, String code, String binding) {
        String input = tenantId + "\n" + email + "\n" + scene + "\n" + version + "\n" + code;
        return DigestUtil.sha256Hex(binding == null ? input : input + "\n" + binding);
    }
}
