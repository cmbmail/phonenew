package com.phonecost.dto;

import com.phonecost.domain.SysUser;

/**
 * User response DTO — excludes sensitive fields like password.
 * H-S07: Never return SysUser entity directly to API.
 */
public record UserResponse(
    Long id,
    String username,
    String realName,
    Byte role,
    Long orgId,
    Byte status,
    Byte mustChangePwd  // Byte in DB (0/1), not Boolean
) {
    public static UserResponse from(SysUser user) {
        return new UserResponse(
            user.getId(),
            user.getUsername(),
            user.getRealName(),
            user.getRole(),
            user.getOrgId(),
            user.getStatus(),
            user.getMustChangePwd()
        );
    }
}
