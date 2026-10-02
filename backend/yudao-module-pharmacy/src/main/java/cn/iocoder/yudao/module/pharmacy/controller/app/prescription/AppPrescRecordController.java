package cn.iocoder.yudao.module.pharmacy.controller.app.prescription;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess;
import cn.iocoder.yudao.module.pharmacy.controller.app.prescription.vo.AppPrescRecordCreateReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.app.prescription.vo.AppPrescRecordRespVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.prescription.PhPrescRecordDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription.PrescRecordMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.PRESC_NOT_EXISTS;
import static cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.PRESC_NOT_OWNER;

/**
 * 用户 APP - 处方（小程序端处方药闭环）
 *
 * <p>只提供小程序端最小能力：登记处方（纸质拍照，来源=0）、我的处方列表、处方详情。
 * 审方（通过/驳回/作废）仍走 E 的管理端 {@code PrescRecordService#reviewPrescRecord}，
 * 小程序端不直连管理接口、不复制管理 Controller，仅复用 E 的登记 Service 与数据访问。
 */
@Tag(name = "用户 APP - 处方")
@RestController
@RequestMapping("/member/prescription")
@Validated
public class AppPrescRecordController {

    @Resource
    private PrescRecordMapper prescRecordMapper;
    @Resource private cn.iocoder.yudao.module.pharmacy.service.prescription.AppPrescriptionService appPrescriptions;
    @Resource private cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription.PrescriptionUseMapper uses;

    @PostMapping("/create")
    @Operation(summary = "提交私有处方材料与申请明细")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable=false,responseEnable=false)
    public CommonResult<Long> createPrescRecord(@Valid @RequestBody AppPrescRecordCreateReqVO req) {
        AppMemberAccess.requireMember();
        return success(appPrescriptions.submit(req));
    }

    @GetMapping("/page")
    @Operation(summary = "获得我的处方列表（按创建时间倒序）")
    public CommonResult<List<AppPrescRecordRespVO>> getMyPrescRecords() {
        List<PhPrescRecordDO> records = prescRecordMapper.selectList(PhPrescRecordDO::getWxMemberId, AppMemberAccess.requireMember());
        return success(toRespVO(records));
    }

    @GetMapping("/get")
    @Operation(summary = "获得我的处方详情（校验归属）")
    @Parameter(name = "id", description = "处方编号", required = true, example = "1024")
    public CommonResult<AppPrescRecordRespVO> getPrescRecord(@RequestParam("id") Long id) {
        Long memberId = AppMemberAccess.requireMember();
        PhPrescRecordDO record = prescRecordMapper.selectById(id);
        if (record == null) {
            throw exception(PRESC_NOT_EXISTS);
        }
        if (!Objects.equals(record.getWxMemberId(), memberId)) {
            throw exception(PRESC_NOT_OWNER);
        }
        return success(toRespVO(List.of(record)).get(0));
    }

    // ========== 私有方法 ==========

    private List<AppPrescRecordRespVO> toRespVO(List<PhPrescRecordDO> records) {
        return records.stream()
                .map(record -> {
                    AppPrescRecordRespVO respVO = new AppPrescRecordRespVO();
                    org.springframework.beans.BeanUtils.copyProperties(record, respVO);
                    // 历史公开 URL 也不能冒充受鉴权保护的处方影像。
                    respVO.setImages(List.of());
                    respVO.setMaterialIds(record.getImages() == null ? List.of() : JsonUtils.parseArray(record.getImages(),String.class).stream()
                            .filter(v->v.matches("private:[0-9]+")) .map(v->Long.valueOf(v.substring(8))).toList());
                    respVO.setApprovedItems(record.getApprovedItems() == null ? List.of() : JsonUtils.parseArray(record.getApprovedItems(),cn.iocoder.yudao.module.pharmacy.controller.admin.prescription.vo.PrescItemVO.class));
                    respVO.setApprovedUntil(record.getApprovedUntil());
                    respVO.setRequestedItems(record.getPrescribedItems() == null ? List.of() : JsonUtils.parseArray(record.getPrescribedItems(),cn.iocoder.yudao.module.pharmacy.controller.admin.prescription.vo.PrescItemVO.class));
                    respVO.setUses(uses.selectList(cn.iocoder.yudao.module.pharmacy.dal.dataobject.prescription.PrescriptionUseDO::getPrescId,record.getId()).stream()
                            .map(use->new AppPrescRecordRespVO.Use(use.getWxOrderId(),use.getStatus(),use.getReleaseReason(),use.getCreateTime())).toList());
                    return respVO;
                })
                .toList();
    }

}
