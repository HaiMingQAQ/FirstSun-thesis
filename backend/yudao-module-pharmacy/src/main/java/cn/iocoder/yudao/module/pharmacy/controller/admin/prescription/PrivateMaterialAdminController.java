package cn.iocoder.yudao.module.pharmacy.controller.admin.prescription;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.pharmacy.service.prescription.PrivateMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.*;
@RestController @RequiredArgsConstructor @RequestMapping("/pharmacy/prescription/material")
public class PrivateMaterialAdminController {
    private final PrivateMaterialService service;
    @GetMapping("/get") @PreAuthorize("@ss.hasPermission('pharmacy:prescription:query')")
    @ApiAccessLog(requestEnable=false,responseEnable=false)
    public ResponseEntity<byte[]> get(@RequestParam Long id) {
        var row=service.getForPharmacist(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(row.getMimeType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(row.getContent());
    }
}
