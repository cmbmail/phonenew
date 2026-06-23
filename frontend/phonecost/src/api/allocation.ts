import { apiGet, apiPost } from '../lib/request';
import { useAuthStore } from '../store/auth';
import type { BillBatch, BillDetail } from '../types/bill';
import type { AllocationResult, AllocationAdjustment } from '../types/allocation';

// ==================== Bill ====================

export const getBillBatches = () =>
  apiGet<BillBatch[]>('/import/bill/batches');

export const getBillDetails = (batchId: number) =>
  apiGet<BillDetail[]>(`/import/bill/details/${batchId}`);

// ==================== Allocation ====================

export const calculateAllocation = (billBatchId: number) =>
  apiPost<{ bill_batch_id: number; org_count: number }>('/allocation/calculate', { bill_batch_id: billBatchId });

export const getAllocationResults = (batchId: number) =>
  apiGet<AllocationResult[]>(`/allocation/results/${batchId}`);

export const confirmAllocation = (batchId: number, orgId: number) =>
  apiPost<{ org_id: number; confirm_status: number }>('/allocation/confirm', { batch_id: batchId, org_id: orgId });

export const confirmAllAllocation = (batchId: number) =>
  apiPost<{ confirmed_count: number }>('/allocation/confirm-all', { batch_id: batchId });

export const withdrawAllocation = (batchId: number, orgId: number, reason: string) =>
  apiPost<{ org_id: number; result_count: number }>('/allocation/withdraw', { batch_id: batchId, org_id: orgId, reason });

export const adjustAllocation = (batchId: number, phoneNumber: string, fromOrgId: number, toOrgId: number, reason: string) =>
  apiPost<AllocationAdjustment>('/allocation/adjust', {
    batch_id: batchId,
    phone_number: phoneNumber,
    from_org_id: fromOrgId,
    to_org_id: toOrgId,
    reason,
  });

export const getAdjustments = (batchId: number) =>
  apiGet<AllocationAdjustment[]>(`/allocation/adjustments/${batchId}`);

// ==================== Export URLs ====================

/**
 * Build export URL with JWT token as query parameter
 * The backend export endpoints now require authentication,
 * so we pass the token as a query param since window.open() can't set headers.
 */
export const getExportSummaryUrl = (batchId: number, branchOrgId?: number) => {
  const token = useAuthStore.getState().token;
  let url = `/api/allocation/export/summary?batchId=${batchId}&token=${token}`;
  if (branchOrgId) url += `&branchOrgId=${branchOrgId}`;
  return url;
};

export const getExportDetailUrl = (batchId: number, branchOrgId?: number) => {
  const token = useAuthStore.getState().token;
  let url = `/api/allocation/export/detail?batchId=${batchId}&token=${token}`;
  if (branchOrgId) url += `&branchOrgId=${branchOrgId}`;
  return url;
};

export const getBranchBillUrl = (batchId: number, branchOrgId?: number) => {
  const token = useAuthStore.getState().token;
  let url = `/api/allocation/export/branch-bill?batchId=${batchId}&token=${token}`;
  if (branchOrgId) url += `&branchOrgId=${branchOrgId}`;
  return url;
};

export const getL1SummaryUrl = (batchId: number) => {
  const token = useAuthStore.getState().token;
  return `/api/allocation/export/l1-summary?batchId=${batchId}&token=${token}`;
};

export const getL2BranchDetailUrl = (batchId: number, branchOrgId: number) => {
  const token = useAuthStore.getState().token;
  return `/api/allocation/export/l2-branch-detail?batchId=${batchId}&branchOrgId=${branchOrgId}&token=${token}`;
};

export const getL3SubBranchDetailUrl = (batchId: number, subBranchOrgId: number) => {
  const token = useAuthStore.getState().token;
  return `/api/allocation/export/l3-sub-branch-detail?batchId=${batchId}&subBranchOrgId=${subBranchOrgId}&token=${token}`;
};

export const getCostCenterMappingUrl = (batchId: number, branchOrgId?: number) => {
  const token = useAuthStore.getState().token;
  let url = `/api/allocation/export/cost-center-mapping?batchId=${batchId}&token=${token}`;
  if (branchOrgId) url += `&branchOrgId=${branchOrgId}`;
  return url;
};
