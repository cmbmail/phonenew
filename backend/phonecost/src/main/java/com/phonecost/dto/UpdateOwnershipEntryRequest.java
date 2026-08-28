package com.phonecost.dto;

import lombok.Data;

/**
 * H-S01: Replace Map<String, Object> for updateOwnershipEntry
 */
@Data
public class UpdateOwnershipEntryRequest {
    private String l1Branch;
    private String l2Branch;
    private Byte status;
}
