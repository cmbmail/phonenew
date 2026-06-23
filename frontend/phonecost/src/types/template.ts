export interface BillTemplate {
  id: number;
  name: string;
  operator: string;
  month_pattern: string | null;
  description: string | null;
  sheet_configs: string;
  is_active: number;
  created_at: string;
  updated_at: string;
}

export interface SheetConfigItem {
  sheetNamePattern: string;
  sheetType: 'CALL' | 'RECORDING' | 'CRBT' | 'FLASH_MSG';
  phoneColumn: number;
  extensionColumn?: number | null;
  skipRows: number;
  isQuarterly: boolean;
  columns: { index: number; field: string; type: string }[];
  computedFields?: Record<string, string[]>;
}

export const SHEET_TYPE_LABELS: Record<string, string> = {
  CALL: '按号码费用',
  RECORDING: '录音费',
  CRBT: '彩铃费',
  FLASH_MSG: '闪信费',
};

export const OPERATOR_LABELS: Record<string, string> = {
  CHINA_TELECOM: '中国电信',
  CHINA_MOBILE: '中国移动',
  CHINA_UNICOM: '中国联通',
};
