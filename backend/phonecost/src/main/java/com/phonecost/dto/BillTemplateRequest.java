package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 账单模板创建/更新请求 DTO
 */
@Data
public class BillTemplateRequest {
    @NotBlank(message = "模板名称不能为空")
    private String name;
    private String operator;
    private String description;
    private String monthPattern;
    @NotBlank(message = "Sheet配置不能为空")
    private String sheetConfigs;
}
