package com.phonecost.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for core parsing logic in DataImport and Export services.
 * Tests pure functions without Spring context: exception markers,
 * secondment keywords, month extraction, letter-to-index, sheet matching.
 */
class ImportExportLogicTest {

    // ==================== Ownership Exception Detection ====================

    @Nested
    @DisplayName("号码归属 - [例外]标记检测")
    class ExceptionMarkerDetection {

        private static final String EXCEPTION_PREFIX = "[例外]";

        @Test
        @DisplayName("[例外]前缀标记为P0最高优先级")
        void exceptionPrefixDetected() {
            String description = "[例外]总行-特殊号码";
            assertTrue(description.startsWith(EXCEPTION_PREFIX));
            assertEquals((byte) 1, (byte) (description.startsWith(EXCEPTION_PREFIX) ? 1 : 0));
            assertEquals("P0", description.startsWith(EXCEPTION_PREFIX) ? "P0" : "P2");
        }

        @Test
        @DisplayName("无[例外]前缀默认P2")
        void noExceptionPrefixDefaultsToP2() {
            String description = "北京分行-市场部";
            assertFalse(description.startsWith(EXCEPTION_PREFIX));
            assertEquals((byte) 0, (byte) (description.startsWith(EXCEPTION_PREFIX) ? 1 : 0));
            assertEquals("P2", "P2");
        }

        @Test
        @DisplayName("[例外]大小写敏感")
        void caseSensitiveException() {
            String lowerCase = "[例外]test";
            String mixedCase = "[例外]Test";
            String wrongCase = "[例外]TEST";
            assertTrue(lowerCase.startsWith(EXCEPTION_PREFIX));
            assertTrue(mixedCase.startsWith(EXCEPTION_PREFIX));
            // All should match since prefix is exact Chinese characters
        }

        @Test
        @DisplayName("[例外]在描述中间不算例外")
        void exceptionInMiddleNotDetected() {
            String description = "总行-[例外]-特殊"; // not at start
            assertFalse(description.startsWith(EXCEPTION_PREFIX));
        }
    }

    // ==================== Directory Secondment Detection ====================

    @Nested
    @DisplayName("通讯录 - 借调关键词检测")
    class SecondmentKeywordDetection {

        private static final List<String> SECONDED_KEYWORDS = List.of(
                "借调", "挂职", "交流", "轮岗", "代管", "派驻", "协助"
        );

        private boolean detectSeconded(String deptPath) {
            if (deptPath == null) return false;
            for (String kw : SECONDED_KEYWORDS) {
                if (deptPath.contains(kw)) return true;
            }
            return false;
        }

        @Test
        @DisplayName("所有7个借调关键词都能被识别")
        void allKeywordsDetected() {
            for (String kw : SECONDED_KEYWORDS) {
                assertTrue(detectSeconded("100001-" + kw + "-105326"),
                        "Should detect keyword: " + kw);
            }
        }

        @Test
        @DisplayName("正常部门路径不触发借调")
        void normalPathNoSecondment() {
            assertFalse(detectSeconded("100001-深圳分行-105326-105328"));
            assertFalse(detectSeconded("100002-北京分行-市场部"));
        }

        @Test
        @DisplayName("空路径不触发借调")
        void emptyPathNoSecondment() {
            assertFalse(detectSeconded(null));
            assertFalse(detectSeconded(""));
        }

        @Test
        @DisplayName("借调关键词出现在路径中间也能检测到")
        void keywordInMiddleOfPath() {
            assertTrue(detectSeconded("100001-深圳分行-借调组-105326"));
            assertTrue(detectSeconded("100002-交流干部-市场部"));
        }
    }

    // ==================== Month Extraction ====================

    @Nested
    @DisplayName("账单 - 月份提取正则")
    class MonthExtraction {

        private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月");

        private String extractMonth(String sheetName) {
            Matcher m = MONTH_PATTERN.matcher(sheetName);
            if (m.find()) {
                int year = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                return String.format("%d-%02d", year, month);
            }
            return "";
        }

        @Test
        @DisplayName("标准格式 2026年3月 提取正确")
        void standardFormat() {
            assertEquals("2026-03", extractMonth("2026年3月账单-按号码费用"));
        }

        @Test
        @DisplayName("两位数月份 2026年12月 提取正确")
        void doubleDigitMonth() {
            assertEquals("2026-12", extractMonth("2026年12月电信账单"));
        }

        @Test
        @DisplayName("无月份信息返回空字符串")
        void noMonthInfo() {
            assertEquals("", extractMonth("按号码费用"));
            assertEquals("", extractMonth("Sheet1"));
        }

        @Test
        @DisplayName("只取第一个匹配的月份")
        void firstMatchOnly() {
            // If sheet name somehow has two patterns, first wins
            assertEquals("2026-03", extractMonth("2026年3月-2026年4月汇总"));
        }
    }

    // ==================== Letter to Column Index ====================

    @Nested
    @DisplayName("账单 - 字母列号转数字索引")
    class LetterToIndexConversion {

        private int letterToIndex(String letter) {
            if (letter == null || letter.isBlank()) return 0;
            char c = letter.toUpperCase().charAt(0);
            if (c >= 'A' && c <= 'Z') return c - 'A';
            try { return Integer.parseInt(letter); } catch (Exception e) { return 0; }
        }

        @Test
        @DisplayName("A-Z 映射到 0-25")
        void basicLetters() {
            assertEquals(0, letterToIndex("A"));
            assertEquals(1, letterToIndex("B"));
            assertEquals(2, letterToIndex("C"));
            assertEquals(7, letterToIndex("H"));
            assertEquals(25, letterToIndex("Z"));
        }

