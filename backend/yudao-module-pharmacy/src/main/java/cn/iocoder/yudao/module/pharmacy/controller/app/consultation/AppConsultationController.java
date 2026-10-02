package cn.iocoder.yudao.module.pharmacy.controller.app.consultation;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.pharmacy.service.consultation.ConsultationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @Validated @RequiredArgsConstructor @RequestMapping("/member/consultation")
public class AppConsultationController {
    private final ConsultationService service;
    @PostMapping("/open") public CommonResult<Long> open(@RequestBody @Valid ConsultationRequests.Open request) {return CommonResult.success(service.open(request));}
    @GetMapping("/page") public CommonResult<PageResult<ConsultationService.Conversation>> page(@RequestParam(defaultValue="1") @Min(1) Integer pageNo) {return CommonResult.success(service.page(false,null,pageNo));}
    @GetMapping("/messages") public CommonResult<ConsultationService.Thread> messages(@RequestParam @Positive Long id,@RequestParam(defaultValue="0") @Min(0) Long after,@RequestParam(required=false) @Positive Long before) {return CommonResult.success(service.thread(id,false,after,before));}
    @PostMapping("/send") public CommonResult<Long> send(@RequestBody @Valid ConsultationRequests.Send request) {return CommonResult.success(service.send(request,false));}
    @PutMapping("/read") public CommonResult<Boolean> read(@RequestBody @Valid ConsultationRequests.Read request) {return CommonResult.success(service.read(request,false));}
}
