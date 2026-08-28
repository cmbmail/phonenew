import { apiPost, apiGet, apiPut, apiDelete, apiUpload, getApiBaseUrl } from '../lib/request';
import { useAuthStore } from '../store/auth';
import { message } from 'antd';
import type { AsyncImportResult, ImportProgress, MatchResult, OwnershipBatch, OwnershipEntry, DirectoryBatch, DirectoryEntry, DataSnapshot, RecordingDataBatch, RecordingDataEntry, AllocDeptBatch, AllocDeptEntry } from '../types/import';
import type { BillBatch, BillDetail } from '../types/bill';
// ==================== Allocation Org (号码分摊机构) ====================

export const importAllocOrg = (file: File, billingMonth: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('billing_month', billingMonth);
  return apiUpload<AsyncImportResult>('/import/allocation-org', formData);
};

export const getAllocOrgProgress = (batchId: number) =>
  apiGet<ImportProgress>(`/import/allocation-org/progress/${batchId}`);

export const getAllocOrgMonths = (source?: string) => {
  const params: Record<string, string> = {};
  if (source) params.source = source;
  return apiGet<string[]>('/import/allocation-org/months', params);
};

// 号码分摊机构批次列表（按月份 + 来源过滤；source: import=导入, push=推送）
export const getAllocOrgBatches = (billingMonth?: string, source?: string) => {
  const params: Record<string, string> = {};
  if (billingMonth) params.billing_month = billingMonth;
  if (source) params.source = source;
  return apiGet<Array<Record<string, any>>>('/import/allocation-org/batches', params);
};

// 号码分摊机构批次明细（按批次 ID 分页查询）
export const getAllocOrgEntriesByBatch = (batchId: number, search?: string, page = 0, size = 50) => {
  const params: Record<string, string> = { page: String(page), size: String(size) };
  if (search) params.search = search;
  return apiGet<{
    entries: Array<{
      id: number;
      batch_id: number;
      phone_number: string;
      l1_branch: string;
      alloc_dept: string;
      org_code: string;
      cost_center: string;
      remark: string;
    }>;
    total: number;
    page: number;
    size: number;
  }>(`/import/allocation-org/entries-by-batch/${batchId}`, params);
};

export const getAllocOrgEntriesByMonth = (billingMonth: string, search?: string, page = 0, size = 50, source?: string, changeType?: string) => {
  const params: Record<string, string> = { billing_month: billingMonth, page: String(page), size: String(size) };
  if (search) params.search = search;
  if (source) params.source = source;
  if (changeType) params.change_type = changeType;
  return apiGet<{
    entries: Array<{
      id: number;
      batch_id: number;
      phone_number: string;
      l1_branch: string;
      alloc_dept: string;
      org_code: string;
      cost_center: string;
      remark: string;
    }>;
    total: number;
    page: number;
    size: number;
  }>('/import/allocation-org/entries-by-month', params);
};

export const updateAllocOrgEntry = (id: number, data: {
  phone_number?: string;
  l1_branch?: string;
  alloc_dept?: string;
  org_code?: string;
  cost_center?: string;
  remark?: string;
}) =>
  apiPut<{ id: number; updated: boolean }>(`/import/allocation-org/entries/${id}`, data);

export const deleteAllocOrgEntry = (id: number) =>
  apiDelete<{ id: number; deleted: boolean }>(`/import/allocation-org/entries/${id}`);

export const deleteAllocOrgBatch = (id: number) =>
  apiDelete<{ id: number; deleted: boolean }>(`/import/allocation-org/batches/${id}`);

export const verifyAllocOrgEntry = (id: number) =>
  apiPost<{ id: number; verified: boolean }>(`/import/allocation-org/entries/${id}/verify`);

export const verifyEditAllocOrgEntry = (id: number, data: { alloc_dept?: string; branch_id?: number }) =>
  apiPost<{ id: number; verified: boolean; alloc_dept: string }>(`/import/allocation-org/entries/${id}/verify-edit`, data);

