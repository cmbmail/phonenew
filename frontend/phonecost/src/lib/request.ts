import axios, { type AxiosInstance, type InternalAxiosRequestConfig, type AxiosResponse } from 'axios';
import type { ApiResponse } from '../types/api';
import { useAuthStore } from '../store/auth';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';

const instance: AxiosInstance = axios.create({ baseURL: API_BASE_URL, timeout: 30000, headers: { 'Content-Type': 'application/json' } });

// Refresh lock: prevent concurrent refresh requests
let refreshPromise: Promise<string | null> | null = null;

function getOrStartRefresh(): Promise<string | null> {
  if (refreshPromise) return refreshPromise;
  const refreshToken = useAuthStore.getState().refreshToken;
  if (!refreshToken) return Promise.resolve(null);
  refreshPromise = axios.post(`${API_BASE_URL}/auth/refresh`, { refresh_token: refreshToken })
    .then(({ data }) => {
      if (data.code === 200) {
        useAuthStore.getState().setToken(data.data.access_token);
        // H-05 fix: 同时保存新的refresh_token（后端已实现token轮转）
        if (data.data.refresh_token) {
          useAuthStore.getState().setRefreshToken(data.data.refresh_token);
        }
        return data.data.access_token as string;
      }
      return null;
    })
    .catch(() => null)
    .finally(() => { refreshPromise = null; });
  return refreshPromise;
}

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = useAuthStore.getState().token;
  if (token && config.headers) { config.headers.Authorization = `Bearer ${token}`; }
  return config;
});

instance.interceptors.response.use(
  (res: AxiosResponse) => res,
  async (error) => {
    // 请求被主动取消时不做处理
    if (axios.isCancel(error)) return Promise.reject(error);

    const originalRequest = error.config;
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true;
      const newToken = await getOrStartRefresh();
      if (newToken) {
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        return instance(originalRequest);
      }
      useAuthStore.getState().logout();
      window.location.href = '/login';
    }
    // 后端强制改密拦截：403 + data=MUST_CHANGE_PASSWORD
    if (error.response?.status === 403 && error.response?.data?.data === 'MUST_CHANGE_PASSWORD') {
      useAuthStore.getState().setMustChangePwd(true);
    }
    return Promise.reject(error);
  }
);

// ============ API Base URL 工具 ============
// 安全获取 Vite 注入的 __API_BASE__ 全局变量，替代 window as unknown as Record 模式
declare global {
  // eslint-disable-next-line no-var
  var __API_BASE__: string | undefined;
}
export function getApiBaseUrl(): string {
  return globalThis.__API_BASE__ || API_BASE_URL;
}


export async function apiGet<T>(url: string, params?: object, signal?: AbortSignal): Promise<T> {
  const { data } = await instance.get<ApiResponse<T>>(url, { params, signal });
  return data.data;
}
export async function apiPost<T>(url: string, body?: unknown, signal?: AbortSignal, timeout?: number): Promise<T> {
  const { data } = await instance.post<ApiResponse<T>>(url, body, { signal, timeout: timeout ?? undefined });
  return data.data;
}
export async function apiPut<T>(url: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const { data } = await instance.put<ApiResponse<T>>(url, body, { signal });
  return data.data;
}
export async function apiDelete<T>(url: string, signal?: AbortSignal): Promise<T> {
  const { data } = await instance.delete<ApiResponse<T>>(url, { signal });
  return data.data;
}
export async function apiUpload<T>(url: string, formData: FormData, signal?: AbortSignal): Promise<T> {
  // Use transformRequest to delete Content-Type so browser auto-sets
  // multipart/form-data with boundary; instance default is application/json
  const { data } = await instance.post<ApiResponse<T>>(url, formData, {
    timeout: 600000,
    signal,
    transformRequest: [(data, headers) => {
      delete headers['Content-Type'];
      return data;
    }]
  });
  return data.data;
}

export async function apiDownload(url: string, filename: string, signal?: AbortSignal): Promise<void> {
  const response = await instance.get(url, { responseType: 'blob', signal });
  const blob = new Blob([response.data]);
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  // FE-M-16 fix: wrap in try/finally to guarantee ObjectURL revocation
  try { link.click(); } finally { URL.revokeObjectURL(link.href); }
}
