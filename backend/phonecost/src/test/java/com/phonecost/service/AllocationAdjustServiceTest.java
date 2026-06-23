package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllocationAdjustServiceTest {

    @InjectMocks
    private AllocationAdjustService adjustService;

    @Mock private BillDetailRepository billDetailRepository;
    @Mock private AllocationResultRepository resultRepository;
    @Mock private AllocationAdjustmentRepository adjustmentRepository;
    @Mock private SysOrganizationRepository orgRepository;
    @Mock private AuditLogService auditLogService;

    private static final Long BATCH_ID = 1L;
    private static final Long FROM_ORG_ID = 10L;
    private static final Long TO_ORG_ID = 20L;
    private static final String PHONE = "01088881234";
    private static final Long USER_ID = 1L;

    private SysOrganization fromOrg;
    private SysOrganization toOrg;
    private BillDetail detail;
    private AllocationResult fromResult;
    private AllocationResult toResult;

    @BeforeEach
    void setUp() {
        fromOrg = SysOrganization.builder()
                .id(FROM_ORG_ID).name("北京分行").parentId(5L)
                .path("/5/10/").build();
        toOrg = SysOrganization.builder()
                .id(TO_ORG_ID).name("上海分行").parentId(5L)
                .path("/5/20/").build();

        detail = BillDetail.builder()
                .id(1L).batchId(BATCH_ID).phoneNumber(PHONE)
                .sheetType("CALL").monthlyRent(BigDecimal.TEN)
                .callFee(BigDecimal.valueOf(5)).recordingFee(BigDecimal.ZERO)
                .crbtFee(BigDecimal.ZERO).flashMsgFee(BigDecimal.ZERO)
                .totalFee(BigDecimal.valueOf(15)).orgId(FROM_ORG_ID)
                .build();

        fromResult = AllocationResult.builder()
                .id(1L).batchId(BATCH_ID).orgId(FROM_ORG_ID).orgName("北京分行")
                .monthlyRent(BigDecimal.valueOf(100)).callFee(BigDecimal.valueOf(50))
                .recordingFee(BigDecimal.ZERO).crbtFee(BigDecimal.ZERO)
                .flashMsgFee(BigDecimal.ZERO).totalFee(BigDecimal.valueOf(150))
                .phoneCount(10).confirmStatus((byte) 0).version(0)
                .build();

        toResult = AllocationResult.builder()
                .id(2L).batchId(BATCH_ID).orgId(TO_ORG_ID).orgName("上海分行")
                .monthlyRent(BigDecimal.valueOf(200)).callFee(BigDecimal.valueOf(80))
                .recordingFee(BigDecimal.ZERO).crbtFee(BigDecimal.ZERO)
                .flashMsgFee(BigDecimal.ZERO).totalFee(BigDecimal.valueOf(280))
                .phoneCount(15).confirmStatus((byte) 0).version(0)
                .build();
    }

    @Nested
    @DisplayName("Parameter validation")
    class ValidationTests {

        @Test
        @DisplayName("Empty phone number should throw")
        void emptyPhone() {
            assertThrows(IllegalArgumentException.class,
                    () -> adjustService.adjust(BATCH_ID, "", FROM_ORG_ID, TO_ORG_ID, "reason", USER_ID));
        }

        @Test
        @DisplayName("Null org IDs should throw")
        void nullOrgIds() {
            assertThrows(IllegalArgumentException.class,
                    () -> adjustService.adjust(BATCH_ID, PHONE, null, TO_ORG_ID, "reason", USER_ID));
            assertThrows(IllegalArgumentException.class,
                    () -> adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, null, "reason", USER_ID));
        }

        @Test
        @DisplayName("Same org for from/to should throw")
        void sameOrg() {
            assertThrows(IllegalArgumentException.class,
                    () -> adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, FROM_ORG_ID, "reason", USER_ID));
        }

        @Test
        @DisplayName("Empty reason should throw")
        void emptyReason() {
            assertThrows(IllegalArgumentException.class,
                    () -> adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "", USER_ID));
            assertThrows(IllegalArgumentException.class,
                    () -> adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, null, USER_ID));
        }

        @Test
        @DisplayName("Phone not found in batch should throw")
        void phoneNotFound() {
            when(billDetailRepository.findByPhoneNumberAndBatchIdAndDeletedAtIsNull(PHONE, BATCH_ID))
                    .thenReturn(List.of());
            assertThrows(IllegalArgumentException.class,
                    () -> adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "测试调整", USER_ID));
        }

        @Test
        @DisplayName("Phone belongs to different org should throw")
        void wrongOrgOwnership() {
            BillDetail wrongOrgDetail = BillDetail.builder()
                    .id(1L).phoneNumber(PHONE).orgId(99L).totalFee(BigDecimal.TEN)
                    .sheetType("CALL").build();
            when(billDetailRepository.findByPhoneNumberAndBatchIdAndDeletedAtIsNull(PHONE, BATCH_ID))
                    .thenReturn(List.of(wrongOrgDetail));
            assertThrows(IllegalArgumentException.class,
                    () -> adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "测试调整", USER_ID));
        }
    }

    @Nested
    @DisplayName("Successful adjustment")
    class SuccessfulAdjustTests {

        @BeforeEach
        void setUpMocks() {
            when(billDetailRepository.findByPhoneNumberAndBatchIdAndDeletedAtIsNull(PHONE, BATCH_ID))
                    .thenReturn(List.of(detail));
            when(orgRepository.findById(FROM_ORG_ID)).thenReturn(Optional.of(fromOrg));
            when(orgRepository.findById(TO_ORG_ID)).thenReturn(Optional.of(toOrg));
            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, FROM_ORG_ID))
                    .thenReturn(Optional.of(fromResult));
            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, TO_ORG_ID))
                    .thenReturn(Optional.of(toResult));
            // Parent cascade: root org
            SysOrganization rootOrg = SysOrganization.builder().id(5L).name("招商银行").parentId(null).path("/5/").build();
            when(orgRepository.findById(5L)).thenReturn(Optional.of(rootOrg));
            when(adjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("Adjust moves fee from source to target org")
        void feeMovement() {
            BigDecimal fromTotalBefore = fromResult.getTotalFee();
            BigDecimal toTotalBefore = toResult.getTotalFee();

            adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "号码调拨", USER_ID);

            // From org should have subtracted the detail's total fee
            assertEquals(fromTotalBefore.subtract(detail.getTotalFee()), fromResult.getTotalFee());
            // To org should have added the detail's total fee
            assertEquals(toTotalBefore.add(detail.getTotalFee()), toResult.getTotalFee());
        }

        @Test
        @DisplayName("Adjust updates bill_detail orgId and ownership source")
        void detailOrgUpdate() {
            adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "号码调拨", USER_ID);

            assertEquals(TO_ORG_ID, detail.getOrgId());
            assertEquals("ADJUSTED", detail.getOwnershipSource());
            verify(billDetailRepository).saveAll(List.of(detail));
        }

        @Test
        @DisplayName("Adjust decrements phone count from source, increments target")
        void phoneCountUpdate() {
            int fromBefore = fromResult.getPhoneCount();
            int toBefore = toResult.getPhoneCount();

            adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "号码调拨", USER_ID);

            assertEquals(fromBefore - 1, fromResult.getPhoneCount());
            assertEquals(toBefore + 1, toResult.getPhoneCount());
        }

        @Test
        @DisplayName("Adjust creates adjustment record")
        void adjustmentRecord() {
            AllocationAdjustment adj = adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "号码调拨", USER_ID);

            assertNotNull(adj);
            assertEquals(PHONE, adj.getPhoneNumber());
            assertEquals(FROM_ORG_ID, adj.getFromOrgId());
            assertEquals(TO_ORG_ID, adj.getToOrgId());
            assertEquals("号码调拨", adj.getReason());
            assertEquals(detail.getTotalFee(), adj.getAmount());
            assertEquals(USER_ID, adj.getAdjustedBy());
        }

        @Test
        @DisplayName("Adjust audits the operation")
        void auditLog() {
            adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "号码调拨", USER_ID);
            verify(auditLogService).log(eq(USER_ID), eq("user"), eq("ALLOCATION_ADJUST"),
                    eq("allocation_adjustment"), any(), anyString());
        }
    }

    @Nested
    @DisplayName("Adjust to org with no existing result")
    class NewOrgAdjustTests {

        @BeforeEach
        void setUpMocks() {
            when(billDetailRepository.findByPhoneNumberAndBatchIdAndDeletedAtIsNull(PHONE, BATCH_ID))
                    .thenReturn(List.of(detail));
            when(orgRepository.findById(FROM_ORG_ID)).thenReturn(Optional.of(fromOrg));
            when(orgRepository.findById(TO_ORG_ID)).thenReturn(Optional.of(toOrg));
            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, FROM_ORG_ID))
                    .thenReturn(Optional.of(fromResult));
            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, TO_ORG_ID))
                    .thenReturn(Optional.empty());
            SysOrganization rootOrg = SysOrganization.builder().id(5L).name("招商银行").parentId(null).path("/5/").build();
            when(orgRepository.findById(5L)).thenReturn(Optional.of(rootOrg));
            when(adjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("Creates new AllocationResult for target org when none exists")
        void createsNewResult() {
            // Save is called twice: for fromResult and the new toResult
            when(resultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AllocationAdjustment adj = adjustService.adjust(BATCH_ID, PHONE, FROM_ORG_ID, TO_ORG_ID, "号码调拨", USER_ID);

            assertNotNull(adj);
            // resultRepository.save should have been called for fromResult and new toResult
            verify(resultRepository, atLeast(2)).save(any(AllocationResult.class));
        }
    }

    @Nested
    @DisplayName("listAdjustments")
    class ListAdjustmentsTests {

        @Test
        @DisplayName("Returns adjustments for a batch")
        void listByBatch() {
            AllocationAdjustment adj1 = AllocationAdjustment.builder()
                    .id(1L).batchId(BATCH_ID).phoneNumber("0101111").build();
            when(adjustmentRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                    .thenReturn(List.of(adj1));

            List<AllocationAdjustment> result = adjustService.listAdjustments(BATCH_ID);
            assertEquals(1, result.size());
            assertEquals("0101111", result.get(0).getPhoneNumber());
        }

        @Test
        @DisplayName("Returns empty list when no adjustments")
        void emptyList() {
            when(adjustmentRepository.findByBatchIdAndDeletedAtIsNull(999L))
                    .thenReturn(List.of());
            List<AllocationAdjustment> result = adjustService.listAdjustments(999L);
            assertTrue(result.isEmpty());
        }
    }
}
