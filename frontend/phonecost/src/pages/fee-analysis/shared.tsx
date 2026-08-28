import { useState } from 'react';
import { Row, Col, Tooltip, Tag } from 'antd';
import { COLORS } from '../../theme/morandi';
import { useTranslation } from 'react-i18next';
export type { Organization } from '../../types/organization';

// ============ Types ============

export interface FeeRow {
  org_id?: number;
  org_name?: string;
  org_type?: number;
  code?: string;
  cost_center?: string;
  monthly_rent?: number;
  call_fee?: number;
  recording_fee?: number;
  crbt_fee?: number;
  flash_msg_fee?: number;
  total_fee?: number;
  phone_count?: number;
  sub_org_count?: number;
  billing_month?: string;
  phone_number?: string;
  ownership_source?: string;
  detail_count?: number;
}

export interface PhoneAnalysisResult {
  phone_number: string;
  org_name: string;
  ownership_source: string;
  month_count: number;
  total_fee: number;
  avg_monthly_fee: number;
  mom_change: string | null;
  rows: FeeRow[];
}

export interface PhoneListRow {
  phone_number: string;
  org_name: string;
  ownership_source: string;
  total_fee: number;
  monthly_rent: number;
  call_fee: number;
  recording_fee: number;
  crbt_fee: number;
  flash_msg_fee: number;
  month_count: number;
  detail_count: number;
}

export interface L1MonthlyRow {
  billing_month: string;
  total_fee: number;
  monthly_rent: number;
  call_fee: number;
  recording_fee: number;
  crbt_fee: number;
  flash_msg_fee: number;
  phone_count: number;
  sub_org_count: number;
  last_year_fee: number | null;
  last_year_month: string | null;
  yoy_change: string | null;
}

export interface L1MonthlyResult {
  org_id: number;
  org_name: string;
  month_count: number;
  total_fee: number;
  avg_monthly_fee: number;
  rows: L1MonthlyRow[];
}

export interface BarRow {
  billing_month: string;
  total_fee: number;
  monthly_rent: number;
  call_fee: number;
  recording_fee: number;
  crbt_fee: number;
  flash_msg_fee: number;
  phone_count?: number;
  org_count?: number;
}

export type Dimension = 'all' | 'l1' | 'l2' | 'dept' | 'phone';

export type BarField = 'total_fee' | 'monthly_rent' | 'call_fee' | 'recording_fee' | 'crbt_fee' | 'flash_msg_fee';

// ============ Constants ============

export const FEE_BAR_COLORS: Record<string, string> = {
  total_fee: COLORS.sage,
  monthly_rent: COLORS.taupe,
  call_fee: COLORS.slate,
  recording_fee: COLORS.mauve,
  crbt_fee: COLORS.confirmed,
  flash_msg_fee: COLORS.pending,
};

export const DIMENSION_OPTIONS: { value: Dimension; labelKey: string }[] = [
  { value: 'all', labelKey: 'feeAnalysis.allDimension' },
  { value: 'l1', labelKey: 'feeAnalysis.l1Branch' },
  { value: 'l2', labelKey: 'feeAnalysis.l2Branch' },
  { value: 'dept', labelKey: 'feeAnalysis.deptDimension' },
  { value: 'phone', labelKey: 'feeAnalysis.phoneDimension' },
];

const BAR_FIELDS: BarField[] = ['total_fee', 'monthly_rent', 'call_fee', 'recording_fee', 'crbt_fee', 'flash_msg_fee'];

// ============ Utility Functions ============

export const money = (v: unknown) => {
  const n = Number(v);
  return !isNaN(n) && n !== 0 ? `¥${n.toFixed(2)}` : '-';
};

export const moneyWan = (v: number, wanUnit = '万') => {
  if (!v || v === 0) return '¥0';
  if (v >= 10000) return `¥${(v / 10000).toFixed(2)}${wanUnit}`;
  return `¥${v.toFixed(2)}`;
};

export function fillMonths12<T extends { billing_month: string }>(data: T[], emptyRow: (month: string) => T): T[] {
  const year = (data.length > 0 && data[0].billing_month?.length >= 4)
    ? data[0].billing_month.slice(0, 4)
    : String(new Date().getFullYear());
  const map = new Map(data.map(d => [d.billing_month.slice(5), d]));
  const result: T[] = [];
  for (let m = 1; m <= 12; m++) {
    const mm = String(m).padStart(2, '0');
    const monthKey = `${year}-${mm}`;
    result.push(map.has(mm) ? map.get(mm)! : emptyRow(monthKey));
  }
  return result;
}

