package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AllocationServiceTest {

    @Nested
    @DisplayName("FeeAggregator")
    class FeeAggregatorTests {

        @Test
        @DisplayName("Adds fees from a single detail")
        void singleDetail() {
            var agg = new AllocationService.FeeAggregator();
            BillDetail d = BillDetail.builder()
                    .phoneNumber("0101111").sheetType("CALL")
                    .monthlyRent(BigDecimal.TEN).callFee(BigDecimal.valueOf(5))
                    .recordingFee(BigDecimal.ONE).crbtFee(BigDecimal.valueOf(2))
                    .flashMsgFee(BigDecimal.ZERO).totalFee(BigDecimal.valueOf(18))
                    .build();

            agg.add(d);

            assertEquals(0, BigDecimal.TEN.compareTo(agg.monthlyRent));
            assertEquals(0, BigDecimal.valueOf(5).compareTo(agg.callFee));
            assertEquals(0, BigDecimal.ONE.compareTo(agg.recordingFee));
            assertEquals(0, BigDecimal.valueOf(2).compareTo(agg.crbtFee));
            assertEquals(0, BigDecimal.valueOf(18).compareTo(agg.totalFee));
            assertEquals(1, agg.phoneCount);
        }

        @Test
        @DisplayName("Accumulates fees across multiple details")
        void multipleDetails() {
            var agg = new AllocationService.FeeAggregator();

            BillDetail d1 = BillDetail.builder()
                    .phoneNumber("0101111").sheetType("CALL")
                    .monthlyRent(BigDecimal.TEN).callFee(BigDecimal.valueOf(5))
                    .recordingFee(BigDecimal.ZERO).crbtFee(BigDecimal.ZERO)
                    .flashMsgFee(BigDecimal.ZERO).totalFee(BigDecimal.valueOf(15))
                    .build();

            BillDetail d2 = BillDetail.builder()
                    .phoneNumber("0101111").sheetType("RECORDING")
                    .monthlyRent(BigDecimal.ZERO).callFee(BigDecimal.ZERO)
                    .recordingFee(BigDecimal.valueOf(3)).crbtFee(BigDecimal.ZERO)
                    .flashMsgFee(BigDecimal.ZERO).totalFee(BigDecimal.valueOf(3))
                    .build();

            agg.add(d1);
            agg.add(d2);

            assertEquals(0, BigDecimal.TEN.compareTo(agg.monthlyRent));
            assertEquals(0, BigDecimal.valueOf(8).compareTo(agg.totalFee));
            // phoneCount only counts CALL sheet, and same phone deduplicates
            assertEquals(1, agg.phoneCount);
        }

        @Test
        @DisplayName("Deduplicates phone numbers across CALL sheet types")
        void phoneCountDedup() {
            var agg = new AllocationService.FeeAggregator();

            BillDetail d1 = BillDetail.builder()
                    .phoneNumber("0101111").sheetType("CALL")
                    .totalFee(BigDecimal.ONE).build();
            BillDetail d2 = BillDetail.builder()
                    .phoneNumber("0101111").sheetType("CALL")
                    .totalFee(BigDecimal.ONE).build();

            agg.add(d1);
            agg.add(d2);

            // Same phone number should only count once
            assertEquals(1, agg.phoneCount);
        }

        @Test
        @DisplayName("Counts different phone numbers separately")
        void differentPhonesCounted() {
            var agg = new AllocationService.FeeAggregator();

            BillDetail d1 = BillDetail.builder()
                    .phoneNumber("0101111").sheetType("CALL")
                    .totalFee(BigDecimal.ONE).build();
            BillDetail d2 = BillDetail.builder()
                    .phoneNumber("0102222").sheetType("CALL")
                    .totalFee(BigDecimal.ONE).build();

            agg.add(d1);
            agg.add(d2);

            assertEquals(2, agg.phoneCount);
        }

        @Test
        @DisplayName("Non-CALL sheet types do not count toward phoneCount")
        void nonCallNotCounted() {
            var agg = new AllocationService.FeeAggregator();

            BillDetail d = BillDetail.builder()
                    .phoneNumber("0101111").sheetType("RECORDING")
                    .totalFee(BigDecimal.ONE).build();

            agg.add(d);

            assertEquals(0, agg.phoneCount);
        }

        @Test
        @DisplayName("Null fee fields treated as ZERO")
        void nullFees() {
            var agg = new AllocationService.FeeAggregator();

            BillDetail d = BillDetail.builder()
                    .phoneNumber("0101111").sheetType("CALL")
                    .monthlyRent(null).callFee(null).totalFee(null)
                    .build();

            agg.add(d);

            assertEquals(0, BigDecimal.ZERO.compareTo(agg.monthlyRent));
            assertEquals(0, BigDecimal.ZERO.compareTo(agg.callFee));
            assertEquals(0, BigDecimal.ZERO.compareTo(agg.totalFee));
        }
    }
}
