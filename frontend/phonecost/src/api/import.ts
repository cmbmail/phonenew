import { apiPost, apiGet, apiPut, apiDelete, apiUpload, getApiBaseUrl } from '../lib/request';
import { useAuthStore } from '../store/auth';
import { message } from 'antd';
import type { ImportResult, AsyncImportResult, ImportProgress, MatchResult, OwnershipBatch, DirectoryBatch, DirectoryEntry, DataSnapshot, RecordingDataBatch, RecordingDataEntry } from '../types/import';
import type { BillBatch, BillDetail } from '../types/bill';

// ==================== Ownership ====================

export const importOwnership = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<AsyncImportResult>('/import/ownership', formData);
};

export const getOwnershipProgress = (batchId: number) =>
  apiGet<ImportProgress>(`/import/ownership/progress/${batchId}`);

export const getOwnershipBatches = () =>
  apiGet<OwnershipBatch[]>('/import/ownership/batches');

export const downloadOwnershipTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/ownership/template`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '号码归属导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

// ==================== Directory ====================

export const importDirectory = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<AsyncImportResult>('/import/directory', formData);
};

export const getDirectoryProgress = (batchId: number) =>
  apiGet<ImportProgress>(`/import/directory/progress/${batchId}`);

export const downloadDirectoryTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/directory/template`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '部门归属导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
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

export const updateDirectoryEntry = (id: number, data: { dept_path: string; alloc_dept?: string; org_code?: string; cost_center?: string; remark?: string }) =>
  apiPut<DirectoryEntry>(`/import/directory/entries/${id}`, data);

// ==================== Bill ====================

export const importBill = (file: File, billingMonth: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('billing_month', billingMonth);
  return apiUpload<AsyncImportResult>('/import/bill', formData);
};

export const getBillProgress = (batchId: number) =>
  apiGet<ImportProgress>(`/import/bill/progress/${batchId}`);

export const downloadBillTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/bill/template`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '账单导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

export const getBillBatches = (billingMonth?: string) =>
  apiGet<BillBatch[]>('/import/bill/batches', billingMonth ? { billing_month: billingMonth } : undefined);

export const getBillMonths = () =>
  apiGet<string[]>('/import/bill/months');

export const updateBillBatchMonth = (id: number, billingMonth: string) =>
  apiPut<BillBatch>(`/import/bill/batches/${id}/month`, { billing_month: billingMonth });

export const deleteBillBatch = (id: number) =>
  apiDelete<{ id: number; batch_no: string; deleted: boolean }>(`/import/bill/batches/${id}`);

export const getBillDetails = (batchId: number, sheetType?: string, page = 0, size = 50) => {
  const params: Record<string, string | number> = { page, size };
  if (sheetType) params.sheet_type = sheetType;
  return apiGet<{ entries: BillDetail[]; total: number; page: number; size: number }>(
    `/import/bill/details/${batchId}`,
    params as Record<string, string>,
  );
};

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

// ==================== Recording Data ====================

export const importRecordingData = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<AsyncImportResult>('/import/recording-data', formData);
};

export const getRecordingDataProgress = (batchId: number) =>
  apiGet<ImportProgress>(`/import/recording-data/progress/${batchId}`);

export const downloadRecordingDataTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/recording-data/template`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Download failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '录音数据导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

export const getRecordingDataBatches = () =>
  apiGet<RecordingDataBatch[]>('/import/recording-data/batches');

export const getRecordingDataEntries = (batchId: number, page = 0, size = 50) =>
  apiGet<{ entries: RecordingDataEntry[]; total: number; page: number; size: number }>(
    `/import/recording-data/entries/${batchId}`,
    { page, size } as Record<string, string>,
  );

export const deleteRecordingDataBatch = (id: number) =>
  apiDelete<{ id: number; batch_no: string; deleted: boolean }>(`/import/recording-data/batches/${id}`);
