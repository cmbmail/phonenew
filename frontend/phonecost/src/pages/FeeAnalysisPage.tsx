import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Select, Table, Statistic, Row, Col, Input, Segmented, Empty, Tag, Tooltip, Button } from 'antd';
import { BarChartOutlined } from '@ant-design/icons';
import { COLORS } from '../theme/morandi';
import { apiGet } from '../lib/request';
import { getBillBatches } from '../api/import';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import type { BillBatch } from '../types/bill';

interface FeeRow {
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

interface PhoneAnalysisResult {
  phone_number: string;
  org_name: string;
  ownership_source: string;
  month_count: number;
  total_fee: number;
  avg_monthly_fee: number;
  mom_change: string | null;
  rows: FeeRow[];
}

interface PhoneListRow {
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

interface L1MonthlyRow {
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

interface L1MonthlyResult {
  org_id: number;
  org_name: string;
  month_count: number;
  total_fee: number;
  avg_monthly_fee: number;
  rows: L1MonthlyRow[];
}

type Dimension = '全部' | '一级分行' | '二级分行' | '部门' | '单个号码';

const DIM_MAP: Record<Dimension, string> = {
  '全部': 'ALL', '一级分行': 'L1', '二级分行': 'L2', '部门': 'DEPARTMENT', '单个号码': 'PHONE',
};

const ORG_TYPE_LABEL: Record<number, string> = { 1: '集团', 2: '一级分行', 3: '二级分行', 4: '部门', 5: '综合支行', 6: '零专支行' };

const money = (v: unknown) => {
  const n = Number(v);
  return !isNaN(n) && n !== 0 ? `¥${n.toFixed(2)}` : '-';
};

const moneyWan = (v: number) => {
  if (!v || v === 0) return '¥0';
  if (v >= 10000) return `¥${(v / 10000).toFixed(2)}万`;
  return `¥${v.toFixed(2)}`;
};

// ==================== 纯CSS柱状图 ====================

interface BarRow {
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

const FEE_BAR_COLORS: Record<string, string> = {
  total_fee: COLORS.sage,
  monthly_rent: COLORS.taupe,
  call_fee: COLORS.slate,
  recording_fee: COLORS.mauve,
  crbt_fee: COLORS.confirmed,
  flash_msg_fee: COLORS.pending,
};

type BarField = 'total_fee' | 'monthly_rent' | 'call_fee' | 'recording_fee' | 'crbt_fee' | 'flash_msg_fee';

const BAR_FIELD_LABELS: Record<BarField, string> = {
  total_fee: '总费用',
  monthly_rent: '月租费',
  call_fee: '通话费',
  recording_fee: '录音费',
  crbt_fee: '彩铃费',
  flash_msg_fee: '闪信费',
};

// 补全1-12月，缺失月份填0
function fillMonths12<T extends { billing_month: string }>(data: T[]): T[] {
  // Derive year from the first available data point; fallback to current year
  const year = (data.length > 0 && data[0].billing_month?.length >= 4)
    ? data[0].billing_month.slice(0, 4)
    : String(new Date().getFullYear());
  const map = new Map(data.map(d => [d.billing_month.slice(5), d]));
  const result: T[] = [];
  for (let m = 1; m <= 12; m++) {
    const mm = String(m).padStart(2, '0');
    if (map.has(mm)) {
      result.push(map.get(mm)!);
    } else {
      // 构造一个全0的占位行
      const placeholder = { billing_month: `${year}-${mm}` } as unknown as T;
      result.push(placeholder);
    }
  }
  return result;
}

// 指标选择器（共用）
function MetricSelector({ activeField, onChange }: { activeField: BarField; onChange: (f: BarField) => void }) {
  return (
    <Row gutter={8} style={{ marginBottom: 16 }}>
      {(Object.keys(BAR_FIELD_LABELS) as BarField[]).map(f => (
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
            {BAR_FIELD_LABELS[f]}
          </span>
        </Col>
      ))}
    </Row>
  );
}

// 单指标柱状图（总费用对比、单个号码用）
function BarChart({ data, field, height = 190 }: { data: BarRow[]; field?: BarField; height?: number }) {
  const [activeField, setActiveField] = useState<BarField>(field || 'total_fee');
  const fullData = fillMonths12(data);
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
          // 像素高度计算：预留标签空间，最高柱达可用高度80%
          const LABEL_RESERVE = 55;
          const availableHeight = chartHeight - 28 - LABEL_RESERVE;
          const barHeight = Math.max(availableHeight * (val / maxVal) * 0.8, val > 0 ? 2 : 0);
          return (
            <div key={d.billing_month} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 30 }}>
              <div style={{ fontSize: 10, color: COLORS.textDark, marginBottom: 2, whiteSpace: 'nowrap', fontWeight: 500 }}>{val > 0 ? moneyWan(val) : ''}</div>
              <Tooltip title={val > 0 ? `${d.billing_month} ${BAR_FIELD_LABELS[activeField]}: ${money(val)}` : `${d.billing_month} 无数据`}>
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

// YoY双柱对比图（今年 vs 去年同期）
function YoyBarChart({ data, height = 200 }: { data: L1MonthlyRow[]; height?: number }) {
  const [activeField, setActiveField] = useState<BarField>('total_fee');

  const fullData = fillMonths12(data);

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
      {/* 图例 */}
      <div style={{ display: 'flex', gap: 24, marginBottom: 8, fontSize: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 14, height: 10, background: COLORS.sage, borderRadius: 2 }} />
          <span style={{ color: COLORS.textMuted }}>本年</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 14, height: 10, background: COLORS.border, borderRadius: 2, border: `1px dashed ${COLORS.textMuted}` }} />
          <span style={{ color: COLORS.textMuted }}>去年同期</span>
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

