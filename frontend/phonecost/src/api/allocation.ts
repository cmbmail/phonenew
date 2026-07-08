import { apiGet, apiPost } from '../lib/request';
import { useAuthStore } from '../store/auth';
import type { AllocationResult, L1SummaryRow, AllocationDetailRow, AllocationResultPage } from '../types/allocation';
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

export const getAllocationResults = (batchId: number, page = 0, size = 200, signal?: AbortSignal) =>
  apiGet<AllocationResultPage>(`/allocation/results/${batchId}?page=${page}&size=${size}`, undefined, signal);

export const confirmAllocation = (batchId: number, orgId: number) =>
  apiPost<{ org_id: number; confirm_status: number }>('/allocation/confirm', { batch_id: batchId, org_id: orgId });

export const confirmAllAllocation = (batchId: number) =>
  apiPost<{ confirmed_count: number }>('/allocation/confirm-all', { batch_id: batchId });

export const withdrawAllocation = (batchId: number, orgId: number, reason: string) =>
  apiPost<{ org_id: number; result_count: number }>('/allocation/withdraw', { batch_id: batchId, org_id: orgId, reason });

export const getL1SummaryData = (batchId: number) =>
  apiGet<L1SummaryRow[]>(`/allocation/l1-summary-data?batchId=${batchId}`);

export const getL1DetailData = (batchId: number, sheetType: string) =>
  apiGet<AllocationDetailRow[]>(`/allocation/l1-detail?batchId=${batchId}&sheetType=${sheetType}`);

export const getL2DetailData = (batchId: number, branchOrgId: number, sheetType: string) =>
  apiGet<AllocationDetailRow[]>(`/allocation/l2-detail?batchId=${batchId}&branchOrgId=${branchOrgId}&sheetType=${sheetType}`);

export const getL3DetailData = (batchId: number, subBranchOrgId: number, sheetType: string) =>
  apiGet<AllocationDetailRow[]>(`/allocation/l3-detail?batchId=${batchId}&subBranchOrgId=${subBranchOrgId}&sheetType=${sheetType}`);

// ==================== Export (secure fetch-based) ====================

/**
 * Secure file download using fetch + Blob (avoids JWT in URL query params)
 * The backend export endpoints require Authorization header;
 * we use fetch() to set the header and then trigger a browser download via Blob URL.
 */
const downloadExport = async (url: string, filename: string) => {
  const token = useAuthStore.getState().token;
  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`Export failed: ${response.status}`);
  const blob = await response.blob();
  const blobUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = blobUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(blobUrl);
};

export const exportSummary = (batchId: number, branchOrgId?: number) => {
  let url = `/api/allocation/export/summary?batchId=${batchId}`;
  if (branchOrgId) url += `&branchOrgId=${branchOrgId}`;
  const ts = new Date().toISOString().slice(0, 10);
  return downloadExport(url, `费用分摊汇总_${ts}.xlsx`);
};

export const exportDetail = (batchId: number, branchOrgId?: number) => {
  let url = `/api/allocation/export/detail?batchId=${batchId}`;
  if (branchOrgId) url += `&branchOrgId=${branchOrgId}`;
  const ts = new Date().toISOString().slice(0, 10);
  return downloadExport(url, `费用分摊明细_${ts}.xlsx`);
};

export const exportL1Summary = (batchId: number) => {
  const url = `/api/allocation/export/l1-summary?batchId=${batchId}`;
  const ts = new Date().toISOString().slice(0, 10);
  return downloadExport(url, `一级分行汇总_${ts}.xlsx`);
};