export const downloadAllocOrgTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/allocation-org/template`;
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
      link.download = '号码分摊机构导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

export const exportAllocOrg = (billingMonth?: string, source?: string) => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const params: string[] = [];
  if (billingMonth) params.push(`billing_month=${encodeURIComponent(billingMonth)}`);
  if (source) params.push(`source=${encodeURIComponent(source)}`);
  const queryStr = params.length > 0 ? `?${params.join('&')}` : '';
  const url = `${baseUrl}/import/allocation-org/export${queryStr}`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '号码分摊机构导出.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

// ==================== Org Code Mapping (组织机构对照表) ====================

export const getOrgCodeMappingEntries = (search?: string, page = 0, size = 50) => {
  const params: Record<string, string> = { page: String(page), size: String(size) };
  if (search) params.search = search;
  return apiGet<{
    entries: Array<{
      id: number;
      org_code: string;
      org_name: string;
      cost_center_code: string;
      remark: string;
      created_at: string;
      updated_at: string;
    }>;
    total: number;
    page: number;
    size: number;
  }>('/import/org-code-mapping', params);
};

export const createOrgCodeMapping = (data: { org_code: string; org_name: string; cost_center_code?: string; remark?: string }) =>
  apiPost<Record<string, unknown>>('/import/org-code-mapping', data);

export const updateOrgCodeMapping = (id: number, data: { org_code: string; org_name: string; cost_center_code?: string; remark?: string }) =>
  apiPut<Record<string, unknown>>(`/import/org-code-mapping/${id}`, data);

export const deleteOrgCodeMapping = (id: number) =>
  apiDelete<{ id: number; deleted: boolean }>(`/import/org-code-mapping/${id}`);

export const batchDeleteOrgCodeMapping = (ids: number[]) =>
  apiPost<{ deleted: number }>('/import/org-code-mapping/batch', { ids });

export const importOrgCodeMapping = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<{ imported: number }>('/import/org-code-mapping/import', formData);
};

export const exportOrgCodeMapping = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/org-code-mapping/export`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '组织机构对照表导出.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

export const downloadOrgCodeMappingTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/org-code-mapping/template`;
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
      link.download = '组织机构对照表导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};



export const generateOwnership = (billingMonth: string) =>
  apiPost<{ batch_id: number; batch_no: string; total_count: number; exception_count: number; elapsed_ms: number }>(
    '/import/ownership/generate',
    { billing_month: billingMonth }
  );

export const syncAllocationOrg = (billingMonth: string) =>
  apiPost<{ total: number; updated: number; skipped: number; message: string }>(
    '/import/ownership/sync-allocation-org',
    { billing_month: billingMonth }
  );

export const importOwnership = (file: File, billingMonth?: string) => {
  const formData = new FormData();
  formData.append('file', file);
  if (billingMonth) formData.append('billing_month', billingMonth);
  return apiUpload<AsyncImportResult>('/import/ownership', formData);
};

export const getOwnershipProgress = (batchId: number) =>
  apiGet<ImportProgress>(`/import/ownership/progress/${batchId}`);

export const getOwnershipBatches = (billingMonth?: string) =>
  apiGet<OwnershipBatch[]>('/import/ownership/batches', billingMonth ? { billing_month: billingMonth } : undefined);

export const getOwnershipMonths = () =>
  apiGet<string[]>('/import/ownership/months');

export const getOwnershipEntriesByMonth = (billingMonth: string, search?: string, page = 0, size = 50) => {
  const params: Record<string, string> = { billing_month: billingMonth, page: String(page), size: String(size) };
  if (search) params.search = search;
  return apiGet<{
    entries: OwnershipEntry[];
    total: number;
    filtered: number;
    page: number;
    size: number;
  }>('/import/ownership/entries-by-month', params);
};

export const updateOwnershipEntry = (id: number, data: Partial<Pick<OwnershipEntry, 'l1_branch' | 'l2_branch' | 'status'>>) =>
  apiPut<{ id: number; updated: boolean }>(`/import/ownership/entries/${id}`, data);

export const addOwnershipEntry = (data: { phone_number: string; l1_branch?: string; l2_branch?: string; full_path?: string; extension?: string; status?: number }) =>
  apiPost<{ id: number; batch_id: number }>('/import/ownership/entries', data);

export const deleteOwnershipBatch = (id: number) =>
  apiDelete<{ id: number; batch_no: string; deleted: boolean }>(`/import/ownership/batches/${id}`);

export const deleteOwnershipEntry = (id: number) =>
  apiDelete<{ id: number; phone_number: string; deleted: boolean }>(`/import/ownership/entries/${id}`);

export const exportOwnership = (billingMonth?: string) => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const params = billingMonth ? `?billing_month=${encodeURIComponent(billingMonth)}` : '';
  const url = `${baseUrl}/import/ownership/export${params}`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '号码归属导出.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

export const exportBranchOwnership = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/ownership/export-branch`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '归属分行导出.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

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

