package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.module.pharmacy.config.MemberEmailProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberPasswordResetSenderTest {
    @Test void resetPurposeUsesExistingSenderAndNoRealTransport()throws Exception{
        var properties=new MemberEmailProperties();properties.setEnabled(true);properties.setFrom("sender@example.test");properties.setApiKey("offline-only");
        var client=mock(HttpClient.class);HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(201);when(response.body()).thenReturn("{\"id\":\"offline-message\"}");
        when(client.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        new ResendEmailSender(properties,client).sendCode("member@example.test",MemberEmailCodeService.SCENE_PASSWORD_RESET,"123456");
        var request=ArgumentCaptor.forClass(HttpRequest.class);verify(client).send(request.capture(),any(HttpResponse.BodyHandler.class));
        var text=new StringBuilder();var done=new CompletableFuture<Void>();
        request.getValue().bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer data){text.append(StandardCharsets.UTF_8.decode(data));}
            public void onError(Throwable error){done.completeExceptionally(error);}
            public void onComplete(){done.complete(null);}
        });
        done.get(2,TimeUnit.SECONDS);assertTrue(text.toString().contains("重置密码"));assertTrue(text.toString().contains("123456"));
        assertEquals("https://api.resend.com/emails",request.getValue().uri().toString());
    }
}
