package im.tlgxy.cloud.module.tenant.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SaveTenantRequest {

    private Integer id;

    @NotBlank(message = "工作空间名称不能为空")
    @Size(max = 100, message = "工作空间名称不能超过 100 个字符")
    private String name;

    private Boolean enable = true;
}

