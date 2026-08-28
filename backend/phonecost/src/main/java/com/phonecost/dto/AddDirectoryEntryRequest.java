package com.phonecost.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * H-S01: Replace Map<String, String> for addDirectoryEntry
 */
@Data
public class AddDirectoryEntryRequest {
    @NotBlank(message = "部门路径不能为空")
    private String deptPath;

    private String username;
    private String extension;
    private String phoneNumber;
    private String allocDept;
    private String orgCode;
    private String costCenter;
    private String remark;
}
