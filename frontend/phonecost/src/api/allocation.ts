import { apiGet, apiPost } from '../lib/request';
import { useAuthStore } from '../store/auth';
import type { BillBatch, BillDetail } from '../types/bill';
import type { AllocationResult } from '../types/allocation';

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
