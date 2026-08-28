export interface BillBatch { id: number; batch_no: string; billing_month: string; file_name: string; template_id: number; status: number; total_amount: string; total_count: number; import_status: number; error_message: string | null; imported_by: number; confirmed_at: string | null; confirmed_by: number | null; locked_at: string | null; created_at: string; }
export interface BillDetail {
  id: number;
  batch_id: number;
  phone_number: string;
  extension: string;
  sheet_type: string;
  monthly_rent: number | null;
  call_fee: number | null;
  recording_fee: number | null;
  crbt_fee: number | null;
  flash_msg_fee: number | null;
  total_fee: number | null;
  ownership_source: string;
  is_exception: number;
  is_seconded: number;
  org_id: number | null;
  flash_month: string;
  raw_data: string | null;
}

export type SheetTypeCode = 'CALL' | 'RECORDING' | 'CRBT' | 'FLASH_MSG';

export const SHEET_TYPE_LABELS: Record<SheetTypeCode, string> = {
  CALL: '按号码费用',
  RECORDING: '录音费用',
  CRBT: '彩铃费用',
  FLASH_MSG: '闪信费用',
};

export const SHEET_TYPES: SheetTypeCode[] = ['CALL', 'RECORDING', 'CRBT', 'FLASH_MSG'];
