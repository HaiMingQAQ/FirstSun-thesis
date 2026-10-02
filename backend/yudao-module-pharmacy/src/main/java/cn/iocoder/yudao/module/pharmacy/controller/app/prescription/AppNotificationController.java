package cn.iocoder.yudao.module.pharmacy.controller.app.prescription;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess;
import cn.iocoder.yudao.module.system.service.notify.NotifyMessageService;
import cn.iocoder.yudao.module.system.controller.admin.notify.vo.message.NotifyMessageMyPageReqVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
@RestController @RequiredArgsConstructor @Validated @RequestMapping("/member/business-notification")
public class AppNotificationController {
    private final NotifyMessageService messages;
    public record Notice(Long id,String content,boolean readStatus,java.time.LocalDateTime createTime,Map<String,Object> reference) { }
    @GetMapping("/page") public CommonResult<PageResult<Notice>> page(
            @RequestParam(defaultValue="1") @jakarta.validation.constraints.Min(1) Integer pageNo) {
        Long member=AppMemberAccess.requireMember();var req=new NotifyMessageMyPageReqVO();req.setPageNo(pageNo);req.setPageSize(20);
        var result=messages.getMyMyNotifyMessagePage(req,member,UserTypeEnum.MEMBER.getValue());
        return CommonResult.success(new PageResult<>(result.getList().stream().map(r->new Notice(r.getId(),r.getTemplateContent(),
                Boolean.TRUE.equals(r.getReadStatus()),r.getCreateTime(),r.getTemplateParams())).toList(),result.getTotal()));
    }
    @GetMapping("/unread") public CommonResult<Long> unread() {
        return CommonResult.success(messages.getUnreadNotifyMessageCount(AppMemberAccess.requireMember(),UserTypeEnum.MEMBER.getValue()));
    }
    @PutMapping("/read") public CommonResult<Boolean> read(@RequestParam Long id) {
        Long member=AppMemberAccess.requireMember();var record=messages.getNotifyMessage(id);
        if(record==null||!Objects.equals(record.getUserId(),member)||!Objects.equals(record.getUserType(),UserTypeEnum.MEMBER.getValue()))
            throw new AccessDeniedException("无通知访问权限");
        messages.updateNotifyMessageRead(List.of(id),member,UserTypeEnum.MEMBER.getValue());return CommonResult.success(true);
    }
}
