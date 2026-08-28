import { apiGet } from '../lib/request';

export interface AuditLogEntry {
  id: number;
  user_id: number;
  username: string;
  action: string;
  entity_type: string;
  entity_id: number | null;
  detail: string | null;
  ip_address: string;
  created_at: string;
}

export interface PagedAuditLogs {
  content: AuditLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface AuditLogParams {
  page?: number;
  size?: number;
  action?: string;
  username?: string;
  entityType?: string;
  startDate?: string;
  endDate?: string;
}

export const getAuditLogs = (params: AuditLogParams = {}) => {
  const qs = new URLSearchParams();
  if (params.page != null) qs.set('page', String(params.page));
  if (params.size != null) qs.set('size', String(params.size));
  if (params.action) qs.set('action', params.action);
  if (params.username) qs.set('username', params.username);
  if (params.entityType) qs.set('entityType', params.entityType);
  if (params.startDate) qs.set('startDate', params.startDate);
  if (params.endDate) qs.set('endDate', params.endDate);
  return apiGet<PagedAuditLogs>(`/audit-logs?${qs.toString()}`);
};
