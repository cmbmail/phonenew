-- Fix: ownership_source VARCHAR(2) too small for 'ADJUSTED' value
-- Used by AllocationAdjustService when adjusting fees between organizations
ALTER TABLE bill_detail MODIFY COLUMN ownership_source VARCHAR(20) NOT NULL DEFAULT '' COMMENT '归属来源: P0/P1/P2/P3/ADJUSTED';
