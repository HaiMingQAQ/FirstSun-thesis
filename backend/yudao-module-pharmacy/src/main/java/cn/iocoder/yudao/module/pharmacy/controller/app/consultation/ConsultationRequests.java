package cn.iocoder.yudao.module.pharmacy.controller.app.consultation;
import jakarta.validation.constraints.*;
public final class ConsultationRequests {
    private ConsultationRequests() { }
    public record Open(@NotNull @Positive Long storeId,@NotNull @Pattern(regexp="PHARMACIST|SERVICE") String kind) { }
    public record Send(@NotNull @Positive Long conversationId,
                       @NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{8,64}") String clientRequestId,
                       @NotBlank @Size(max=1000) String content) { }
    public record Read(@NotNull @Positive Long conversationId,@NotNull @Positive Long throughId) { }
}
