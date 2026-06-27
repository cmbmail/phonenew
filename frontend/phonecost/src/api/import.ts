import { apiPost, apiGet, apiPut, apiUpload } from '../lib/request';
import type { ImportResult, MatchResult, OwnershipBatch, DirectoryBatch, DirectoryEntry, DataSnapshot } from '../types/import';
import type { BillBatch } from '../types/bill';

// ==================== Ownership ====================

export const importOwnership = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<ImportResult>('/import/ownership', formData);
};

export const getOwnershipBatches = () =>
  apiGet<OwnershipBatch[]>('/import/ownership/batches');

// ==================== Directory ====================

export const importDirectory = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<ImportResult>('/import/directory', formData);
};

export const getDirectoryBatches = () =>
  apiGet<DirectoryBatch[]>('/import/directory/batches');

export const setDirectoryMonth = (batchId: number, billingMonth: string) =>
  apiPut<DirectoryBatch>(`/import/directory/batches/${batchId}/month`, { billing_month: billingMonth });

export const getDirectorySnapshots = () =>
  apiGet<DirectoryBatch[]>('/import/directory/snapshots');

export const clearDirectoryException = (id: number) =>
  apiPut<DirectoryEntry>(`/import/directory/entries/${id}/clear-exception`);

export const syncDirectoryFromMatch = (id: number) =>
  apiPut<DirectoryEntry>(`/import/directory/entries/${id}/sync-from-match`);

export const batchClearDirectoryException = (ids: number[]) =>
  apiPut<{ cleared: number }>('/import/directory/entries/batch-clear-exception', { ids });

export const updateDirectoryExceptionReason = (id: number, reason: string) =>
  apiPut<DirectoryEntry>(`/import/directory/entries/${id}/reason`, { reason });

// ==================== Bill ====================

export const importBill = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<ImportResult>('/import/bill', formData);
};

export const getBillBatches = () =>
  apiGet<BillBatch[]>('/import/bill/batches');

export const getActiveImportTemplate = () =>
  apiGet<{ id: number; name: string; operator: string }>('/templates/active');

// ==================== Match ====================

export const matchOwnership = (params: {
  bill_batch_id: number;
  ownership_batch_id?: number;
  directory_batch_id?: number;
}) => apiPost<MatchResult>('/import/match-ownership', params);

// ==================== Snapshot ====================

export const getSnapshots = () =>
  apiGet<DataSnapshot[]>('/import/snapshots');
