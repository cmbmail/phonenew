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
  org_id: number | null;
  is_seconded: number;
  actual_org_id: number | null;
  seconded_keyword: string;
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

export const IMPORT_STATUS_MAP: Record<number, { label: string; color: string }> = {
  0: { label: '处理中', color: 'processing' },
  1: { label: '成功', color: 'success' },
  2: { label: '失败', color: 'error' },
};

export const MATCH_LEVEL_MAP: Record<string, { label: string; color: string }> = {
  P0: { label: '例外标记', color: 'red' },
  P1: { label: '通讯录', color: 'green' },
  P2: { label: '号码归属', color: 'blue' },
  P3: { label: '未归属', color: 'default' },
};
