import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface AuthState {
  token: string | null; refreshToken: string | null; role: number | null;
  username: string | null; realName: string | null; mustChangePwd: boolean;
  setAuth: (data: { access_token: string; refresh_token: string; role: number; username: string; real_name: string; must_change_pwd: number }) => void;
  setToken: (token: string) => void; logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist((set) => ({
    token: null, refreshToken: null, role: null, username: null, realName: null, mustChangePwd: false,
    setAuth: (data) => set({ token: data.access_token, refreshToken: data.refresh_token, role: data.role, username: data.username, realName: data.real_name, mustChangePwd: data.must_change_pwd === 1 }),
    setToken: (token) => set({ token }),
    logout: () => set({ token: null, refreshToken: null, role: null, username: null, realName: null, mustChangePwd: false }),
  }), { name: 'phonecost-auth' })
);
