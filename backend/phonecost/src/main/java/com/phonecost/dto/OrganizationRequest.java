package com.phonecost.dto;

import lombok.Data;

/**
 * 组织创建/更新请求 DTO
 * 防止 Mass Assignment：只暴露允许用户设置的字段
 */
@Data
public class OrganizationRequest {
    private String name;
    private String code;
    private String costCenter;
    private Long parentId;
    private Byte type;  // 1=集团 2=分行 3=部门 4=综合支行 5=零专支行
}
