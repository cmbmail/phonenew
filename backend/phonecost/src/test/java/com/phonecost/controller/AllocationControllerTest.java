package com.phonecost.controller;

import com.phonecost.config.SecurityConfig;
import com.phonecost.domain.*;
import com.phonecost.repository.*;
import com.phonecost.security.JwtAuthenticationEntryPoint;
import com.phonecost.security.JwtAuthenticationFilter;
import com.phonecost.service.*;
import com.phonecost.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AllocationController.class)
@Import(SecurityConfig.class)
class AllocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private AllocationService allocationService;
    @MockBean private AllocationConfirmService confirmService;
    @MockBean private AllocationExportService exportService;
    @MockBean private AllocationAdjustService adjustService;
    @MockBean private AllocationResultRepository resultRepository;
    @MockBean private BillBatchRepository billBatchRepository;
    @MockBean private DataScopeService dataScopeService;
    @MockBean private SysUserRepository userRepository;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockBean private PasswordEncoder passwordEncoder;

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String BRANCH_TOKEN = "branch-token";
    private static final String FINANCE_TOKEN = "finance-token";

    @BeforeEach
    void setUpJwtFilter() throws Exception {
        // Make the JWT filter pass through for authenticated tests
        // We'll use jwt() post-processor from spring-security-test directly
    }

    private void mockAdminScope() {
        when(dataScopeService.getDataScope(anyLong())).thenReturn(DataScope.allScope());
    }

    @Nested
    @DisplayName("POST /allocation/calculate")
    class CalculateTests {

        @Test
        @DisplayName("Admin can calculate allocation")
        void adminCanCalculate() throws Exception {
            when(allocationService.calculateAllocation(1L)).thenReturn(List.of());

            mockMvc.perform(post("/allocation/calculate")
                    .with(jwt().authorities(() -> "ROLE_ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"bill_batch_id\":1}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Non-admin cannot calculate")
        void branchCannotCalculate() throws Exception {
            mockMvc.perform(post("/allocation/calculate")
                    .with(jwt().authorities(() -> "ROLE_BRANCH"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"bill_batch_id\":1}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /allocation/adjust")
    class AdjustTests {

        @Test
        @DisplayName("Admin can adjust allocation")
        void adminCanAdjust() throws Exception {
            AllocationAdjustment adj = AllocationAdjustment.builder()
                    .id(1L).batchId(1L).phoneNumber("0101111")
                    .fromOrgId(10L).toOrgId(20L).amount(BigDecimal.TEN)
                    .reason("test").build();
            when(adjustService.adjust(anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong()))
                    .thenReturn(adj);

            mockMvc.perform(post("/allocation/adjust")
                    .with(jwt().authorities(() -> "ROLE_ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"batch_id\":1,\"phone_number\":\"0101111\",\"from_org_id\":10,\"to_org_id\":20,\"reason\":\"test\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("Finance can adjust allocation")
        void financeCanAdjust() throws Exception {
            AllocationAdjustment adj = AllocationAdjustment.builder()
                    .id(1L).batchId(1L).phoneNumber("0101111")
                    .fromOrgId(10L).toOrgId(20L).amount(BigDecimal.TEN)
                    .reason("test").build();
            when(adjustService.adjust(anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyLong()))
                    .thenReturn(adj);

            mockMvc.perform(post("/allocation/adjust")
                    .with(jwt().authorities(() -> "ROLE_FINANCE"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"batch_id\":1,\"phone_number\":\"0101111\",\"from_org_id\":10,\"to_org_id\":20,\"reason\":\"test\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Branch cannot adjust allocation")
        void branchCannotAdjust() throws Exception {
            mockMvc.perform(post("/allocation/adjust")
                    .with(jwt().authorities(() -> "ROLE_BRANCH"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"batch_id\":1,\"phone_number\":\"0101111\",\"from_org_id\":10,\"to_org_id\":20,\"reason\":\"test\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Missing required fields returns 400")
        void missingFields() throws Exception {
            mockMvc.perform(post("/allocation/adjust")
                    .with(jwt().authorities(() -> "ROLE_ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"batch_id\":1}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /allocation/adjustments/{batchId}")
    class ListAdjustmentsTests {

        @Test
        @DisplayName("Admin can list adjustments")
        void adminCanList() throws Exception {
            when(adjustService.listAdjustments(1L)).thenReturn(List.of());

            mockMvc.perform(get("/allocation/adjustments/1")
                    .with(jwt().authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("Branch can list adjustments")
        void branchCanList() throws Exception {
            when(adjustService.listAdjustments(1L)).thenReturn(List.of());

            mockMvc.perform(get("/allocation/adjustments/1")
                    .with(jwt().authorities(() -> "ROLE_BRANCH")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Department cannot list adjustments")
        void deptCannotList() throws Exception {
            mockMvc.perform(get("/allocation/adjustments/1")
                    .with(jwt().authorities(() -> "ROLE_DEPARTMENT")))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /allocation/confirm")
    class ConfirmTests {

        @Test
        @DisplayName("Admin can confirm")
        void adminCanConfirm() throws Exception {
            AllocationResult result = AllocationResult.builder()
                    .id(1L).orgId(10L).confirmStatus((byte) 1).build();
            when(dataScopeService.getDataScope(anyLong())).thenReturn(DataScope.allScope());
            when(confirmService.confirm(anyLong(), anyLong(), anyLong())).thenReturn(result);

            mockMvc.perform(post("/allocation/confirm")
                    .with(jwt().authorities(() -> "ROLE_ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"batch_id\":1,\"org_id\":10}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Branch can confirm own scope org")
        void branchConfirmOwnScope() throws Exception {
            DataScope branchScope = DataScope.subtreeScope("/5/10/", List.of(10L, 11L, 12L));
            when(dataScopeService.getDataScope(anyLong())).thenReturn(branchScope);
            AllocationResult result = AllocationResult.builder()
                    .id(1L).orgId(10L).confirmStatus((byte) 1).build();
            when(confirmService.confirm(1L, 10L, anyLong())).thenReturn(result);

            mockMvc.perform(post("/allocation/confirm")
                    .with(jwt().authorities(() -> "ROLE_BRANCH"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"batch_id\":1,\"org_id\":10}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Branch cannot confirm out-of-scope org")
        void branchConfirmOutOfScope() throws Exception {
            DataScope branchScope = DataScope.subtreeScope("/5/10/", List.of(10L, 11L));
            when(dataScopeService.getDataScope(anyLong())).thenReturn(branchScope);

            mockMvc.perform(post("/allocation/confirm")
                    .with(jwt().authorities(() -> "ROLE_BRANCH"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"batch_id\":1,\"org_id\":99}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /allocation/results/{batchId}")
    class ResultsTests {

        @Test
        @DisplayName("Authenticated user can get results")
        void canGetResults() throws Exception {
            when(dataScopeService.getDataScope(anyLong())).thenReturn(DataScope.allScope());
            when(resultRepository.findByBatchIdAndDeletedAtIsNull(1L)).thenReturn(List.of());

            mockMvc.perform(get("/allocation/results/1")
                    .with(jwt().authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Unauthenticated user gets 401/403")
        void unauthenticated() throws Exception {
            mockMvc.perform(get("/allocation/results/1"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
