package cn.iocoder.yudao.module.ai.controller.admin.pharmacy;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.ai.service.pharmacy.PharmacyKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理端：药品说明书知识库（RAG）。
 *
 * 重建索引是幂等的，可在语料更新后重复执行；检索接口用于演示与排障，
 * 能看到每条召回片段的药品、小节与相似度，便于说明「回答依据从哪来」。
 */
@RestController
@RequestMapping("/pharmacy/ai/knowledge")
@RequiredArgsConstructor
public class PharmacyAiKnowledgeController {

    private final PharmacyKnowledgeService knowledge;

    @PostMapping("/reindex")
    @PreAuthorize("@ss.hasPermission('pharmacy:ai:chat')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<PharmacyKnowledgeService.ReindexResult> reindex() {
        return success(knowledge.reindex());
    }

    @GetMapping("/search")
    @PreAuthorize("@ss.hasPermission('pharmacy:ai:chat')")
    @ApiAccessLog(requestEnable = false, responseEnable = false, sanitizeKeys = {"question"})
    public CommonResult<Map<String, Object>> search(@RequestParam("question") String question,
                                                    @RequestParam(value = "topK", defaultValue = "4") int topK,
                                                    @RequestParam(value = "saleableOnly", defaultValue = "true") boolean saleableOnly) {
        List<PharmacyKnowledgeService.Hit> hits = knowledge.search(question, topK, saleableOnly);
        return success(Map.of("question", question, "hits", hits,
                "points", knowledge.countPoints().orElse(-1L)));
    }
}
