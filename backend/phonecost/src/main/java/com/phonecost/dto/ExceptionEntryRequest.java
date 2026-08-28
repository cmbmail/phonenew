package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * H-S01: Replace Map<String, String> for addExceptionEntry / updateExceptionEntry
 */
@Data
public class ExceptionEntryRequest {
    @NotBlank(message = "月份不能为空")
    private String billingMonth;

    @NotBlank(message = "号码不能为空")
    private String phoneNumber;

    private String extension;
    private String fullPath;
    private String l1Branch;
    private String l2Branch;
    private String description;
}
