package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.pharmacy.config.MemberEmailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.EMAIL_SEND_FAILED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResendEmailSenderTest {

    private final MemberEmailProperties properties = new MemberEmailProperties();
    private final HttpClient client = mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    private final HttpResponse<String> response = mock(HttpResponse.class);
    private final ResendEmailSender sender = new ResendEmailSender(properties, client);

    @BeforeEach
    void setUp() throws Exception {
        properties.setEnabled(true);
        properties.setFrom("sender@example.com");
        properties.setApiKey("offline-test-key");
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
    }

    @Test
    void successfulResponseUsesFixedHttpsEndpointAndBoundedTimeout() throws Exception {
        when(response.statusCode()).thenReturn(201);
        when(response.body()).thenReturn("{\"id\":\"test-message-id\"}");
        sender.sendCode("a@example.com", "login", "123456");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("https://api.resend.com/emails", request.getValue().uri().toString());
        assertEquals("POST", request.getValue().method());
        assertEquals(Duration.ofSeconds(10), request.getValue().timeout().orElseThrow());
    }

    @Test
    void only200Or201WithNonBlankTextIdIsSuccess() {
        for (int status : new int[]{202, 204, 302, 400, 403, 429, 500}) {
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenReturn("{\"id\":\"x\",\"message\":\"secret-code-body\"}");
            assertSafeFailure();
        }
        when(response.statusCode()).thenReturn(200);
        for (String body : new String[]{"", "not-json", "{}", "{\"id\":\" \"}", "{\"id\":1}"}) {
            when(response.body()).thenReturn(body);
            assertSafeFailure();
        }
    }

    @Test
    void transportFailuresDoNotExposeSupplierDetails() throws Exception {
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.io.IOException("offline-test-key secret-code-body"));
        assertSafeFailure();
    }

    @Test
    void defaultClientNeverRedirectsAndHasConnectTimeout() {
        HttpClient actual = ResendEmailSender.createHttpClient();
        assertEquals(HttpClient.Redirect.NEVER, actual.followRedirects());
        assertEquals(Duration.ofSeconds(5), actual.connectTimeout().orElseThrow());
    }

    @Test
    void springCanSelectProductionConstructorWithoutNetworkOrExtraBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MemberEmailProperties.class, MemberEmailProperties::new);
            context.register(ResendEmailSender.class);
            context.refresh();
            assertNotNull(context.getBean(ResendEmailSender.class));
        }
    }

    private void assertSafeFailure() {
        var ex = assertThrows(ServiceException.class, () -> sender.sendCode("a@example.com", "login", "123456"));
        assertEquals(EMAIL_SEND_FAILED.getCode(), ex.getCode());
        assertFalse(ex.toString().contains("secret-code-body"));
        assertFalse(ex.toString().contains("offline-test-key"));
        assertNull(ex.getCause());
    }
}
