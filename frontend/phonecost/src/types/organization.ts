export interface Organization { id: number; name: string; type: number; code: string; parent_id: number | null; sort_order: number; path: string; is_active: number; created_at: string; updated_at: string; }
export const ORG_TYPE_LABELS: Record<number, string> = { 1: '集团', 2: '一级分行', 3: '二级分行', 4: '部门', 5: '综合支行', 6: '零专支行' };
export const ORG_TYPE_OPTIONS = [
  { value: 1, label: '集团' },
  { value: 2, label: '一级分行' },
  { value: 3, label: '二级分行' },
  { value: 4, label: '部门' },
  { value: 5, label: '综合支行' },
  { value: 6, label: '零专支行' },
];
export const ROLE_LABELS: Record<number, string> = {
  1: '集团管理员', 2: '分行管理员', 3: '部门管理员', 4: '财务人员',
};
export const ROLE_OPTIONS = [
  { value: 1, label: '集团管理员' },
  { value: 2, label: '分行管理员' },
  { value: 3, label: '部门管理员' },
  { value: 4, label: '财务人员' },
];
