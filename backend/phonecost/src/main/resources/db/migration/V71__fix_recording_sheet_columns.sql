-- V71: Fix bill_template RECORDING sheet columns - add missing recordingDir (index 2)
-- The RECORDING sheet template was missing index 2 (recordingDir / close time) column mapping.
-- This caused raw_data to store the close time as "col_2" instead of "recordingDir",
-- making it invisible in the frontend recording tab.

UPDATE bill_template
SET sheet_configs = JSON_SET(
    sheet_configs,
    '$[1].columns',
    JSON_ARRAY(
        JSON_OBJECT('index', 0, 'field', 'extension', 'type', 'STRING'),
        JSON_OBJECT('index', 1, 'field', 'phoneNumber', 'type', 'STRING'),
        JSON_OBJECT('index', 2, 'field', 'recordingDir', 'type', 'STRING'),
        JSON_OBJECT('index', 3, 'field', 'recordingFee', 'type', 'DECIMAL')
    )
)
WHERE is_active = 1 AND deleted_at IS NULL;
