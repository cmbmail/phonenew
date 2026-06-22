import { apiGet } from '../lib/request';

export interface DashboardStats {
  org_count: number;
  user_count: number;
  bill_batch_count: number;
  bill_detail_count: number;
  total_amount: number;
  allocation_result_count: number;
  confirmed_count: number;
  pending_count: number;
  branch_count: number;
}

export const getDashboardStats = () =>
  apiGet<DashboardStats>('/dashboard/stats');
