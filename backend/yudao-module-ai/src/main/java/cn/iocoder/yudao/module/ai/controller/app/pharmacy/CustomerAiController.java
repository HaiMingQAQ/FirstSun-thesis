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
    private final cn.iocoder.yudao.module.ai.service.pharmacy.CustomerAiHistoryService history;
    public record TopicRequest(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long storeId) { }
    @PostMapping("/topics")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<?> createTopic(@Valid @RequestBody TopicRequest request) { return success(history.create(request.storeId())); }
    @GetMapping("/topics")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<?> topics(@RequestParam(required = false) @jakarta.validation.constraints.Positive Long beforeId) { return success(history.list(beforeId)); }
    @GetMapping("/topics/{id}")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<?> topic(@PathVariable @jakarta.validation.constraints.Positive Long id, @RequestParam(required = false) @jakarta.validation.constraints.Positive Long beforeId) { return success(history.detail(id, beforeId)); }
    @DeleteMapping("/topics/{id}")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Boolean> deleteTopic(@PathVariable @jakarta.validation.constraints.Positive Long id) { history.delete(id); return success(true); }
    @DeleteMapping("/topics")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Boolean> clearTopics() { history.clear(); return success(true); }
    @PostMapping("/consult")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<CustomerAiConsultRespVO> consult(@Valid @RequestBody CustomerAiConsultReqVO request) {
        return success(service.consult(request));
    }
    @PostMapping(value = "/consult/stream", produces = "text/event-stream")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(@Valid @RequestBody CustomerAiConsultReqVO request) {
        return service.stream(request);
    }
}
