export interface LoginRequest { username: string; password: string; }
export interface LoginResponse { access_token: string; refresh_token: string; must_change_pwd: number; role: number; username: string; real_name: string; }
export interface UserInfo { id: number; username: string; real_name: string; role: number; org_id: number | null; }
