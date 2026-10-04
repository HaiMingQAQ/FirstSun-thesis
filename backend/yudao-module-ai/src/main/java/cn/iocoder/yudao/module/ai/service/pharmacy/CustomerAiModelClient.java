package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.module.ai.dal.dataobject.model.AiModelDO;
import cn.iocoder.yudao.module.ai.enums.model.AiPlatformEnum;
import cn.iocoder.yudao.module.ai.service.model.AiApiKeyService;
import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import cn.iocoder.yudao.module.ai.util.AiUtils;
import lombok.RequiredArgsConstructor;
import io.netty.channel.ChannelOption;
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

/** Customer-only provider options; other AI features keep their existing model clients. */
@Service
@RequiredArgsConstructor
public class CustomerAiModelClient {
    private final AiModelService models;
    private final AiApiKeyService apiKeys;

    public record Session(ChatModel client, ChatOptions options) { }

    public Session forModel(AiModelDO model, Double temperature, Integer maxTokens) {
        var platform = AiPlatformEnum.validatePlatform(model.getPlatform());
        if (platform != AiPlatformEnum.DEEP_SEEK) {
            return new Session(models.getChatModel(model.getId()),
                    AiUtils.buildChatOptions(platform, model.getModel(), temperature, maxTokens));
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
        // RestClient's auto-selected Apache client retries 429/503 outside the model retry limit.
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(10000);
        requests.setReadTimeout(20000);
        var streaming = HttpClient.create().disableRetry(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000).responseTimeout(Duration.ofSeconds(45));
        var api = OpenAiApi.builder().apiKey(apiKey)
                .baseUrl(StringUtils.hasText(url) ? url.trim() : "https://api.deepseek.com")
                .restClientBuilder(RestClient.builder().requestFactory(requests))
                .webClientBuilder(WebClient.builder().clientConnector(new ReactorClientHttpConnector(streaming)))
                .completionsPath("/chat/completions").build();
        // Flash defaults to reasoning; its hidden tokens can exhaust our short answer budget.
        var options = OpenAiChatOptions.builder().model(validated.getModel()).temperature(temperature)
                .maxTokens(maxTokens).internalToolExecutionEnabled(false)
                .extraBody(Map.of("thinking", Map.of("type", "disabled"))).build();
        var client = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        return new Session(client, options);
    }
}