// ==================== Ownership Exceptions ====================

export const addExceptionEntry = (data: { billing_month: string; phone_number: string; extension?: string; full_path?: string; l1_branch?: string; l2_branch?: string; description?: string }) =>
  apiPost<{ id: number; batch_id: number }>('/import/ownership/exceptions', data);

export const updateExceptionEntry = (id: number, data: { phone_number?: string; extension?: string; full_path?: string; l1_branch?: string; l2_branch?: string; description?: string }) =>
  apiPut<{ id: number; updated: boolean }>(`/import/ownership/exceptions/${id}`, data);

export const deleteExceptionEntry = (id: number) =>
  apiDelete<{ id: number; deleted: boolean }>(`/import/ownership/exceptions/${id}`);

export const downloadExceptionTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/ownership/exceptions/template`;
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
      link.download = '例外号码导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

export const importExceptions = (file: File, billingMonth: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('billing_month', billingMonth);
  return apiUpload<{ batch_id: number; imported: number; message: string }>('/import/ownership/exceptions/import', formData);
};

// All exceptions (cross-batch, no month required)
export const getAllExceptionEntries = (search?: string, page = 0, size = 50) => {
  const params: Record<string, string> = { page: String(page), size: String(size) };
  if (search) params.search = search;
  return apiGet<{
    entries: Array<{
      id: number;
      phone_number: string;
      extension: string;
      full_path: string;
      description: string;
      match_level: string;
      matched_branch: string;
      matched_dept: string;
      exception_reason: string;
    }>;
    total: number;
    filtered: number;
    page: number;
    size: number;
  }>('/import/ownership/all-exceptions', params);
};

export const exportAllExceptions = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/ownership/exceptions/export-all`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '例外号码导出.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

// ==================== Directory ====================

