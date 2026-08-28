import { apiGet, apiPost, apiPut, apiDelete } from '../lib/request';

export interface UserItem {
  id: number;
  username: string;
  real_name: string;
  role: number;
  org_id: number | null;
  status: number;
  must_change_pwd: number;
  created_at: string;
  updated_at: string;
}

export interface PagedUsers {
  content: UserItem[];
  total: number;
  page: number;
  size: number;
}

export interface GetUsersParams {
  orgId?: number;
  username?: string;
  realName?: string;
  page?: number;
  size?: number;
}

export const getUsers = (params: GetUsersParams = {}) => {
  const qs = new URLSearchParams();
  if (params.orgId != null) qs.set('org_id', String(params.orgId));
  if (params.username) qs.set('username', params.username);
  if (params.realName) qs.set('realName', params.realName);
  qs.set('page', String(params.page ?? 0));
  qs.set('size', String(params.size ?? 20));
  return apiGet<PagedUsers>(`/users?${qs.toString()}`);
};

export const createUser = (data: {
  username: string;
  password: string;
  real_name: string;
  role: number;
  org_id?: number;
  status?: number;
}) => apiPost<UserItem>('/users', data);

export const updateUser = (id: number, data: {
  real_name: string;
  role: number;
  org_id?: number;
  status: number;
}) => apiPut<UserItem>(`/users/${id}`, data);

export const deleteUser = (id: number) => apiDelete<void>(`/users/${id}`);

export const resetPassword = (id: number, new_password: string) =>
  apiPut<void>(`/users/${id}/reset-password`, { new_password });
