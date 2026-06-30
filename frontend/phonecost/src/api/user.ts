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

export const getUsers = (orgId?: number) => {
  const params = orgId ? `?org_id=${orgId}` : '';
  return apiGet<UserItem[]>(`/users${params}`);
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