          // 像素高度计算：预留标签空间，最高柱达可用高度80%
          const LABEL_RESERVE = 40;
          const availableHeight = chartHeight - 28 - LABEL_RESERVE;
          const tallerVal = Math.max(curVal, prevVal);
          const dualBarHeight = hasData ? Math.max(availableHeight * (tallerVal / maxVal) * 0.8, 2) : 0;
          const curBarPct = tallerVal > 0 ? (curVal / tallerVal) * 100 : 0;
          const prevBarPct = tallerVal > 0 ? (prevVal / tallerVal) * 100 : 0;

          return (
            <div key={d.billing_month} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 36 }}>
              {/* 数值 + 同比 */}
              <div style={{ fontSize: 9, marginBottom: 2, whiteSpace: 'nowrap' }}>
                {hasData && <span style={{ color: COLORS.textDark, fontWeight: 500 }}>{moneyWan(curVal)}</span>}
                {yoyChange !== null && hasData && (
                  <span style={{ marginLeft: 4, color: isUp ? COLORS.danger : isDown ? COLORS.confirmed : COLORS.textMuted }}>
                    {isUp ? '+' : ''}{yoyChange}%
                  </span>
                )}
              </div>
              {/* 双柱容器 — 固定像素高度 */}
              <div style={{ display: 'flex', gap: 2, width: '100%', maxWidth: 44, alignItems: 'flex-end', height: dualBarHeight + 'px', flex: '0 0 auto' }}>
                {/* 去年柱 */}
                <Tooltip title={prevVal > 0 ? `${d.last_year_month}: ${money(prevVal)}` : hasData ? '无去年同期数据' : `${d.billing_month} 无数据`}>
                  <div style={{
                    flex: 1, minHeight: prevVal > 0 ? 2 : 0,
                    height: prevVal > 0 ? prevBarPct + '%' : '0%',
                    background: 'transparent',
                    border: prevVal > 0 ? `1.5px dashed ${COLORS.textMuted}` : 'none',
                    borderRadius: '2px 2px 0 0',
                    opacity: prevVal > 0 ? 0.6 : 0,
                  }} />
                </Tooltip>
                {/* 今年柱 */}
                <Tooltip title={curVal > 0 ? `${d.billing_month}: ${money(curVal)}` : hasData ? '无数据' : `${d.billing_month} 无数据`}>
                  <div style={{
                    flex: 1, minHeight: curVal > 0 ? 2 : 0,
                    height: curVal > 0 ? curBarPct + '%' : '0%',
                    background: curVal > 0 ? FEE_BAR_COLORS[activeField] : 'transparent',
                    borderRadius: '2px 2px 0 0',
                    transition: 'height 0.4s ease',
                  }} />
                </Tooltip>
              </div>
              {/* 月份 */}
              <div style={{ fontSize: 10, color: COLORS.textMuted, marginTop: 4, whiteSpace: 'nowrap' }}>{monthLabel}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ==================== 主页面 ====================

export default function FeeAnalysisPage() {
  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [dimension, setDimension] = useState<Dimension>('全部');

  // Phone analysis data
  const [phoneData, setPhoneData] = useState<PhoneAnalysisResult | null>(null);

  // ALL dimension monthly data
  const [allMonthlyData, setAllMonthlyData] = useState<BarRow[]>([]);
  const [allMonthlyLoading, setAllMonthlyLoading] = useState(false);

  // L1 monthly analysis data
  const [l1MonthlyData, setL1MonthlyData] = useState<L1MonthlyResult | null>(null);
  const [l1MonthlyLoading, setL1MonthlyLoading] = useState(false);
  const [selectedL1OrgId, setSelectedL1OrgId] = useState<number | null>(null);

  // L2 monthly analysis data
  const [l2MonthlyData, setL2MonthlyData] = useState<L1MonthlyResult | null>(null);
  const [l2MonthlyLoading, setL2MonthlyLoading] = useState(false);
  const [selectedL2OrgId, setSelectedL2OrgId] = useState<number | null>(null);

  // Department monthly analysis data
  const [deptMonthlyData, setDeptMonthlyData] = useState<L1MonthlyResult | null>(null);
  const [deptMonthlyLoading, setDeptMonthlyLoading] = useState(false);
  const [selectedDeptOrgId, setSelectedDeptOrgId] = useState<number | null>(null);

  // Org list
  const [orgList, setOrgList] = useState<Organization[]>([]);

  // Phone list (default view)
  const [phoneList, setPhoneList] = useState<PhoneListRow[]>([]);
  const [phoneListLoading, setPhoneListLoading] = useState(false);
  const [phoneSearch, setPhoneSearch] = useState('');
  const [phoneListL1OrgId, setPhoneListL1OrgId] = useState<number | null>(null);

  const fetchBatches = useCallback(async () => {
    try { setBatches(await getBillBatches()); } catch { /* */ }
  }, []);

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { /* */ }
  }, []);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  // Fetch ALL dimension monthly data
  useEffect(() => {
    if (dimension !== '全部') return;
    setAllMonthlyLoading(true);
    apiGet<BarRow[]>('/allocation/analysis/monthly-comparison')
      .then(data => setAllMonthlyData(data || []))
      .catch(() => setAllMonthlyData([]))
      .finally(() => setAllMonthlyLoading(false));
  }, [dimension]);

  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  // Trigger L1 fetch on org selection
  useEffect(() => {
    if (dimension === '一级分行' && selectedL1OrgId) {
      setL1MonthlyLoading(true);
      apiGet<L1MonthlyResult>(`/allocation/analysis/l1-monthly?orgId=${selectedL1OrgId}`)
        .then(setL1MonthlyData).catch(() => setL1MonthlyData(null))
        .finally(() => setL1MonthlyLoading(false));
    }
  }, [dimension, selectedL1OrgId]);