export const importDirectory = (file: File, billingMonth?: string) => {
  const formData = new FormData();
  formData.append('file', file);
  if (billingMonth) formData.append('billing_month', billingMonth);
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
      link.download = '通讯录导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

export const getDirectoryBatches = (billingMonth?: string) =>
  apiGet<DirectoryBatch[]>('/import/directory/batches', billingMonth ? { billing_month: billingMonth } : undefined);

// Directory entries by batch (used by the batch-detail drill-down in DataComparisonPage)
export const getDirectoryEntriesByBatch = (batchId: number, search?: string, page = 0, size = 50) => {
  const params: Record<string, string> = { page: String(page), size: String(size) };
  if (search) params.search = search;
  return apiGet<{
    entries: DirectoryEntry[];
    total: number;
    filtered: number;
    page: number;
    size: number;
    codeToNameMap?: Record<string, string>;
  }>(`/import/directory/entries/${batchId}`, params);
};

export const getDirectoryMonths = () =>
  apiGet<string[]>('/import/directory/months');

export const getExceptionMonths = () =>
  apiGet<string[]>('/import/directory/exception-months');

export const deleteDirectoryBatch = (id: number) =>
  apiDelete<{ id: number; deleted: boolean }>(`/import/directory/batches/${id}`);

export const deleteDirectoryBatchesByMonth = (billingMonth: string) =>
  apiDelete<{ billing_month: string; deleted_count: number; deleted: boolean }>(`/import/directory/batches/month/${billingMonth}`);

export const updateDirectoryEntry = (id: number, data: { dept_path: string; alloc_dept?: string; org_code?: string; cost_center?: string; remark?: string }) =>
  apiPut<DirectoryEntry>(`/import/directory/entries/${id}`, data);

// ==================== Directory (cost center) - all entries ====================

export const getAllDirectoryEntries = (search?: string, page = 0, size = 50) => {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (search) params.set('search', search);
  return apiGet<{ entries: DirectoryEntry[]; total: number; filtered: number; page: number; size: number }>(`/import/directory/all-entries?${params}`);
};

export const addDirectoryEntry = (data: { dept_path: string; username?: string; extension?: string; phone_number?: string; alloc_dept?: string; org_code?: string; cost_center?: string; remark?: string }) =>
  apiPost<DirectoryEntry>('/import/directory/entries', data);

export const deleteDirectoryEntry = (id: number) =>
  apiDelete<{ id: number; deleted: boolean }>(`/import/directory/entries/${id}`);

export const exportAllDirectoryEntries = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  fetch(`${baseUrl}/import/directory/export-all`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '通讯录导出.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

// Cost center template & export
export const downloadCostCenterTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/directory/cost-center-template`;
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
      link.download = '成本中心导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

export const exportCostCenterEntries = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  fetch(`${baseUrl}/import/directory/export-cost-center`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '成本中心导出.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

// ==================== Directory Comparison ====================

export const compareDirectory = (month1: string, month2: string, page?: number, size?: number, search?: string, type?: string) => {
  const params: Record<string, string> = { month1, month2 };
  if (page !== undefined) params.page = String(page);
  if (size !== undefined) params.size = String(size);
  if (search) params.search = search;
  if (type) params.type = type;
  return apiGet<{
    diffs: Array<{
      type: string;
      dept_path: string;
      username: string;
      extension: string;
      phone_number: string;
      month1_dept_path: string;
      month1_username: string;
      month1_extension: string;
      changed_columns: string[];
    }>;
    month1: string;
    month2: string;
    month1_count: number;
    month2_count: number;
    added: number;
    removed: number;
    changed: number;
    unchanged: number;
    total: number;
    page?: number;
    size?: number;
    total_pages?: number;
  }>('/import/directory/compare', params);
};

export const exportDirectoryComparison = (month1: string, month2: string, types?: string[]) => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  let url = `${baseUrl}/import/directory/compare/export?month1=${encodeURIComponent(month1)}&month2=${encodeURIComponent(month2)}`;
  if (types && types.length > 0) {
    url += `&types=${encodeURIComponent(types.join(','))}`;
  }
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = `通讯录对比_${month1}_vs_${month2}.xlsx`;
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

export const compareExceptionEntries = (page?: number, size?: number, onlyDiff?: boolean, month?: string, search?: string) => {
  const params: Record<string, string> = {};
  if (page !== undefined) params.page = String(page);
  if (size !== undefined) params.size = String(size);
  if (onlyDiff !== undefined) params.only_diff = String(onlyDiff);
  if (month) params.month = month;
  if (search) params.search = search;
  return apiGet<{
    entries: Array<{
      id: number;
      phone_number: string;
      dept_path: string;
      username: string;
      extension: string;
      seconded_keyword: string;
      billing_month: string;
      latest_dept_path: string;
      latest_username: string;
      latest_extension: string;
      latest_phone_number: string;
      changed_columns: string[];
      has_diff: boolean;
    }>;
    total: number;
    total_all: number;
    changed: number;
    unchanged: number;
    billing_month: string;
    page?: number;
    size?: number;
    total_pages?: number;
  }>('/import/directory/exception-compare', params);
};

// ==================== Comparison Archive ====================

export const getLatestComparisonArchive = () =>
  apiGet<any>('/import/directory/comparison-archive/latest');

// ==================== Push Comparison to Allocation Org ====================

export const pushComparisonToAllocationOrg = (params: {
  push_type: 'directory' | 'exception';
  month1?: string;
  month2?: string;
  month?: string;
  types?: string[];
}): Promise<any> => {
  return apiPost('/import/allocation-org/push-from-comparison', params);
};

/**
 * 分行号码 → 号码分摊机构 推送
 * @param sourceMonth 分行号码月份（数据来源）
 * @param targetMonth 推送目标月份（可选，默认 sourceMonth）
 */
export const pushBranchNumberToAllocationOrg = (sourceMonth: string, targetMonth?: string): Promise<any> => {
  return apiPost('/import/allocation-org/push-from-branch-number', {
    source_month: sourceMonth,
    target_month: targetMonth,
  });
};

// ==================== Current/Exception Export ====================

export const exportDirectoryByMonth = (billingMonth: string): Promise<void> => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/directory/month-entries/export?billing_month=${encodeURIComponent(billingMonth)}`;
  return fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = `通讯录_${billingMonth}.xlsx`;
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch((err) => {
      message.error('导出失败');
      throw err;
    });
};

export const exportExceptionCompare = (month?: string, onlyDiff = true): Promise<void> => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const params = new URLSearchParams();
  if (month) params.set('month', month);
  if (onlyDiff) params.set('only_diff', 'true');
  const url = `${baseUrl}/import/directory/exception-compare-export?${params.toString()}`;
  return fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = `例外数据差异${month ? '_' + month : ''}.xlsx`;
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch((err) => {
      message.error('导出失败');
      throw err;
    });
};

