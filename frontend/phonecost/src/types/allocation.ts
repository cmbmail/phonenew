export interface AllocationResult {
  id: number;
  batch_id: number;
  org_id: number;
  org_name: string;
  monthly_rent: number;
  call_fee: number;
  recording_fee: number;
  crbt_fee: number;
  flash_msg_fee: number;
  total_fee: number;
  phone_count: number;
  confirm_status: number; // 0=pending, 1=confirmed, 2=withdrawn
  confirmed_at: string | null;
  confirmed_by: number | null;
  withdrawn_at: string | null;
  withdrawn_by: number | null;
  withdraw_reason: string;
  version: number;
  created_at: string;
  updated_at: string;
}

export interface AllocationAdjustment {
  id: number;
  batch_id: number;
  phone_number: string;
  from_org_id: number;
  to_org_id: number;
  from_org_name: string;
  to_org_name: string;
  amount: number;
  fee_type: string;
  reason: string;
  adjusted_by: number;
  adjusted_name: string;
  created_at: string;
}

export const CONFIRM_STATUS_MAP: Record<number, { label: string; color: string }> = {
  0: { label: '待确认', color: 'default' },
  1: { label: '已确认', color: 'success' },
  2: { label: '已撤回', color: 'warning' },
};
