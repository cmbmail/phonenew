import { COLORS } from '../theme/morandi';

// Phone Ownership types
export interface OwnershipBatch {
  id: number;
  batch_no: string;
  file_name: string;
  total_count: number;
  exception_count: number;
  import_status: number;
  error_message: string | null;
  imported_by: number;
  created_at: string;
}

export interface OwnershipEntry {
  id: number;
  batch_id: number;
  phone_number: string;
  description: string;
  is_exception: number;
  org_id: number | null;
  match_level: string;
}

// Directory types
export interface DirectoryBatch {
  id: number;
  batch_no: string;
  file_name: string;
  total_count: number;
  seconded_count: number;
  billing_month: string | null;
  import_status: number;
  error_message: string | null;
  imported_by: number;
  created_at: string;
}

export interface DirectoryEntry {
  id: number;
  batch_id: number;
  dept_path: string;
  username: string;
  extension: string;
  phone_number: string;
  alloc_dept: string;
  org_code: string;
  cost_center: string;
  remark: string;
  org_id: number | null;
  is_seconded: number;
  actual_org_id: number | null;
  seconded_keyword: string;
}

// Import progress (async polling)
export interface ImportProgress {
  status: 'PENDING' | 'READING' | 'WRITING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN';
  total: number;
  processed: number;
  exception_count?: number;
  seconded_count?: number;
  elapsed_ms: number;
  message: string;
  sheet_info?: string;
}

// Async import trigger result
export interface AsyncImportResult {
  batch_id: number;
  batch_no: string;
  import_status: number;
  message: string;
}

// Import result
export interface ImportResult {
  batch_id: number;
  batch_no: string;
  total_count: number;
  exception_count?: number;
  seconded_count?: number;
  import_status: number;
  billing_month?: string;
  total_amount?: number;
}

// Ownership match result
export interface MatchResult {
  bill_batch_id: number;
  matched_count: number;
}

// Data snapshot
export interface DataSnapshot {
  id: number;
  bill_batch_id: number;
  ownership_batch_id: number | null;
  directory_batch_id: number | null;
  matched_count: number;
  created_at: string;
  updated_at: string;
}

export const IMPORT_STATUS_MAP: Record<number, { label: string; color: string }> = {
  0: { label: '处理中', color: COLORS.slate },
  1: { label: '成功', color: COLORS.confirmed },
  2: { label: '失败', color: COLORS.danger },
};

// Recording Data types
export interface RecordingDataBatch {
  id: number;
  batch_no: string;
  file_name: string;
  total_count: number;
  import_status: number;
  error_message: string | null;
  imported_by: number;
  created_at: string;
}

export interface RecordingDataEntry {
  id: number;
  batch_id: number;
  extension: string;
  phone_number: string;
  dept_name: string;
  remark: string;
}
