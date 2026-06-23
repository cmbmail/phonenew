package com.phonecost.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BillTemplate sheet_configs JSON validation logic.
 * Mirrors the private validateSheetConfigs() method in BillTemplateService
 * to ensure validation rules are correct without needing Spring context.
 */
class BillTemplateValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> VALID_TYPES = List.of("CALL", "RECORDING", "CRBT", "FLASH_MSG");

    /**
     * Extracted validation logic matching BillTemplateService.validateSheetConfigs()
     */
    private void validateSheetConfigs(String json) {
        try {
            List<Map<String, Object>> sheets = MAPPER.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});

            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("Sheet配置至少需要定义一个Sheet");
            }

            for (int i = 0; i < sheets.size(); i++) {
                Map<String, Object> sheet = sheets.get(i);
                String pattern = (String) sheet.get("sheetNamePattern");
                String sheetType = (String) sheet.get("sheetType");

                if (pattern == null || pattern.isBlank()) {
                    throw new IllegalArgumentException(String.format("第%d个Sheet配置缺少 sheetNamePattern", i + 1));
                }
                if (sheetType == null || sheetType.isBlank()) {
                    throw new IllegalArgumentException(String.format("第%d个Sheet配置缺少 sheetType", i + 1));
                }
                if (!VALID_TYPES.contains(sheetType)) {
                    throw new IllegalArgumentException(
                            String.format("第%d个Sheet的sheetType='%s'无效，允许值: %s", i + 1, sheetType, VALID_TYPES));
                }
                if (!sheet.containsKey("phoneColumn")) {
                    throw new IllegalArgumentException(String.format("第%d个Sheet配置缺少 phoneColumn", i + 1));
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Sheet配置JSON格式错误: " + e.getMessage());
        }
    }

    @Nested
    @DisplayName("Valid configs")
    class ValidConfigs {

        @Test
        @DisplayName("Single CALL sheet with all required fields")
        void singleCallSheet() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "按号码费用.*",
                            "sheetType", "CALL",
                            "phoneColumn", "A",
                            "feeColumns", Map.of("monthly_rent", "B", "call_fee", "C"))
            ));
            assertDoesNotThrow(() -> validateSheetConfigs(json));
        }

        @Test
        @DisplayName("All 4 valid sheetTypes accepted")
        void allValidTypes() throws Exception {
            for (String type : VALID_TYPES) {
                String json = MAPPER.writeValueAsString(List.of(
                        Map.of("sheetNamePattern", "test.*",
                                "sheetType", type,
                                "phoneColumn", "A")
                ));
                assertDoesNotThrow(() -> validateSheetConfigs(json), "Should accept type: " + type);
            }
        }

        @Test
        @DisplayName("Multiple sheets all valid")
        void multipleSheets() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "按号码费用.*", "sheetType", "CALL", "phoneColumn", "A"),
                    Map.of("sheetNamePattern", "录音费.*", "sheetType", "RECORDING", "phoneColumn", "A"),
                    Map.of("sheetNamePattern", "彩铃费.*", "sheetType", "CRBT", "phoneColumn", "A"),
                    Map.of("sheetNamePattern", "闪信费.*", "sheetType", "FLASH_MSG", "phoneColumn", "A")
            ));
            assertDoesNotThrow(() -> validateSheetConfigs(json));
        }

        @Test
        @DisplayName("Extra fields in sheet config ignored")
        void extraFieldsIgnored() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "test.*",
                            "sheetType", "CALL",
                            "phoneColumn", "A",
                            "extraField", "ignored",
                            "anotherExtra", 123)
            ));
            assertDoesNotThrow(() -> validateSheetConfigs(json));
        }
    }

    @Nested
    @DisplayName("Invalid configs - rejection")
    class InvalidConfigs {

        @Test
        @DisplayName("Empty array rejected")
        void emptyArray() throws Exception {
            String json = "[]";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs(json));
            assertTrue(ex.getMessage().contains("至少需要定义一个Sheet"));
        }

        @Test
        @DisplayName("Missing sheetNamePattern")
        void missingPattern() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetType", "CALL", "phoneColumn", "A")
            ));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs(json));
            assertTrue(ex.getMessage().contains("缺少 sheetNamePattern"));
            assertTrue(ex.getMessage().contains("第1个"));
        }

        @Test
        @DisplayName("Blank sheetNamePattern")
        void blankPattern() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "   ", "sheetType", "CALL", "phoneColumn", "A")
            ));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs(json));
            assertTrue(ex.getMessage().contains("缺少 sheetNamePattern"));
        }

        @Test
        @DisplayName("Missing sheetType")
        void missingSheetType() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "test.*", "phoneColumn", "A")
            ));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs(json));
            assertTrue(ex.getMessage().contains("缺少 sheetType"));
        }

        @Test
        @DisplayName("Invalid sheetType value")
        void invalidSheetType() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "test.*", "sheetType", "INVALID_TYPE", "phoneColumn", "A")
            ));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs(json));
            assertTrue(ex.getMessage().contains("无效"));
            assertTrue(ex.getMessage().contains("INVALID_TYPE"));
        }

        @Test
        @DisplayName("Missing phoneColumn")
        void missingPhoneColumn() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "test.*", "sheetType", "CALL")
            ));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs(json));
            assertTrue(ex.getMessage().contains("缺少 phoneColumn"));
        }

        @Test
        @DisplayName("Second sheet invalid reports correct index")
        void secondSheetInvalid() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "valid.*", "sheetType", "CALL", "phoneColumn", "A"),
                    Map.of("sheetNamePattern", "valid2.*", "sheetType", "CALL")
                    // missing phoneColumn on 2nd sheet
            ));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs(json));
            assertTrue(ex.getMessage().contains("第2个"));
        }

        @Test
        @DisplayName("Non-JSON string rejected")
        void nonJsonInput() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs("not-json-at-all"));
            assertTrue(ex.getMessage().contains("JSON格式错误"));
        }

        @Test
        @DisplayName("JSON object (not array) rejected")
        void jsonObjectNotArray() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> validateSheetConfigs("{\"key\":\"value\"}"));
            assertTrue(ex.getMessage().contains("JSON格式错误"));
        }
    }

    @Nested
    @DisplayName("Real-world template examples")
    class RealWorldExamples {

        @Test
        @DisplayName("China Telecom standard 4-sheet template")
        void chinaTelecomTemplate() throws Exception {
            String json = """
                [
                  {"sheetNamePattern":"按号码费用","sheetType":"CALL","phoneColumn":"A","feeColumns":{"monthly_rent":"B","call_fee":"D","total_fee":"F"}},
                  {"sheetNamePattern":"录音费","sheetType":"RECORDING","phoneColumn":"A","feeColumn":"C"},
                  {"sheetNamePattern":"彩铃费","sheetType":"CRBT","phoneColumn":"A","feeColumn":"B"},
                  {"sheetNamePattern":"闪信费","sheetType":"FLASH_MSG","phoneColumn":"A","feeColumn":"C"}
                ]
                """;
            assertDoesNotThrow(() -> validateSheetConfigs(json));
        }

        @Test
        @DisplayName("Minimal single-sheet template")
        void minimalTemplate() throws Exception {
            String json = MAPPER.writeValueAsString(List.of(
                    Map.of("sheetNamePattern", "账单.*", "sheetType", "CALL", "phoneColumn", "A")
            ));
            assertDoesNotThrow(() -> validateSheetConfigs(json));
        }
    }
}