  // L2 monthly fetch
  useEffect(() => {
    if (dimension === '二级分行' && selectedL2OrgId) {
      setL2MonthlyLoading(true);
      apiGet<L1MonthlyResult>(`/allocation/analysis/l2-monthly?orgId=${selectedL2OrgId}`)
        .then(setL2MonthlyData).catch(() => setL2MonthlyData(null))
        .finally(() => setL2MonthlyLoading(false));
    }
  }, [dimension, selectedL2OrgId]);

  // Department monthly fetch
  useEffect(() => {
    if (dimension === '部门' && selectedDeptOrgId) {
      setDeptMonthlyLoading(true);
      apiGet<L1MonthlyResult>(`/allocation/analysis/dept-monthly?orgId=${selectedDeptOrgId}`)
        .then(setDeptMonthlyData).catch(() => setDeptMonthlyData(null))
        .finally(() => setDeptMonthlyLoading(false));
    }
  }, [dimension, selectedDeptOrgId]);

  // Phone number click — fetch detail
  const selectPhone = useCallback(async (phone: string) => {
    try {
      const data = await apiGet<PhoneAnalysisResult>(`/allocation/analysis?batchId=${selectedBatchId || 0}&dimension=PHONE&phoneNumber=${phone}`);
      setPhoneData(data);
    } catch {
      setPhoneData(null);
    }
  }, [selectedBatchId]);

