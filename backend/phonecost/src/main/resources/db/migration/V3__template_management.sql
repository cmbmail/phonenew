-- V3: 账单模板管理增强
-- 1. 新增 month_pattern / description 字段
-- 2. 修正种子数据为正确的数字列索引格式（与实际Excel对齐）

ALTER TABLE bill_template
    ADD COLUMN month_pattern    VARCHAR(200) NULL COMMENT '账期提取正则' AFTER operator,
    ADD COLUMN description      VARCHAR(500) NULL COMMENT '模板描述' AFTER month_pattern;

-- 更新种子数据：使用数字列索引格式（与实际电信账单Excel列对齐）
UPDATE bill_template SET
    name = '中国电信标准模板',
    month_pattern = '(\\d{4})年(\\d{1,2})月',
    description = '中国电信标准4-Sheet账单：按号码费用/录音/彩铃/闪信',
    sheet_configs = '[
        {
            "sheetNamePattern": "按号码费用",
            "sheetType": "CALL",
            "phoneColumn": 0,
            "skipRows": 1,
            "isQuarterly": false,
            "columns": [
                {"index": 0, "field": "phoneNumber", "type": "STRING"},
                {"index": 1, "field": "platformFee", "type": "DECIMAL"},
                {"index": 2, "field": "monthlyRentCode", "type": "DECIMAL"},
                {"index": 5, "field": "domesticFee", "type": "DECIMAL"},
                {"index": 7, "field": "internationalFee", "type": "DECIMAL"},
                {"index": 8, "field": "totalFee", "type": "DECIMAL"}
            ],
            "computedFields": {
                "monthlyRent": ["platformFee", "monthlyRentCode"],
                "callFee": ["domesticFee", "internationalFee"]
            }
        },
        {
            "sheetNamePattern": "录音",
            "sheetType": "RECORDING",
            "phoneColumn": 1,
            "extensionColumn": 0,
            "skipRows": 1,
            "isQuarterly": false,
            "columns": [
                {"index": 0, "field": "extension", "type": "STRING"},
                {"index": 1, "field": "phoneNumber", "type": "STRING"},
                {"index": 3, "field": "recordingFee", "type": "DECIMAL"}
            ]
        },
        {
            "sheetNamePattern": "彩铃",
            "sheetType": "CRBT",
            "phoneColumn": 1,
            "extensionColumn": 0,
            "skipRows": 1,
            "isQuarterly": false,
            "columns": [
                {"index": 0, "field": "extension", "type": "STRING"},
                {"index": 1, "field": "phoneNumber", "type": "STRING"},
                {"index": 2, "field": "crbtFee", "type": "DECIMAL"}
            ]
        },
        {
            "sheetNamePattern": "闪信",
            "sheetType": "FLASH_MSG",
            "phoneColumn": 0,
            "skipRows": 1,
            "isQuarterly": true,
            "columns": [
                {"index": 0, "field": "phoneNumber", "type": "STRING"},
                {"index": 1, "field": "flashMonth", "type": "STRING"},
                {"index": 3, "field": "flashMsgFee", "type": "DECIMAL"}
            ]
        }
    ]'
WHERE id = 1;
