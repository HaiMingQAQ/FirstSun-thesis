package cn.iocoder.yudao.module.pharmacy.config;

import cn.iocoder.yudao.module.pharmacy.service.member.MemberEmailAddress;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.EMAIL_UNAVAILABLE;

/** 不生成包含 API key 的 toString；秘密只由私有环境变量提供。 */
@Component
@ConfigurationProperties(prefix = "yudao.pharmacy.member-email")
@Getter
@Setter
public class MemberEmailProperties {

    private boolean enabled = false;
    private String from;
    private String apiKey;
    private String testRecipient;

    public void ensureAvailable() {
        if (!enabled || apiKey == null || apiKey.isBlank()
                || apiKey.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw exception(EMAIL_UNAVAILABLE);
        }
        try {
            String sender = MemberEmailAddress.normalize(from);
            if (testRecipient != null && !testRecipient.isBlank()) {
                MemberEmailAddress.normalize(testRecipient);
            } else if (sender.endsWith("@resend.dev")) {
                // 默认测试域名只允许本人邮箱；未配置目标时不得进行真实试发。
                throw exception(EMAIL_UNAVAILABLE);
            }
        } catch (RuntimeException ignored) {
            throw exception(EMAIL_UNAVAILABLE);
        }
    }
}
