import { apiGet, apiPost } from '../lib/request';
import { useAuthStore } from '../store/auth';
import type { AllocationResult, L1SummaryRow } from '../types/allocation';
import type { OwnershipBatch, DirectoryBatch } from '../types/import';

// ==================== Snapshot ====================

export interface AllocationSnapshot {
  ownership_batch_id: number | null;
  directory_batch_id: number | null;
  matched_count: number | null;
  ownership_batches: OwnershipBatch[];
  directory_batches: DirectoryBatch[];
}

export const getAllocationSnapshot = (batchId: number) =>
  apiGet<AllocationSnapshot>(`/allocation/snapshot/${batchId}`);

// ==================== Allocation ====================

export const calculateAllocation = (billBatchId: number, ownershipBatchId?: number | null, directoryBatchId?: number | null) =>
  apiPost<{ bill_batch_id: number; org_count: number; matched_count: number; ownership_batch_id: number | null; directory_batch_id: number | null }>('/allocation/calculate', {
    bill_batch_id: billBatchId,
    ...(ownershipBatchId != null ? { ownership_batch_id: ownershipBatchId } : {}),
    ...(directoryBatchId != null ? { directory_batch_id: directoryBatchId } : {}),
  });

export const getAllocationResults = (batchId: number) =>
  apiGet<AllocationResult[]>(`/allocation/results/${batchId}`);

export const confirmAllocation = (batchId: number, orgId: number) =>
  apiPost<{ org_id: number; confirm_status: number }>('/allocation/confirm', { batch_id: batchId, org_id: orgId });

export const confirmAllAllocation = (batchId: number) =>
  apiPost<{ confirmed_count: number }>('/allocation/confirm-all', { batch_id: batchId });

export const withdrawAllocation = (batchId: number, orgId: number, reason: string) =>
  apiPost<{ org_id: number; result_count: number }>('/allocation/withdraw', { batch_id: batchId, org_id: orgId, reason });

export const getL1SummaryData = (batchId: number) =>
  apiGet<L1SummaryRow[]>(`/allocation/l1-summary-data?batchId=${batchId}`);

export const getL1DetailData = (batchId: number, sheetType: string) =>
  apiGet<Record<string, unknown>[]>(`/allocation/l1-detail?batchId=${batchId}&sheetType=${sheetType}`);

export const getL2DetailData = (batchId: number, branchOrgId: number, sheetType: string) =>
  apiGet<Record<string, unknown>[]>(`/allocation/l2-detail?batchId=${batchId}&branchOrgId=${branchOrgId}&sheetType=${sheetType}`);

export const getL3DetailData = (batchId: number, subBranchOrgId: number, sheetType: string) =>
  apiGet<Record<string, unknown>[]>(`/allocation/l3-detail?batchId=${batchId}&subBranchOrgId=${subBranchOrgId}&sheetType=${sheetType}`);

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

export const getL1SummaryUrl = (batchId: number) => {
  const token = useAuthStore.getState().token;
  return `/api/allocation/export/l1-summary?batchId=${batchId}&token=${token}`;
};

