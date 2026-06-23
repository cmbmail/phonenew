package com.phonecost.service;

import com.phonecost.domain.*;
import com.phonecost.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllocationConfirmServiceTest {

    @InjectMocks
    private AllocationConfirmService confirmService;

    @Mock private AllocationResultRepository resultRepository;
    @Mock private AllocationAdjustmentRepository adjustmentRepository;
    @Mock private SysOrganizationRepository orgRepository;
    @Mock private AuditLogService auditLogService;

    private static final Long BATCH_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("confirm")
    class ConfirmTests {

        @Test
        @DisplayName("Confirm pending result successfully")
        void confirmPending() {
            AllocationResult result = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(ORG_ID)
                    .confirmStatus((byte) 0).version(0).build();
            result.setId(1L);

            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, ORG_ID))
                    .thenReturn(Optional.of(result));
            when(resultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AllocationResult confirmed = confirmService.confirm(BATCH_ID, ORG_ID, USER_ID);

            assertEquals((byte) 1, confirmed.getConfirmStatus());
            assertEquals(USER_ID, confirmed.getConfirmedBy());
            assertNotNull(confirmed.getConfirmedAt());
            verify(auditLogService).log(eq(USER_ID), eq("user"), eq("ALLOCATION_CONFIRM"),
                    eq("allocation_result"), eq(1L), anyString());
        }

        @Test
        @DisplayName("Confirm already confirmed should throw")
        void confirmAlreadyConfirmed() {
            AllocationResult result = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(ORG_ID).confirmStatus((byte) 1).build();
            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, ORG_ID))
                    .thenReturn(Optional.of(result));

            assertThrows(IllegalArgumentException.class,
                    () -> confirmService.confirm(BATCH_ID, ORG_ID, USER_ID));
        }

        @Test
        @DisplayName("Confirm withdrawn result should throw")
        void confirmWithdrawn() {
            AllocationResult result = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(ORG_ID).confirmStatus((byte) 2).build();
            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, ORG_ID))
                    .thenReturn(Optional.of(result));

            assertThrows(IllegalArgumentException.class,
                    () -> confirmService.confirm(BATCH_ID, ORG_ID, USER_ID));
        }

        @Test
        @DisplayName("Confirm non-existent result should throw")
        void confirmNotFound() {
            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, ORG_ID))
                    .thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> confirmService.confirm(BATCH_ID, ORG_ID, USER_ID));
        }
    }

    @Nested
    @DisplayName("withdraw")
    class WithdrawTests {

        @Test
        @DisplayName("Withdraw confirmed result successfully")
        void withdrawConfirmed() {
            AllocationResult result = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(ORG_ID)
                    .confirmStatus((byte) 1).version(0).build();
            result.setId(1L);
            SysOrganization org = SysOrganization.builder()
                    .name("北京分行").path("/5/10/").parentId(5L).build();
            org.setId(ORG_ID);

            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, ORG_ID))
                    .thenReturn(Optional.of(result));
            when(orgRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
            when(resultRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                    .thenReturn(List.of(result));
            when(resultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<AllocationResult> results = confirmService.withdraw(BATCH_ID, ORG_ID, USER_ID, "数据修正");

            assertEquals((byte) 2, result.getConfirmStatus());
            assertEquals("数据修正", result.getWithdrawReason());
            assertEquals(USER_ID, result.getWithdrawnBy());
        }

        @Test
        @DisplayName("Withdraw with empty reason should throw")
        void withdrawNoReason() {
            assertThrows(IllegalArgumentException.class,
                    () -> confirmService.withdraw(BATCH_ID, ORG_ID, USER_ID, ""));
            assertThrows(IllegalArgumentException.class,
                    () -> confirmService.withdraw(BATCH_ID, ORG_ID, USER_ID, null));
        }

        @Test
        @DisplayName("Withdraw non-confirmed result should throw")
        void withdrawPending() {
            AllocationResult result = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(ORG_ID).confirmStatus((byte) 0).build();
            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, ORG_ID))
                    .thenReturn(Optional.of(result));

            assertThrows(IllegalArgumentException.class,
                    () -> confirmService.withdraw(BATCH_ID, ORG_ID, USER_ID, "原因"));
        }

        @Test
        @DisplayName("Withdraw cascades to descendant orgs")
        void withdrawCascades() {
            AllocationResult parentResult = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(ORG_ID)
                    .confirmStatus((byte) 1).version(0).build();
            parentResult.setId(1L);
            AllocationResult childResult = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(11L)
                    .confirmStatus((byte) 1).version(0).build();
            childResult.setId(2L);

            SysOrganization parentOrg = SysOrganization.builder()
                    .name("北京分行").path("/5/10/").parentId(5L).build();
            parentOrg.setId(ORG_ID);
            SysOrganization childOrg = SysOrganization.builder()
                    .name("部门A").path("/5/10/11/").parentId(ORG_ID).build();
            childOrg.setId(11L);

            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, ORG_ID))
                    .thenReturn(Optional.of(parentResult));
            when(orgRepository.findById(ORG_ID)).thenReturn(Optional.of(parentOrg));
            when(resultRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                    .thenReturn(List.of(parentResult, childResult));
            when(orgRepository.findById(11L)).thenReturn(Optional.of(childOrg));
            when(resultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            confirmService.withdraw(BATCH_ID, ORG_ID, USER_ID, "上级撤回");

            assertEquals((byte) 2, parentResult.getConfirmStatus());
            assertEquals((byte) 2, childResult.getConfirmStatus());
            assertTrue(childResult.getWithdrawReason().startsWith("上级撤回:"));
        }

        @Test
        @DisplayName("Withdraw does NOT cascade to non-descendant orgs")
        void withdrawNoCascadeToUnrelated() {
            AllocationResult target = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(ORG_ID)
                    .confirmStatus((byte) 1).version(0).build();
            target.setId(1L);
            AllocationResult other = AllocationResult.builder()
                    .batchId(BATCH_ID).orgId(20L)
                    .confirmStatus((byte) 1).version(0).build();
            other.setId(2L);

            SysOrganization targetOrg = SysOrganization.builder()
                    .name("北京分行").path("/5/10/").parentId(5L).build();
            targetOrg.setId(ORG_ID);
            SysOrganization otherOrg = SysOrganization.builder()
                    .name("上海分行").path("/5/20/").parentId(5L).build();
            otherOrg.setId(20L);

            when(resultRepository.findByBatchIdAndOrgIdAndDeletedAtIsNull(BATCH_ID, ORG_ID))
                    .thenReturn(Optional.of(target));
            when(orgRepository.findById(ORG_ID)).thenReturn(Optional.of(targetOrg));
            when(resultRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                    .thenReturn(List.of(target, other));
            when(orgRepository.findById(20L)).thenReturn(Optional.of(otherOrg));
            when(resultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            confirmService.withdraw(BATCH_ID, ORG_ID, USER_ID, "部分撤回");

            assertEquals((byte) 2, target.getConfirmStatus());
            assertEquals((byte) 1, other.getConfirmStatus()); // Unchanged
        }
    }

    @Nested
    @DisplayName("confirmAll")
    class ConfirmAllTests {

        @Test
        @DisplayName("Confirms all pending results in allScope")
        void confirmAllAdmin() {
            AllocationResult r1 = AllocationResult.builder().batchId(BATCH_ID).orgId(10L)
                    .confirmStatus((byte) 0).version(0).build();
            r1.setId(1L);
            AllocationResult r2 = AllocationResult.builder().batchId(BATCH_ID).orgId(20L)
                    .confirmStatus((byte) 0).version(0).build();
            r2.setId(2L);

            when(resultRepository.findByBatchIdAndConfirmStatusAndDeletedAtIsNull(BATCH_ID, (byte) 0))
                    .thenReturn(List.of(r1, r2));

            DataScope allScope = DataScope.allScope();
            int count = confirmService.confirmAllInScope(BATCH_ID, USER_ID, allScope);

            assertEquals(2, count);
            assertEquals((byte) 1, r1.getConfirmStatus());
            assertEquals((byte) 1, r2.getConfirmStatus());
        }

        @Test
        @DisplayName("Branch admin only confirms visible orgs")
        void branchScopedConfirm() {
            AllocationResult r1 = AllocationResult.builder().batchId(BATCH_ID).orgId(10L)
                    .confirmStatus((byte) 0).version(0).build();
            r1.setId(1L);
            AllocationResult r2 = AllocationResult.builder().batchId(BATCH_ID).orgId(20L)
                    .confirmStatus((byte) 0).version(0).build();
            r2.setId(2L);

            when(resultRepository.findByBatchIdAndConfirmStatusAndDeletedAtIsNull(BATCH_ID, (byte) 0))
                    .thenReturn(List.of(r1, r2));

            DataScope branchScope = DataScope.subtreeScope("/5/10/", List.of(10L));
            int count = confirmService.confirmAllInScope(BATCH_ID, USER_ID, branchScope);

            assertEquals(1, count);
            assertEquals((byte) 1, r1.getConfirmStatus());
            assertEquals((byte) 0, r2.getConfirmStatus());
        }
    }
}