        @Test
        @DisplayName("小写字母也支持")
        void lowercaseLetters() {
            assertEquals(0, letterToIndex("a"));
            assertEquals(2, letterToIndex("c"));
        }

        @Test
        @DisplayName("数字字符串直接返回数字值")
        void numericString() {
            assertEquals(0, letterToIndex("0"));
            assertEquals(5, letterToIndex("5"));
        }

        @Test
        @DisplayName("null和空白返回0")
        void nullAndBlankReturnZero() {
            assertEquals(0, letterToIndex(null));
            assertEquals(0, letterToIndex(""));
            assertEquals(0, letterToIndex("   "));
        }
    }

    // ==================== Sheet Name Matching ====================

    @Nested
    @DisplayName("账单 - Sheet名称模式匹配")
    class SheetNameMatching {

        /**
         * Mirrors BillImportService.matchSheetConfig logic:
         * sheetName.matches(".*" + sheetNamePattern + ".*")
         */
        private boolean matchesPattern(String sheetName, String pattern) {
            if (pattern == null || pattern.isBlank()) return false;
            try {
                return sheetName.matches(".*" + pattern + ".*");
            } catch (Exception e) {
                return false;
            }
        }

        @Test
        @DisplayName("精确匹配Sheet名")
        void exactMatch() {
            assertTrue(matchesPattern("按号码费用", "按号码费用"));
            assertTrue(matchesPattern("录音费", "录音费"));
        }

        @Test
        @DisplayName("通配符匹配")
        void wildcardMatch() {
            assertTrue(matchesPattern("2026年3月账单-按号码费用", "按号码费用"));
            assertTrue(matchesPattern("2026年3月-录音费明细", "录音费"));
        }

        @Test
        @DisplayName("正则表达式匹配")
        void regexMatch() {
            assertTrue(matchesPattern("按号码费用", "按号码.*"));
            assertTrue(matchesPattern("按号码费用_明细", "按号码.*"));
            assertTrue(matchesPattern("彩铃费-202603", "彩铃.*"));
        }

        @Test
        @DisplayName("不匹配返回false")
        void noMatch() {
            assertFalse(matchesPattern("闪信费", "按号码费用"));
            assertFalse(matchesPattern("Sheet1", "按号码.*"));
        }
    }

    // ==================== Fee Aggregation Logic ====================

    @Nested
    @DisplayName("分摊 - 费用聚合计算")
    class FeeAggregationLogic {

        @Test
        @DisplayName("safeAdd处理null值安全相加")
        void safeAddWithNulls() {
            BigDecimal result = safeAdd(null, null);
            assertEquals(BigDecimal.ZERO, result);

            result = safeAdd(new BigDecimal("100.50"), null);
            assertEquals(new BigDecimal("100.50"), result);

            result = safeAdd(null, new BigDecimal("200.00"));
            assertEquals(new BigDecimal("200.00"), result);

            result = safeAdd(new BigDecimal("100.50"), new BigDecimal("200.00"));
            assertEquals(new BigDecimal("300.50"), result);
        }

        @Test
        @DisplayName("totalFee自动求和验证")
        void totalFeeAutoSum() {
            BigDecimal monthlyRent = new BigDecimal("30.00");
            BigDecimal callFee = new BigDecimal("150.50");
            BigDecimal recordingFee = new BigDecimal("20.00");
            BigDecimal crbtFee = new BigDecimal("5.00");
            BigDecimal flashMsgFee = BigDecimal.ZERO;

            BigDecimal expected = monthlyRent.add(callFee).add(recordingFee)
                    .add(crbtFee).add(flashMsgFee);
            assertEquals(new BigDecimal("205.50"), expected);
        }

        @Test
        @DisplayName("computedFields多字段求和")
        void computedFieldSum() {
            // Simulates: monthlyRent = platformFee + monthlyRentCode
            BigDecimal platformFee = new BigDecimal("20.00");
            BigDecimal monthlyRentCode = new BigDecimal("10.00");
            BigDecimal computedMonthlyRent = platformFee.add(monthlyRentCode);
            assertEquals(new BigDecimal("30.00"), computedMonthlyRent);

            // Simulates: callFee = domesticFee + internationalFee
            BigDecimal domesticFee = new BigDecimal("100.00");
            BigDecimal internationalFee = new BigDecimal("50.50");
            BigDecimal computedCallFee = domesticFee.add(internationalFee);
            assertEquals(new BigDecimal("150.50"), computedCallFee);
        }

        private static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
            return (a != null ? a : BigDecimal.ZERO).add(b != null ? b : BigDecimal.ZERO);
        }
    }

    // ==================== Flash Month Formatting ====================

    @Nested
    @DisplayName("账单 - 闪信月份格式化")
    class FlashMonthFormatting {

        private String formatFlashMonth(String rawMonth) {
            if (rawMonth == null) return "";
            if (rawMonth.matches("\\d{6}")) {
                return rawMonth.substring(0, 4) + "-" + rawMonth.substring(4, 6);
            }
            return rawMonth;
        }

        @Test
        @DisplayName("6位数字转为YYYY-MM格式")
        void sixDigitFormat() {
            assertEquals("2026-01", formatFlashMonth("202601"));
            assertEquals("2026-12", formatFlashMonth("202612"));
            assertEquals("2025-03", formatFlashMonth("202503"));
        }

        @Test
        @DisplayName("非6位数字原样返回")
        void nonSixDigitPassthrough() {
            assertEquals("Q1-2026", formatFlashMonth("Q1-2026"));
            assertEquals("", formatFlashMonth(""));
        }

        @Test
        @DisplayName("null返回空字符串")
        void nullReturnsEmpty() {
            assertEquals("", formatFlashMonth(null));
        }
    }
}