export const exportExceptionByMonth = (billingMonth: string): Promise<void> => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/directory/exception-entries/month-export?billing_month=${encodeURIComponent(billingMonth)}`;
  return fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = `例外数据_${billingMonth}.xlsx`;
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch((err) => {
      message.error('导出失败');
      throw err;
    });
};

export const downloadDirectoryExceptionTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/directory/exception-template`;
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
      link.download = '例外数据导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

// ==================== Exception Import ====================

export const importExceptionEntries = (file: File, billingMonth: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('billing_month', billingMonth);
  return apiUpload<{ imported: number; skipped: number }>(
    '/import/directory/exception-import', formData
  );
};

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

export const getBillDetails = (batchId: number, sheetType?: string, page = 0, size = 50, keyword?: string) => {
  const params: Record<string, string | number> = { page, size };
  if (sheetType) params.sheet_type = sheetType;
  if (keyword && keyword.trim()) params.keyword = keyword.trim();
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

export const importRecordingData = (file: File, billingMonth: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('billing_month', billingMonth);
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

export const getRecordingDataBatches = (billingMonth?: string) =>
  apiGet<RecordingDataBatch[]>('/import/recording-data/batches', billingMonth ? { billing_month: billingMonth } : undefined);

export const getRecordingDataMonths = () =>
  apiGet<string[]>('/import/recording-data/months');

export const getRecordingDataEntriesByMonth = (billingMonth: string, search?: string, page = 0, size = 50) => {
  const params: Record<string, string> = { billing_month: billingMonth, page: String(page), size: String(size) };
  if (search) params.search = search;
  return apiGet<{ entries: RecordingDataEntry[]; total: number; filtered: number; page: number; size: number }>(
    '/import/recording-data/entries-by-month',
    params,
  );
};

export const deleteRecordingDataBatch = (id: number) =>
  apiDelete<{ id: number; batch_no: string; deleted: boolean }>(`/import/recording-data/batches/${id}`);

export const addRecordingDataEntry = (data: { billing_month: string; extension?: string; phone_number?: string; dept_name?: string; remark?: string }) =>
  apiPost<{ id: number; batch_id: number }>('/import/recording-data/entries', data);

export const exportRecordingData = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/recording-data/export`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = '录音数据导出.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};

// ==================== Allocation Dept ====================

export const importAllocDept = (file: File, billingMonth: string) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('billing_month', billingMonth);
  return apiUpload<AsyncImportResult>('/import/allocation-dept', formData);
};

export const getAllocDeptProgress = (batchId: number) =>
  apiGet<ImportProgress>(`/import/allocation-dept/progress/${batchId}`);

export const getAllocDeptBatches = (billingMonth?: string) =>
  apiGet<AllocDeptBatch[]>('/import/allocation-dept/batches', billingMonth ? { billing_month: billingMonth } : undefined);

export const getAllocDeptMonths = () =>
  apiGet<string[]>('/import/allocation-dept/months');

export const getAllocDeptEntriesByMonth = (billingMonth: string, search?: string, page = 0, size = 50) => {
  const params: Record<string, string> = { billing_month: billingMonth, page: String(page), size: String(size) };
  if (search) params.search = search;
  return apiGet<{
    entries: AllocDeptEntry[];
    total: number;
    filtered: number;
    page: number;
    size: number;
  }>('/import/allocation-dept/entries-by-month', params);
};

export const downloadAllocDeptTemplate = () => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/allocation-dept/template`;
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
      link.download = '分摊部门导入模板.xlsx';
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('模板下载失败，请检查网络或重新登录');
    });
};

export const addAllocDeptEntry = (data: { billing_month: string; branch: string; dept_name: string; full_path: string; org_code: string; cost_center: string }) =>
  apiPost<{ id: number; batch_id: number }>('/import/allocation-dept/entries', data);

export const deleteAllocDeptBatch = (batchId: number) =>
  apiDelete(`/import/allocation-dept/batches/${batchId}`);

export const exportAllocDept = (billingMonth: string) => {
  const token = useAuthStore.getState().token;
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl}/import/allocation-dept/export?billing_month=${encodeURIComponent(billingMonth)}`;
  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error('Export failed');
      return res.blob();
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = blobUrl;
      link.download = `分摊部门_${billingMonth}.xlsx`;
      link.click();
      URL.revokeObjectURL(blobUrl);
    })
    .catch(() => {
      message.error('导出失败，请检查网络或重新登录');
    });
};



