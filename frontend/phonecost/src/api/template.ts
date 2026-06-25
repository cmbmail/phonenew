import { apiGet, apiPost, apiPut, apiDelete } from '../lib/request';
import type { BillTemplate } from '../types/template';

export const getTemplates = () => apiGet<BillTemplate[]>('/templates');

export const createTemplate = (body: {
  name: string;
  operator?: string;
  month_pattern?: string;
  description?: string;
  sheet_configs: string;
}) => apiPost<BillTemplate>('/templates', body);

export const updateTemplate = (id: number, body: Partial<{
  name: string;
  operator: string;
  month_pattern: string;
  description: string;
  sheet_configs: string;
}>) => apiPut<BillTemplate>(`/templates/${id}`, body);

export const deleteTemplate = (id: number) => apiDelete(`/templates/${id}`);

export const activateTemplate = (id: number) =>
  apiPost<BillTemplate>(`/templates/${id}/activate`, {});