  // Phone list fetch (default view when no search)
  useEffect(() => {
    if (dimension !== '单个号码') return;
    setPhoneListLoading(true);
    const url = phoneListL1OrgId
      ? `/allocation/analysis/phone-list?orgId=${phoneListL1OrgId}`
      : '/allocation/analysis/phone-list';
    apiGet<{ total_count: number; rows: PhoneListRow[] }>(url)
      .then(data => setPhoneList(data.rows || []))
      .catch(() => setPhoneList([]))
      .finally(() => setPhoneListLoading(false));
  }, [dimension, phoneListL1OrgId]);

  // Org dropdowns
  const l1Orgs = useMemo(() => orgList.filter(o => o.type === 2), [orgList]);

  // L2 orgs under selected L1
  const l2Orgs = useMemo(() => {
    if (!selectedL1OrgId) return [];
    const l1 = orgList.find(o => o.id === selectedL1OrgId);
    if (!l1?.path) return [];
    return orgList.filter(o => o.type === 3 && o.path?.startsWith(l1.path) && o.id !== l1.id);
  }, [orgList, selectedL1OrgId]);

  // Department org options (all non-root orgs)
  const deptOrgOptions = useMemo(() => {
    return orgList
      .filter(o => o.type !== 1) // exclude 集团
      .map(o => ({ label: `${o.name} (${ORG_TYPE_LABEL[o.type] || o.type})`, value: o.id }));
  }, [orgList]);

  // ==================== 一级分行分析 ====================

  const l1DetailColumns = [
    { title: '月份', dataIndex: 'billing_month', key: 'billing_month', width: 100, fixed: 'left' as const },
    { title: '合计', dataIndex: 'total_fee', key: 'total_fee', width: 120, render: (v: number) => <strong>{money(v)}</strong> },
    { title: '月租费', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money },
    { title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money },
    { title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money },
    { title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money },
    { title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money },
    { title: '去年同期', dataIndex: 'last_year_fee', key: 'last_year_fee', width: 120, render: (v: number | null) => v ? money(v) : <span style={{ color: COLORS.textMuted }}>-</span> },
    { title: '同比变化', dataIndex: 'yoy_change', key: 'yoy_change', width: 100,
      render: (v: string | null) => {
        if (v === null) return <span style={{ color: COLORS.textMuted }}>-</span>;
        const num = Number(v);
        const color = num > 0 ? COLORS.danger : num < 0 ? COLORS.confirmed : COLORS.textMuted;
        return <span style={{ color, fontWeight: 500 }}>{num > 0 ? '+' : ''}{v}%</span>;
      }},
    { title: '号码数', dataIndex: 'phone_count', key: 'phone_count', width: 80 },
    { title: '下级组织', dataIndex: 'sub_org_count', key: 'sub_org_count', width: 80 },
  ];

