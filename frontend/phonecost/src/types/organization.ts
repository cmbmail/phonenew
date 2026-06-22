export interface Organization { id: number; name: string; type: number; code: string; parent_id: number | null; sort_order: number; path: string; is_active: number; created_at: string; updated_at: string; }
export const ORG_TYPE_LABELS: Record<number, string> = { 1: '集团', 2: '一级分行', 3: '二级分行', 4: '部门' };
