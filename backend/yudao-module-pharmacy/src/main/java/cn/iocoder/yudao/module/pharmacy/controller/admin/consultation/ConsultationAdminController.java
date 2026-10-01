package cn.iocoder.yudao.module.pharmacy.controller.admin.consultation;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.pharmacy.controller.app.consultation.ConsultationRequests;
import cn.iocoder.yudao.module.pharmacy.service.consultation.ConsultationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @Validated @RequiredArgsConstructor @RequestMapping("/pharmacy/consultation")
public class ConsultationAdminController {
    private final ConsultationService service;
    @GetMapping("/page") @PreAuthorize("@ss.hasPermission('pharmacy:consultation:query')")
    public CommonResult<PageResult<ConsultationService.Conversation>> page(@RequestParam(required=false) @Positive Long storeId,@RequestParam(defaultValue="1") @Min(1) Integer pageNo) {return CommonResult.success(service.page(true,storeId,pageNo));}
    @GetMapping("/messages") @PreAuthorize("@ss.hasPermission('pharmacy:consultation:query')")
    public CommonResult<ConsultationService.Thread> messages(@RequestParam @Positive Long id,@RequestParam(defaultValue="0") @Min(0) Long after,@RequestParam(required=false) @Positive Long before) {return CommonResult.success(service.thread(id,true,after,before));}
    @PostMapping("/claim") @PreAuthorize("@ss.hasPermission('pharmacy:consultation:reply')")
    public CommonResult<Boolean> claim(@RequestParam @Positive Long id) {return CommonResult.success(service.claim(id));}
    @PostMapping("/send") @PreAuthorize("@ss.hasPermission('pharmacy:consultation:reply')")
    public CommonResult<Long> send(@RequestBody @Valid ConsultationRequests.Send request) {return CommonResult.success(service.send(request,true));}
    @PutMapping("/read") @PreAuthorize("@ss.hasPermission('pharmacy:consultation:query')")
    public CommonResult<Boolean> read(@RequestBody @Valid ConsultationRequests.Read request) {return CommonResult.success(service.read(request,true));}
}
