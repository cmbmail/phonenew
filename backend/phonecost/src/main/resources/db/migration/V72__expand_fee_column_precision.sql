-- V72: Extend fee column precision to preserve original import precision
-- Problem: bill_detail / allocation_result / bill_batch fee columns were DECIMAL(12,2),
--          MySQL auto-rounded imported values to 2 decimal places, losing original
--          precision (e.g. 0.055 yuan/min * 1001 min = 55.055, or 10/31*days = 15-digit).
-- Fix: widen scale to 15 (raw_data already stores full precision; DB columns now match).
-- DECIMAL(22,15): 7 integer digits + 15 fraction digits (max observed total ~130k).
--
-- NOTE: Some soft-deleted rows (deleted_at IS NOT NULL) contain corrupt legacy values
--       (e.g. total_fee ~1.05 billion, bill_batch.total_amount ~188 billion) that exceed
--       DECIMAL(22,15). These rows are invisible to the application (@Where deleted_at IS NULL)
--       and safe to remove before widening the columns.

-- 0. Purge soft-deleted legacy rows with out-of-range values
DELETE FROM bill_detail WHERE deleted_at IS NOT NULL AND ABS(total_fee) > 100000000;
DELETE FROM bill_batch WHERE deleted_at IS NOT NULL AND ABS(total_amount) > 100000000;

-- 1. bill_detail fee columns
ALTER TABLE bill_detail
    MODIFY COLUMN monthly_rent DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '月租',
    MODIFY COLUMN call_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '通话费',
    MODIFY COLUMN recording_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '录音费',
    MODIFY COLUMN crbt_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '彩铃费',
    MODIFY COLUMN flash_msg_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '闪信费',
    MODIFY COLUMN total_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '合计';

-- allocation_result fee columns
ALTER TABLE allocation_result
    MODIFY COLUMN monthly_rent DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '月租',
    MODIFY COLUMN call_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '通话费',
    MODIFY COLUMN recording_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '录音费',
    MODIFY COLUMN crbt_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '彩铃费',
    MODIFY COLUMN flash_msg_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '闪信费',
    MODIFY COLUMN total_fee DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '合计';

-- bill_batch.total_amount
ALTER TABLE bill_batch
    MODIFY COLUMN total_amount DECIMAL(22,15) NOT NULL DEFAULT '0' COMMENT '总金额';