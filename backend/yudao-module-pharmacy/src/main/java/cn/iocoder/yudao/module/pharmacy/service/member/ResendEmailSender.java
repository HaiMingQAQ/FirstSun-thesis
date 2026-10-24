package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.pharmacy.config.MemberEmailProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.*;

/** 同步发送验证码；不经过会保存正文的邮件日志、MQ 或通用邮件模板。 */
@Service
public class ResendEmailSender {

    private static final URI ENDPOINT = URI.create("https://api.resend.com/emails");
    private final MemberEmailProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public ResendEmailSender(MemberEmailProperties properties) {
        this(properties, createHttpClient());
    }

    ResendEmailSender(MemberEmailProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    static HttpClient createHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void sendCode(String email, String scene, String code) {
        properties.ensureAvailable();
        String recipient = MemberEmailAddress.normalize(email);
        if (properties.getTestRecipient() != null && !properties.getTestRecipient().isBlank()
                && !recipient.equals(MemberEmailAddress.normalize(properties.getTestRecipient()))) {
            throw exception(EMAIL_TEST_RECIPIENT_ONLY);
        }
        String purpose = switch (scene) {
            case MemberEmailCodeService.SCENE_REGISTER -> "注册";
            case MemberEmailCodeService.SCENE_LOGIN -> "登录";
            case MemberEmailCodeService.SCENE_FIRST_PASSWORD -> "首次设置密码";
            case MemberEmailCodeService.SCENE_PASSWORD_RESET -> "重置密码";
            default -> throw exception(EMAIL_SCENE_INVALID);
        };
        try {
            String body = JsonUtils.toJsonString(Map.of("from", MemberEmailAddress.normalize(properties.getFrom()),
                    "to", List.of(recipient), "subject", "FirstSun 邮箱验证码",
                    "text", "您的" + purpose + "验证码为：" + code + "。5分钟内有效，请勿向他人透露。"));
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw exception(EMAIL_SEND_FAILED);
            }
            JsonNode result = JsonUtils.getObjectMapper().readTree(response.body());
            if (result == null || !result.path("id").isTextual() || result.path("id").asText().isBlank()) {
                throw exception(EMAIL_SEND_FAILED);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw exception(EMAIL_SEND_FAILED);
        } catch (Exception ignored) {
            // 不带原始异常 cause：供应商响应或请求中可能含邮箱、验证码或密钥。
            throw exception(EMAIL_SEND_FAILED);
        }
    }
}
