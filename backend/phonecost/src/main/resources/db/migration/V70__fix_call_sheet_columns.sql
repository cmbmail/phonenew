-- V70: Fix bill_template CALL sheet columns - add missing duration fields
-- The CALL sheet template was missing domesticDuration (index 3), transferDuration (index 4),
-- internationalDuration (index 6), and remark (index 9) column mappings.
-- This caused raw_data to store these fields as col_3/col_4/col_6/col_9 instead of proper field names,
-- making them invisible in the frontend.

UPDATE bill_template
SET sheet_configs = JSON_SET(
    sheet_configs,
    '$[0].columns',
    JSON_ARRAY(
        JSON_OBJECT('index', 0, 'field', 'phoneNumber', 'type', 'STRING'),
        JSON_OBJECT('index', 1, 'field', 'platformFee', 'type', 'DECIMAL'),
        JSON_OBJECT('index', 2, 'field', 'monthlyRentCode', 'type', 'DECIMAL'),
        JSON_OBJECT('index', 3, 'field', 'domesticDuration', 'type', 'DECIMAL'),
        JSON_OBJECT('index', 4, 'field', 'transferDuration', 'type', 'DECIMAL'),
        JSON_OBJECT('index', 5, 'field', 'domesticFee', 'type', 'DECIMAL'),
        JSON_OBJECT('index', 6, 'field', 'internationalDuration', 'type', 'DECIMAL'),
        JSON_OBJECT('index', 7, 'field', 'internationalFee', 'type', 'DECIMAL'),
        JSON_OBJECT('index', 8, 'field', 'totalFee', 'type', 'DECIMAL'),
        JSON_OBJECT('index', 9, 'field', 'remark', 'type', 'STRING')
    )
)
WHERE is_active = 1 AND deleted_at IS NULL;
