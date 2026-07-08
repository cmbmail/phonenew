package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新通讯录异常原因请求 DTO
 */
@Data
public class UpdateExceptionReasonRequest {
    private String reason;
}
