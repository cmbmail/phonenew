package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 设置通讯录月份请求 DTO
 */
@Data
public class SetDirectoryMonthRequest {
    @NotBlank(message = "月份不能为空")
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "月份格式错误，应为 yyyy-MM")
    private String billingMonth;
}
