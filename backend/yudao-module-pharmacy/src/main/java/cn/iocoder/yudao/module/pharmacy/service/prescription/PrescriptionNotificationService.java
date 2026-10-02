package cn.iocoder.yudao.module.pharmacy.service.prescription;
import cn.iocoder.yudao.module.system.service.notify.NotifyMessageService;
import cn.iocoder.yudao.module.system.service.notify.NotifyTemplateService;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service @RequiredArgsConstructor
public class PrescriptionNotificationService {
    private final NotifyMessageService messages;
    private final NotifyTemplateService templates;
    public void send(Long member,Long prescId,String title) {
        if(member==null)return;
        var template=templates.getNotifyTemplateByCodeFromCache("pharmacy_prescription_event");
        if(template==null||!Integer.valueOf(0).equals(template.getStatus()))throw new IllegalStateException("处方通知模板未配置");
        messages.createNotifyMessage(member,UserTypeEnum.MEMBER.getValue(),template,title,Map.of("title",title,"prescId",prescId));
    }
}
