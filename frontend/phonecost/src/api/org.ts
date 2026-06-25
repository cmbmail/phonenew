import { apiGet, apiPost, apiPut, apiDelete, apiUpload } from '../lib/request';
import type { Organization } from '../types/organization';

export const getOrgTree = () => apiGet<Organization[]>('/org/tree');

export const createOrg = (data: Partial<Organization>) =>
  apiPost<Organization>('/org', data);

export const updateOrg = (id: number, data: Partial<Organization>) =>
  apiPut<Organization>(`/org/${id}`, data);

export const deleteOrg = (id: number) => apiDelete<void>(`/org/${id}`);

export const importOrg = (file: File) => {
  const formData = new FormData();
  formData.append('file', file);
  return apiUpload<{ total: number; created: number; skipped: number }>('/org/import', formData);
};

export const rebuildOrgPaths = () => apiPost<void>('/org/rebuild-paths');
