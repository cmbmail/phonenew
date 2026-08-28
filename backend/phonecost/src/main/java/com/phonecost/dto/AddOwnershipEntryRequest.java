package com.phonecost.dto;

import lombok.Data;

/**
 * H-S01: Replace Map<String, Object> for addOwnershipEntry
 */
@Data
public class AddOwnershipEntryRequest {
    private String phoneNumber;
    private String extension;
    private String fullPath;
    private String l1Branch;
    private String l2Branch;
    private String description;
    private Byte status;
}
