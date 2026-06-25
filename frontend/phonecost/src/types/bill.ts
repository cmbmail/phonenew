export interface BillBatch { id: number; batch_no: string; billing_month: string; file_name: string; template_id: number; status: number; total_amount: string; total_count: number; import_status: number; error_message: string | null; imported_by: number; confirmed_at: string | null; confirmed_by: number | null; locked_at: string | null; created_at: string; }
export const BILL_STATUS_LABELS: Record<number, string> = { 0: '草稿', 1: '已分摊', 2: '已确认', 3: '已锁定' };
export const BILL_STATUS_COLORS: Record<number, string> = { 0: 'default', 1: 'processing', 2: 'success', 3: 'warning' };
