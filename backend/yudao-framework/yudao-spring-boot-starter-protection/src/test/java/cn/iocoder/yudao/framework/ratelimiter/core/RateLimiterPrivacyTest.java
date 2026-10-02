package cn.iocoder.yudao.framework.ratelimiter.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.aop.RateLimiterAspect;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.DefaultRateLimiterKeyResolver;
import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimiterPrivacyTest {
    @RateLimiter public void privateRequest(String content) { }
    @Test void rejectedRequestNeverReadsOrLogsArguments() throws Exception {
        var resolver=mock(DefaultRateLimiterKeyResolver.class);
        var redis=mock(RateLimiterRedisDAO.class);
        var point=mock(JoinPoint.class);
        var signature=mock(Signature.class);
        when(point.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn("privateRequest(String)");
        var annotation=getClass().getMethod("privateRequest",String.class).getAnnotation(RateLimiter.class);
        when(resolver.resolver(point,annotation)).thenReturn("test-only-key");
        var logger=(Logger)LoggerFactory.getLogger(RateLimiterAspect.class);
        var appender=new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            assertThrows(ServiceException.class,()->new RateLimiterAspect(List.of(resolver),redis).beforePointCut(point,annotation));
            verify(point,never()).getArgs();
            assertEquals(1,appender.list.size());
            assertFalse(appender.list.get(0).getFormattedMessage().contains("参数"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