  const renderL1Content = () => (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col>
          <span style={{ marginRight: 8 }}>选择一级分行：</span>
          <Select style={{ width: 260 }} placeholder="选择一级分行" value={selectedL1OrgId} onChange={setSelectedL1OrgId}
            showSearch optionFilterProp="label"
            options={l1Orgs.map(o => ({ label: o.name, value: o.id }))} />
        </Col>
      </Row>

      {l1MonthlyLoading && <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>加载中...</div>}

      {l1MonthlyData && l1MonthlyData.rows && l1MonthlyData.rows.length > 0 ? (
        <>
          {/* 汇总卡片 */}
          <Row gutter={16} style={{ marginBottom: 20 }}>
            <Col span={5}>
              <Statistic title="一级分行" value={l1MonthlyData.org_name} valueStyle={{ fontSize: 18, color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title="累计费用" value={Number(l1MonthlyData.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title="月均费用" value={Number(l1MonthlyData.avg_monthly_fee || 0).toFixed(2)} prefix="¥" />
            </Col>
            <Col span={3}>
              <Statistic title="数据月数" value={l1MonthlyData.month_count} suffix="个月" />
            </Col>
          </Row>

          {/* YoY双柱对比图 */}
          <Card size="small" title="月度费用同比对比" style={{ marginBottom: 16 }}>
            <YoyBarChart data={l1MonthlyData.rows} />
          </Card>

          {/* 数据表 */}
          <Card size="small" title="月度费用明细">
            <Table columns={l1DetailColumns} dataSource={l1MonthlyData.rows} rowKey="billing_month" size="small"
              pagination={false} scroll={{ x: 1100 }} />
          </Card>
        </>
      ) : selectedL1OrgId && !l1MonthlyLoading ? (
        <Empty description="该分行暂无费用数据" />
      ) : !selectedL1OrgId ? (
        <Empty description="请选择一级分行" />
      ) : null}
    </>
  );

  // ==================== 单个号码分析 ====================

  const phoneDetailColumns = [
    { title: '月份', dataIndex: 'billing_month', key: 'billing_month', width: 100, fixed: 'left' as const },
    { title: '月租费', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money },
    { title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money },
    { title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money },
    { title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money },
    { title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money },
    { title: '合计', dataIndex: 'total_fee', key: 'total_fee', width: 110, render: (v: number) => <strong>{money(v)}</strong> },
    { title: '归属组织', dataIndex: 'org_name', key: 'org_name', width: 140 },
    { title: '归属来源', dataIndex: 'ownership_source', key: 'ownership_source', width: 100,
      render: (v: string) => {
        const map: Record<string, { label: string; color: string }> = {
          P0: { label: 'P0例外', color: COLORS.danger }, P1: { label: 'P1通讯录', color: COLORS.confirmed },
          P2: { label: 'P2归属表', color: COLORS.slate }, P3: { label: 'P3未归属', color: COLORS.textMuted },
        };
        const info = map[v];
        return info ? <Tag color={info.color}>{info.label}</Tag> : (v || '-');
      }},
    { title: '账单条数', dataIndex: 'detail_count', key: 'detail_count', width: 80 },
  ];

  // Filtered phone list based on search (comma-separated)
  const filteredPhoneList = useMemo(() => {
    const terms = phoneSearch.split(/[,，]/).map(s => s.trim()).filter(Boolean);
    if (terms.length === 0) return phoneList;
    return phoneList.filter(r => terms.some(t => (r.phone_number || '').includes(t)));
  }, [phoneList, phoneSearch]);

  const phoneListColumns = [
    { title: '号码', dataIndex: 'phone_number', key: 'phone_number', width: 140, fixed: 'left' as const,
      render: (v: string) => <a onClick={() => selectPhone(v)} style={{ color: COLORS.sage, cursor: 'pointer' }}>{v}</a> },
    { title: '归属组织', dataIndex: 'org_name', key: 'org_name', width: 140,
      render: (v: string) => v || <span style={{ color: COLORS.textMuted }}>未归属</span> },
    { title: '归属来源', dataIndex: 'ownership_source', key: 'ownership_source', width: 90,
      render: (v: string) => {
        const map: Record<string, { label: string; color: string }> = {
          P0: { label: 'P0例外', color: COLORS.danger }, P1: { label: 'P1通讯录', color: COLORS.confirmed },
          P2: { label: 'P2归属表', color: COLORS.slate }, P3: { label: 'P3未归属', color: COLORS.textMuted },
        };
        const info = map[v];
        return info ? <Tag color={info.color}>{info.label}</Tag> : (v || '-');
      }},
    { title: '累计费用', dataIndex: 'total_fee', key: 'total_fee', width: 120, render: (v: number) => <strong>{money(v)}</strong>,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.total_fee || 0) - (b.total_fee || 0), defaultSortOrder: 'descend' as const },
    { title: '月租费', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.monthly_rent || 0) - (b.monthly_rent || 0) },
    { title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.call_fee || 0) - (b.call_fee || 0) },
    { title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.recording_fee || 0) - (b.recording_fee || 0) },
    { title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.crbt_fee || 0) - (b.crbt_fee || 0) },
    { title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.flash_msg_fee || 0) - (b.flash_msg_fee || 0) },
    { title: '数据月数', dataIndex: 'month_count', key: 'month_count', width: 80,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.month_count || 0) - (b.month_count || 0) },
  ];

  const renderPhoneContent = () => (
    <>
      {phoneData && phoneData.rows && phoneData.rows.length > 0 ? (
        <>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col>
              <Button onClick={() => setPhoneData(null)} style={{ borderColor: COLORS.sage, color: COLORS.sage }}>← 返回号码列表</Button>
            </Col>
          </Row>
          <Row gutter={16} style={{ marginBottom: 20 }}>
            <Col span={4}><Statistic title="号码" value={String(phoneData.phone_number || '')} valueStyle={{ fontSize: 16, color: COLORS.sage, fontVariantNumeric: 'normal' }} groupSeparator="" /></Col>
            <Col span={4}><Statistic title="归属组织" value={phoneData.org_name || '未归属'} valueStyle={{ fontSize: 16 }} /></Col>
            <Col span={3}><Statistic title="累计费用" value={Number(phoneData.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} /></Col>
            <Col span={3}><Statistic title="月均费用" value={Number(phoneData.avg_monthly_fee || 0).toFixed(2)} prefix="¥" /></Col>
            <Col span={3}>
              <Statistic title="数据月数" value={phoneData.month_count} suffix="个月" />
              {phoneData.mom_change !== null && (
                <div style={{ fontSize: 12, color: Number(phoneData.mom_change) > 0 ? COLORS.danger : COLORS.confirmed }}>
                  环比 {Number(phoneData.mom_change) > 0 ? '+' : ''}{phoneData.mom_change}%
                </div>
              )}
            </Col>
          </Row>

          <Card size="small" title="月度费用趋势" style={{ marginBottom: 16 }}>
            <BarChart data={phoneData.rows as unknown as BarRow[]} />
          </Card>

          <Card size="small" title="费用清单">
            <Table columns={phoneDetailColumns} dataSource={phoneData.rows} rowKey="billing_month" size="small"
              pagination={false} scroll={{ x: 1100 }} />
          </Card>
        </>
      ) : (
        <>
          <Row gutter={16} style={{ marginBottom: 12 }} align="middle">
            <Col>
              <span style={{ marginRight: 8 }}>一级分行：</span>
              <Select
                style={{ width: 220 }}
                placeholder="全部分行"
                allowClear
                value={phoneListL1OrgId}
                onChange={setPhoneListL1OrgId}
                showSearch optionFilterProp="label"
                options={l1Orgs.map(o => ({ label: o.name, value: o.id }))}
              />
            </Col>
            <Col flex="auto" />
            <Col>
              <Input.Search
                placeholder="搜索号码，多个用逗号分隔"
                allowClear
                style={{ width: 300 }}
                value={phoneSearch}
                onChange={e => setPhoneSearch(e.target.value)}
                onSearch={v => setPhoneSearch(v)}
              />
            </Col>
          </Row>
          <Card size="small" title={`号码费用列表（共 ${filteredPhoneList.length} 个号码）`}>
            <Table
              columns={phoneListColumns}
              dataSource={filteredPhoneList}
              rowKey="phone_number"
              size="small"
              loading={phoneListLoading}
              pagination={{ pageSize: 50, showSizeChanger: true, showTotal: t => `共 ${t} 条` }}
              scroll={{ x: 1100 }}
            />
          </Card>
        </>
      )}
    </>
  );

  // ==================== 费用分析Tab ====================

  // ==================== 全部维度：月度趋势 ====================

  const allMonthlyColumns = [
    { title: '月份', dataIndex: 'billing_month', key: 'billing_month', width: 100, fixed: 'left' as const },
    { title: '总费用', dataIndex: 'total_fee', key: 'total_fee', width: 120, render: (v: number) => <strong>{money(v)}</strong>,
      sorter: (a: BarRow, b: BarRow) => (a.total_fee || 0) - (b.total_fee || 0) },
    { title: '月租费', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money },
    { title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money },
    { title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money },
    { title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money },
    { title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money },
    { title: '号码数', dataIndex: 'phone_count', key: 'phone_count', width: 80 },
    { title: '组织数', dataIndex: 'org_count', key: 'org_count', width: 80 },
  ];

  const renderAllContent = () => {
    if (allMonthlyLoading) return <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>加载中...</div>;

    const grandTotal = allMonthlyData.reduce((s, r) => s + (Number(r.total_fee) || 0), 0);
    const avgMonthly = allMonthlyData.length > 0 ? grandTotal / allMonthlyData.length : 0;
    const lastRow = allMonthlyData[allMonthlyData.length - 1];
    const phoneCount = lastRow?.phone_count || 0;
    const orgCount = lastRow?.org_count || 0;

    return (
      <>
        <Row gutter={16} style={{ marginBottom: 20 }}>
          <Col span={6}><Statistic title="累计总费用" value={grandTotal.toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} /></Col>
          <Col span={6}><Statistic title="月均费用" value={avgMonthly.toFixed(2)} prefix="¥" /></Col>
          <Col span={4}><Statistic title="号码数" value={phoneCount} /></Col>
          <Col span={4}><Statistic title="组织数" value={orgCount} /></Col>
          <Col span={4}><Statistic title="数据月数" value={allMonthlyData.length} suffix="个月" /></Col>
        </Row>

        <Card size="small" title="总费用趋势" style={{ marginBottom: 16 }}>
          <BarChart data={allMonthlyData} field="total_fee" />
        </Card>

        <Card size="small" title="月度费用明细">
          <Table columns={allMonthlyColumns} dataSource={allMonthlyData} rowKey="billing_month" size="small"
            pagination={false} scroll={{ x: 880 }} />
        </Card>
      </>
    );
  };

  const renderL2Content = () => (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col>
          <span style={{ marginRight: 8 }}>选择一级分行：</span>
          <Select style={{ width: 240 }} placeholder="选择一级分行" value={selectedL1OrgId} onChange={setSelectedL1OrgId}
            showSearch optionFilterProp="label"
            options={l1Orgs.map(o => ({ label: o.name, value: o.id }))} />
        </Col>
        {selectedL1OrgId && (
          <Col>
            <span style={{ marginRight: 8 }}>选择二级分行：</span>
            <Select style={{ width: 240 }} placeholder="选择二级分行" value={selectedL2OrgId} onChange={setSelectedL2OrgId}
              showSearch optionFilterProp="label"
              options={l2Orgs.map(o => ({ label: o.name, value: o.id }))} />
          </Col>
        )}
      </Row>

      {l2MonthlyLoading && <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>加载中...</div>}

      {l2MonthlyData && l2MonthlyData.rows && l2MonthlyData.rows.length > 0 ? (
        <>
          {/* 汇总卡片 */}
          <Row gutter={16} style={{ marginBottom: 20 }}>
            <Col span={5}>
              <Statistic title="二级分行" value={l2MonthlyData.org_name} valueStyle={{ fontSize: 18, color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title="累计费用" value={Number(l2MonthlyData.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title="月均费用" value={Number(l2MonthlyData.avg_monthly_fee || 0).toFixed(2)} prefix="¥" />
            </Col>
            <Col span={3}>
              <Statistic title="数据月数" value={l2MonthlyData.month_count} suffix="个月" />
            </Col>
          </Row>

          {/* YoY双柱对比图 */}
          <Card size="small" title="月度费用同比对比" style={{ marginBottom: 16 }}>
            <YoyBarChart data={l2MonthlyData.rows} />
          </Card>

          {/* 数据表 */}
          <Card size="small" title="月度费用明细">
            <Table columns={l1DetailColumns} dataSource={l2MonthlyData.rows} rowKey="billing_month" size="small"
              pagination={false} scroll={{ x: 1100 }} />
          </Card>
        </>
      ) : selectedL2OrgId && !l2MonthlyLoading ? (
        <Empty description="该分行暂无费用数据" />
      ) : !selectedL2OrgId ? (
        <Empty description={selectedL1OrgId ? '请选择二级分行' : '请先选择一级分行'} />
      ) : null}
    </>
  );

  const renderDeptContent = () => (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col>
          <span style={{ marginRight: 8 }}>选择部门：</span>
          <Select style={{ width: 320 }} placeholder="选择部门" showSearch optionFilterProp="label"
            value={selectedDeptOrgId} onChange={setSelectedDeptOrgId} options={deptOrgOptions} />
        </Col>
      </Row>

      {deptMonthlyLoading && <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>加载中...</div>}

      {deptMonthlyData && deptMonthlyData.rows && deptMonthlyData.rows.length > 0 ? (
        <>
          {/* 汇总卡片 */}
          <Row gutter={16} style={{ marginBottom: 20 }}>
            <Col span={5}>
              <Statistic title="部门" value={deptMonthlyData.org_name} valueStyle={{ fontSize: 18, color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title="累计费用" value={Number(deptMonthlyData.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title="月均费用" value={Number(deptMonthlyData.avg_monthly_fee || 0).toFixed(2)} prefix="¥" />
            </Col>
            <Col span={3}>
              <Statistic title="数据月数" value={deptMonthlyData.month_count} suffix="个月" />
            </Col>
          </Row>

          {/* YoY双柱对比图 */}
          <Card size="small" title="月度费用同比对比" style={{ marginBottom: 16 }}>
            <YoyBarChart data={deptMonthlyData.rows} />
          </Card>

          {/* 数据表 */}
          <Card size="small" title="月度费用明细">
            <Table columns={l1DetailColumns} dataSource={deptMonthlyData.rows} rowKey="billing_month" size="small"
              pagination={false} scroll={{ x: 1100 }} />
          </Card>
        </>
      ) : selectedDeptOrgId && !deptMonthlyLoading ? (
        <Empty description="该部门暂无费用数据" />
      ) : !selectedDeptOrgId ? (
        <Empty description="请选择部门" />
      ) : null}
    </>
  );

  const renderAnalysisTab = () => (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Segmented options={['全部', '一级分行', '二级分行', '部门', '单个号码']} value={dimension} onChange={v => setDimension(v as Dimension)} />
        </Col>
      </Row>

      {dimension === '全部' && renderAllContent()}
      {dimension === '一级分行' && renderL1Content()}
      {dimension === '二级分行' && renderL2Content()}
      {dimension === '部门' && renderDeptContent()}
      {dimension === '单个号码' && renderPhoneContent()}
    </>
  );

  return (
    <Card title={<><BarChartOutlined style={{ marginRight: 8 }} />费用分析</>} styles={{ body: { padding: '16px 20px' } }}>
      {renderAnalysisTab()}
    </Card>
  );
}
