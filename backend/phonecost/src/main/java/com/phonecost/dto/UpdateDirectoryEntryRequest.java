package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateDirectoryEntryRequest {
    @NotBlank(message = "部门路径不能为空")
    private String deptPath;

    private String allocDept;
    private String orgCode;
    private String costCenter;
    private String remark;
}
