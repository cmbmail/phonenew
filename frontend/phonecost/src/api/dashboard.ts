import { apiGet } from '../lib/request';

export interface MonthlyTrendItem {
  month: string;
  amount: number;
  count: number;
  batch_id: number;
}

export interface BranchSummaryItem {
  org_id: number;
  name: string;
  amount: number;
  phone_count: number;
  confirm_status: number;
}

export interface FeeBreakdownItem {
  name: string;
  value: number;
  color: string;
}

export interface LatestBatch {
  batch_id: number;
  month: string;
  amount: number;
  count: number;
}

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
  monthly_trend: MonthlyTrendItem[];
  branch_summary: BranchSummaryItem[];
  latest_batch: LatestBatch | null;
  fee_breakdown: FeeBreakdownItem[];
}

export const getDashboardStats = () =>
  apiGet<DashboardStats>('/dashboard/stats');
