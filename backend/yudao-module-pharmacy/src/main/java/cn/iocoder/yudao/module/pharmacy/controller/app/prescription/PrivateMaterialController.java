package cn.iocoder.yudao.module.pharmacy.controller.app.prescription;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.pharmacy.service.prescription.PrivateMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
@RestController @RequiredArgsConstructor @RequestMapping("/member/prescription/material")
public class PrivateMaterialController {
    private final PrivateMaterialService service;
    @PostMapping("/upload") @ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Long> upload(@RequestParam Long storeId,@RequestParam MultipartFile file) throws java.io.IOException {
        return CommonResult.success(service.upload(storeId,file));
    }
    @GetMapping("/get") @ApiAccessLog(requestEnable=false,responseEnable=false)
    public ResponseEntity<byte[]> get(@RequestParam Long id) {
        var row=service.getForMember(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(row.getMimeType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(row.getContent());
    }
}
