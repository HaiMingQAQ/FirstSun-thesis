package cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo;
import jakarta.validation.constraints.*;
import lombok.Data;
import jakarta.validation.Valid;
import java.util.List;
@Data
public class CustomerAiConsultReqVO {
    @NotBlank @Size(max = 64) @Pattern(regexp = "[a-zA-Z0-9_-]+")
    private String clientMessageId;
    @NotBlank @Size(max = 500)
    private String content;
    @NotNull @Positive
    private Long storeId;
    @Positive private Long topicId;
    /** Bounded client conversation, treated as untrusted context, never as identity or catalogue facts. */
    @Valid @Size(max = 6)
    private List<Turn> context;
    public record Turn(@NotBlank @Pattern(regexp = "user|assistant") String role,
                       @NotBlank @Size(max = 1000) String content) { }
}
