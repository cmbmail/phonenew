package com.phonecost.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量清除异常请求 DTO
 */
@Data
public class BatchClearExceptionRequest {
    @NotEmpty(message = "ids 不能为空")
    private List<Long> ids;
}
