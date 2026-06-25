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
    return Promise.reject(error);
  }
);

export async function apiGet<T>(url: string, params?: object): Promise<T> {
  const { data } = await instance.get<ApiResponse<T>>(url, { params });
  return data.data;
}
export async function apiPost<T>(url: string, body?: unknown): Promise<T> {
  const { data } = await instance.post<ApiResponse<T>>(url, body);
  return data.data;
}
export async function apiPut<T>(url: string, body?: unknown): Promise<T> {
  const { data } = await instance.put<ApiResponse<T>>(url, body);
  return data.data;
}
export async function apiDelete<T>(url: string): Promise<T> {
  const { data } = await instance.delete<ApiResponse<T>>(url);
  return data.data;
}
export async function apiUpload<T>(url: string, formData: FormData): Promise<T> {
  const { data } = await instance.post<ApiResponse<T>>(url, formData, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 });
  return data.data;
}
