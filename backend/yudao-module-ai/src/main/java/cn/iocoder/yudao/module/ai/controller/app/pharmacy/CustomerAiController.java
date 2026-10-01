package cn.iocoder.yudao.module.ai.controller.app.pharmacy;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultReqVO;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO;
import cn.iocoder.yudao.module.ai.service.pharmacy.CustomerAiConsultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
@RestController @Validated @RequiredArgsConstructor @RequestMapping("/pharmacy/ai")
public class CustomerAiController {
    private final CustomerAiConsultService service;
    @PostMapping("/consult")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<CustomerAiConsultRespVO> consult(@Valid @RequestBody CustomerAiConsultReqVO request) {
        return success(service.consult(request));
    }
}
