package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO;
import cn.iocoder.yudao.module.ai.enums.model.AiPlatformEnum;
import cn.iocoder.yudao.module.ai.service.model.AiApiKeyService;
import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import cn.iocoder.yudao.module.ai.util.AiUtils;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;

/** 管理端独立调用边界，避免 SDK 与 HTTP 客户端的重试叠加。 */
@Service
@RequiredArgsConstructor
public class PharmacyAiModelClient {
    private final AiModelService models;
    private final AiApiKeyService apiKeys;

    public record Session(ChatModel client, ChatOptions options, boolean allowRouteRepair) { }

    public Session forModel(AiModelDO model) {
        var platform = AiPlatformEnum.validatePlatform(model.getPlatform());
        int maxTokens = Math.min(model.getMaxTokens() == null ? 800 : model.getMaxTokens(), 1200);
        if (platform != AiPlatformEnum.OPENAI && platform != AiPlatformEnum.DEEP_SEEK) {
            // 未控制其他 SDK 的内部重试时，不再增加第二次模型调用。
            return new Session(models.getChatModel(model.getId()),
                    AiUtils.buildChatOptions(platform, model.getModel(), 0.1, maxTokens), false);
        }
        var validated = models.validateModel(model.getId());
        var key = apiKeys.validateApiKey(validated.getKeyId());
        if (!model.getPlatform().equals(validated.getPlatform())
                || !model.getPlatform().equals(key.getPlatform())) {
            throw new IllegalStateException("AI 模型与服务商配置不一致");
        }
        String apiKey = AiUtils.resolveSpringPlaceholders(key.getApiKey());
        AiUtils.validateApiKey(apiKey);
        String url = AiUtils.resolveSpringPlaceholders(key.getUrl());
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(10000);
        requests.setReadTimeout(20000);
        var streaming = HttpClient.create().disableRetry(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000).responseTimeout(Duration.ofSeconds(45));
        var api = OpenAiApi.builder().apiKey(apiKey)
                .baseUrl(StringUtils.hasText(url) ? url.trim()
                        : platform == AiPlatformEnum.OPENAI ? "https://api.openai.com" : "https://api.deepseek.com")
                .completionsPath(platform == AiPlatformEnum.OPENAI ? "/v1/chat/completions" : "/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(requests))
                .webClientBuilder(WebClient.builder().clientConnector(new ReactorClientHttpConnector(streaming)))
                .build();
        var optionsBuilder = OpenAiChatOptions.builder().model(validated.getModel()).temperature(0.1)
                .maxTokens(maxTokens).internalToolExecutionEnabled(false);
        // 与顾客端保持一致，避免 DeepSeek 隐藏思考消耗简短路由/回答预算。
        if (platform == AiPlatformEnum.DEEP_SEEK) optionsBuilder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        var options = optionsBuilder.build();
        var client = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        return new Session(client, options, true);
    }
}
