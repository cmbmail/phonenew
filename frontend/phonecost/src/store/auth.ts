import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { getApiBaseUrl } from '../lib/request';

interface AuthState {
  token: string | null; refreshToken: string | null; role: number | null;
  username: string | null; realName: string | null; orgId: number | null;
  mustChangePwd: boolean;
  setAuth: (data: { access_token: string; refresh_token: string; role: number; username: string; real_name: string; org_id?: number; must_change_pwd: number }) => void;
  setToken: (token: string) => void; setRefreshToken: (refreshToken: string) => void; clearMustChangePwd: () => void; setMustChangePwd: (v: boolean) => void; logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist((set, get) => ({
    token: null, refreshToken: null, role: null, username: null, realName: null, orgId: null, mustChangePwd: false,
    setAuth: (data) => set({ token: data.access_token, refreshToken: data.refresh_token, role: data.role, username: data.username, realName: data.real_name, orgId: data.org_id ?? null, mustChangePwd: data.must_change_pwd === 1 }),
    setToken: (token) => set({ token }),
  setRefreshToken: (refreshToken: string) => set({ refreshToken }),
    clearMustChangePwd: () => set({ mustChangePwd: false }),
    setMustChangePwd: (v: boolean) => set({ mustChangePwd: v }),
    logout: () => {
      // H-S04: Revoke refresh token on server before clearing local state
      const { token, refreshToken } = get();
      if (token && refreshToken) {
        const baseUrl = getApiBaseUrl();
        fetch(`${baseUrl}/auth/logout`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
          body: JSON.stringify({ refresh_token: refreshToken }),
        }).catch(() => { /* best-effort, ignore errors */ });
      }
      set({ token: null, refreshToken: null, role: null, username: null, realName: null, orgId: null, mustChangePwd: false });
    },
  }), { name: 'phonecost-auth' })
);