export const emptyFeeRow = (billing_month: string): FeeRow => ({
  billing_month,
  total_fee: 0, monthly_rent: 0, call_fee: 0,
  recording_fee: 0, crbt_fee: 0, flash_msg_fee: 0,
  phone_count: 0, sub_org_count: 0,
});

export const emptyBarRow = (billing_month: string): BarRow => ({
  billing_month,
  total_fee: 0, monthly_rent: 0, call_fee: 0,
  recording_fee: 0, crbt_fee: 0, flash_msg_fee: 0,
});

export const emptyL1MonthlyRow = (billing_month: string): L1MonthlyRow => ({
  billing_month,
  total_fee: 0, monthly_rent: 0, call_fee: 0,
  recording_fee: 0, crbt_fee: 0, flash_msg_fee: 0,
  phone_count: 0, sub_org_count: 0,
  last_year_fee: null, last_year_month: null, yoy_change: null,
});

// ============ Helper Functions ============

export function getBarFieldLabel(field: BarField, t: (key: string) => string): string {
  const map: Record<BarField, string> = {
    total_fee: t('feeAnalysis.totalFee'),
    monthly_rent: t('feeAnalysis.monthlyRent'),
    call_fee: t('feeAnalysis.callFee'),
    recording_fee: t('feeAnalysis.recordingFee'),
    crbt_fee: t('feeAnalysis.crbtFee'),
    flash_msg_fee: t('feeAnalysis.flashMsgFee'),
  };
  return map[field];
}

export function getOrgTypeLabel(type: number, t: (key: string) => string): string {
  const map: Record<number, string> = {
    1: t('feeAnalysis.orgTypeGroup'),
    2: t('feeAnalysis.orgTypeL1'),
    3: t('feeAnalysis.orgTypeL2'),
    4: t('feeAnalysis.orgTypeDept'),
    5: t('feeAnalysis.orgTypeComp'),
    6: t('feeAnalysis.orgTypeSpec'),
  };
  return map[type] || String(type);
}

// ============ Shared Components ============

function MetricSelector({ activeField, onChange }: { activeField: BarField; onChange: (f: BarField) => void }) {
  const { t } = useTranslation();
  return (
    <Row gutter={8} style={{ marginBottom: 16 }}>
      {BAR_FIELDS.map(f => (
        <Col key={f}>
          <span
            onClick={() => onChange(f)}
            style={{
              display: 'inline-block', padding: '2px 10px', fontSize: 12, cursor: 'pointer', borderRadius: 3,
              border: `1px solid ${activeField === f ? FEE_BAR_COLORS[f] : COLORS.border}`,
              background: activeField === f ? FEE_BAR_COLORS[f] : 'transparent',
              color: activeField === f ? '#fff' : COLORS.textDark,
              transition: 'all 0.2s',
            }}
          >
            {getBarFieldLabel(f, t)}
          </span>
        </Col>
      ))}
    </Row>
  );
}

