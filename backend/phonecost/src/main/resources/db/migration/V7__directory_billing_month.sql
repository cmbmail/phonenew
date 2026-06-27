-- V7: Add billing_month to directory_batch for snapshot support
ALTER TABLE directory_batch ADD COLUMN billing_month VARCHAR(7) NULL COMMENT '快照月份(yyyy-MM)' AFTER seconded_count;
