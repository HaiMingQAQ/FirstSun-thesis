package cn.iocoder.yudao.module.pharmacy.job.member;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.base.StoreMapper;
import cn.iocoder.yudao.module.pharmacy.service.member.WxOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
/** Explicitly enabled instance lifecycle task, reusing the existing transactional expiry service. */
@Component @RequiredArgsConstructor @Slf4j @EnableScheduling
@ConditionalOnProperty(name="firstsun.miniapp.order-expiry-enabled",havingValue="true")
public class CustomerOrderExpiryJob {
    private final TenantFrameworkService tenants;
    private final StoreMapper stores;
    private final WxOrderService orders;
    @Scheduled(fixedDelayString="${firstsun.miniapp.order-expiry-delay-ms:60000}",initialDelay=10000)
    public void closeExpired() {
        for(Long tenant:tenants.getTenantIds()) {
            try {tenants.validTenant(tenant);TenantUtils.execute(tenant,()-> {
                for(var store:stores.selectList()) orders.closeExpiredWxOrders(store.getId(),100);
            });} catch(Exception ex) {log.warn("[closeExpired][tenant={} lifecycle failed type={} code={} at={}]",tenant,
                    ex.getClass().getSimpleName(), ex instanceof cn.iocoder.yudao.framework.common.exception.ServiceException service ? service.getCode() : null,
                    ex.getStackTrace().length == 0 ? "unknown" : ex.getStackTrace()[0]);}
        }
    }
}