export function BarChart({ data, field, height = 190 }: { data: FeeRow[]; field?: BarField; height?: number }) {
  const { t } = useTranslation();
  const [activeField, setActiveField] = useState<BarField>(field || 'total_fee');
  const wanUnit = t('feeAnalysis.wanUnit');
  const fullData = fillMonths12(data, emptyFeeRow);
  const values = fullData.map(d => Number(d[activeField]) || 0);
  const maxVal = Math.max(...values, 1);

  const changes = fullData.map((d, i) => {
    if (i === 0) return null;
    const prev = Number(fullData[i - 1][activeField]) || 0;
    const cur = Number(d[activeField]) || 0;
    if (prev === 0) return null;
    return ((cur - prev) / prev * 100).toFixed(1);
  });

  const chartHeight = height;

  return (
    <div>
      <Row gutter={16} align="middle" style={{ marginBottom: 8 }}>
        <Col flex="auto"><MetricSelector activeField={activeField} onChange={setActiveField} /></Col>
      </Row>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, height: chartHeight, padding: '0 8px 28px', borderBottom: `1px solid ${COLORS.border}` }}>
        {fullData.map((d, i) => {
          const val = Number(d[activeField]) || 0;
          const change = changes[i];
          const isUp = change !== null && Number(change) > 0;
          const isDown = change !== null && Number(change) < 0;
          const LABEL_RESERVE = 55;
          const availableHeight = chartHeight - 28 - LABEL_RESERVE;
          const barHeight = Math.max(availableHeight * (val / maxVal) * 0.8, val > 0 ? 2 : 0);
          return (
            <div key={d.billing_month} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 30 }}>
              <div style={{ fontSize: 10, color: COLORS.textDark, marginBottom: 2, whiteSpace: 'nowrap', fontWeight: 500 }}>{val > 0 ? moneyWan(val, wanUnit) : ''}</div>
              <Tooltip title={val > 0 ? `${d.billing_month} ${getBarFieldLabel(activeField, t)}: ${money(val)}` : `${d.billing_month} ${t('feeAnalysis.noData')}`}>
                <div style={{ width: '100%', maxWidth: 40, height: barHeight + 'px', background: val > 0 ? FEE_BAR_COLORS[activeField] : 'transparent', borderRadius: '3px 3px 0 0', transition: 'height 0.4s ease' }} />
              </Tooltip>
              <div style={{ fontSize: 10, color: COLORS.textMuted, marginTop: 4, whiteSpace: 'nowrap' }}>{d.billing_month?.slice(5) || ''}</div>
              {change !== null && val > 0 && (
                <div style={{ fontSize: 9, color: isUp ? COLORS.danger : isDown ? COLORS.confirmed : COLORS.textMuted, whiteSpace: 'nowrap' }}>
                  {isUp ? '+' : ''}{change}%
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

export function YoyBarChart({ data, height = 200 }: { data: L1MonthlyRow[]; height?: number }) {
  const { t } = useTranslation();
  const [activeField, setActiveField] = useState<BarField>('total_fee');
  const wanUnit = t('feeAnalysis.wanUnit');

  const fullData = fillMonths12(data, emptyL1MonthlyRow);

  const maxVal = Math.max(
    ...fullData.map(d => Number(d[activeField]) || 0),
    ...fullData.map(d => Number(d.last_year_fee) || 0),
    1,
  );

  const chartHeight = height;

  return (
    <div>
      <Row gutter={16} align="middle" style={{ marginBottom: 8 }}>
        <Col flex="auto"><MetricSelector activeField={activeField} onChange={setActiveField} /></Col>
      </Row>
      <div style={{ display: 'flex', gap: 24, marginBottom: 8, fontSize: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 14, height: 10, background: COLORS.sage, borderRadius: 2 }} />
          <span style={{ color: COLORS.textMuted }}>{t('feeAnalysis.thisYear')}</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 14, height: 10, background: COLORS.border, borderRadius: 2, border: `1px dashed ${COLORS.textMuted}` }} />
          <span style={{ color: COLORS.textMuted }}>{t('feeAnalysis.lastYearLabel')}</span>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, height: chartHeight, padding: '0 8px 28px', borderBottom: `1px solid ${COLORS.border}` }}>
        {fullData.map((d) => {
          const curVal = Number(d[activeField]) || 0;
          const prevVal = Number(d.last_year_fee) || 0;
          const yoyChange = d.yoy_change;
          const isUp = yoyChange !== null && Number(yoyChange) > 0;
          const isDown = yoyChange !== null && Number(yoyChange) < 0;
          const monthLabel = d.billing_month?.slice(5) || '';
          const hasData = curVal > 0 || prevVal > 0;

          const LABEL_RESERVE = 40;
          const availableHeight = chartHeight - 28 - LABEL_RESERVE;
          const tallerVal = Math.max(curVal, prevVal);
          const dualBarHeight = hasData ? Math.max(availableHeight * (tallerVal / maxVal) * 0.8, 2) : 0;
          const curBarPct = tallerVal > 0 ? (curVal / tallerVal) * 100 : 0;
          const prevBarPct = tallerVal > 0 ? (prevVal / tallerVal) * 100 : 0;

          return (
            <div key={d.billing_month} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 36 }}>
              <div style={{ fontSize: 9, marginBottom: 2, whiteSpace: 'nowrap' }}>
                {hasData && <span style={{ color: COLORS.textDark, fontWeight: 500 }}>{moneyWan(curVal, wanUnit)}</span>}
                {yoyChange !== null && hasData && (
                  <span style={{ marginLeft: 4, color: isUp ? COLORS.danger : isDown ? COLORS.confirmed : COLORS.textMuted }}>
                    {isUp ? '+' : ''}{yoyChange}%
                  </span>
                )}
              </div>
              <div style={{ display: 'flex', gap: 2, width: '100%', maxWidth: 44, alignItems: 'flex-end', height: dualBarHeight + 'px', flex: '0 0 auto' }}>
                <Tooltip title={prevVal > 0 ? `${d.last_year_month}: ${money(prevVal)}` : hasData ? t('feeAnalysis.noLastYearData') : `${d.billing_month} ${t('feeAnalysis.noData')}`}>
                  <div style={{
                    flex: 1, minHeight: prevVal > 0 ? 2 : 0,
                    height: prevVal > 0 ? prevBarPct + '%' : '0%',
                    background: 'transparent',
                    border: prevVal > 0 ? `1.5px dashed ${COLORS.textMuted}` : 'none',
                    borderRadius: '2px 2px 0 0',
                    opacity: prevVal > 0 ? 0.6 : 0,
                  }} />
                </Tooltip>
                <Tooltip title={curVal > 0 ? `${d.billing_month}: ${money(curVal)}` : hasData ? t('feeAnalysis.noData') : `${d.billing_month} ${t('feeAnalysis.noData')}`}>
                  <div style={{
                    flex: 1, minHeight: curVal > 0 ? 2 : 0,
                    height: curVal > 0 ? curBarPct + '%' : '0%',
                    background: curVal > 0 ? FEE_BAR_COLORS[activeField] : 'transparent',
                    borderRadius: '2px 2px 0 0',
                    transition: 'height 0.4s ease',
                  }} />
                </Tooltip>
              </div>
              <div style={{ fontSize: 10, color: COLORS.textMuted, marginTop: 4, whiteSpace: 'nowrap' }}>{monthLabel}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ============ Shared Column Definitions ============

export function createL1DetailColumns(t: (key: string) => string) {
  return [
    { title: t('feeAnalysis.monthCol'), dataIndex: 'billing_month', key: 'billing_month', width: 100, fixed: 'left' as const },
    { title: t('feeAnalysis.totalCol'), dataIndex: 'total_fee', key: 'total_fee', width: 120, render: (v: number) => <strong>{money(v)}</strong> },
    { title: t('feeAnalysis.monthlyRent'), dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money },
    { title: t('feeAnalysis.callFee'), dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money },
    { title: t('feeAnalysis.recordingFee'), dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money },
    { title: t('feeAnalysis.crbtFee'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money },
    { title: t('feeAnalysis.flashMsgFee'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money },
    { title: t('feeAnalysis.lastYearFee'), dataIndex: 'last_year_fee', key: 'last_year_fee', width: 120, render: (v: number | null) => v ? money(v) : <span style={{ color: COLORS.textMuted }}>-</span> },
    { title: t('feeAnalysis.yoyChange'), dataIndex: 'yoy_change', key: 'yoy_change', width: 100,
      render: (v: string | null) => {
        if (v === null) return <span style={{ color: COLORS.textMuted }}>-</span>;
        const num = Number(v);
        const color = num > 0 ? COLORS.danger : num < 0 ? COLORS.confirmed : COLORS.textMuted;
        return <span style={{ color, fontWeight: 500 }}>{num > 0 ? '+' : ''}{v}%</span>;
      }},
    { title: t('feeAnalysis.phoneCount'), dataIndex: 'phone_count', key: 'phone_count', width: 80 },
    { title: t('feeAnalysis.subOrgCount'), dataIndex: 'sub_org_count', key: 'sub_org_count', width: 80 },
  ];
}

export function renderOwnershipSourceTag(v: string, t: (key: string) => string) {
  const map: Record<string, { labelKey: string; color: string }> = {
    P0: { labelKey: 'feeAnalysis.p0Exception', color: COLORS.danger },
    P1: { labelKey: 'feeAnalysis.p1Directory', color: COLORS.confirmed },
    P2: { labelKey: 'feeAnalysis.p2Ownership', color: COLORS.slate },
    P3: { labelKey: 'feeAnalysis.p3Unassigned', color: COLORS.textMuted },
  };
  const info = map[v];
  return info ? <Tag color={info.color}>{t(info.labelKey)}</Tag> : (v || '-');
}
