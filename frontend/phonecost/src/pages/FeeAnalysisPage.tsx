import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Select, Table, Statistic, Row, Col, Input, Segmented, Empty, Tag, Tabs, Tooltip } from 'antd';
import { SearchOutlined, BarChartOutlined, SwapOutlined, PhoneOutlined } from '@ant-design/icons';
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

interface MonthlyRow {
  batch_id: number;
  billing_month: string;
  total_fee: number;
  monthly_rent: number;
  call_fee: number;
  recording_fee: number;
  crbt_fee: number;
  flash_msg_fee: number;
  phone_count: number;
  org_count: number;
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
type MainTab = 'comparison' | 'analysis';

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

const feeColumns = [
  { title: '组织名称', dataIndex: 'org_name', key: 'org_name', width: 180, fixed: 'left' as const },
  { title: '月租费', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money, sorter: (a: FeeRow, b: FeeRow) => (a.monthly_rent || 0) - (b.monthly_rent || 0) },
  { title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money, sorter: (a: FeeRow, b: FeeRow) => (a.call_fee || 0) - (b.call_fee || 0) },
  { title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money, sorter: (a: FeeRow, b: FeeRow) => (a.recording_fee || 0) - (b.recording_fee || 0) },
  { title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money, sorter: (a: FeeRow, b: FeeRow) => (a.crbt_fee || 0) - (b.crbt_fee || 0) },
  { title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money, sorter: (a: FeeRow, b: FeeRow) => (a.flash_msg_fee || 0) - (b.flash_msg_fee || 0) },
  { title: '合计', dataIndex: 'total_fee', key: 'total_fee', width: 110, render: (v: number) => <strong>{money(v)}</strong>, sorter: (a: FeeRow, b: FeeRow) => (a.total_fee || 0) - (b.total_fee || 0), defaultSortOrder: 'descend' as const },
  { title: '号码数', dataIndex: 'phone_count', key: 'phone_count', width: 80, sorter: (a: FeeRow, b: FeeRow) => (a.phone_count || 0) - (b.phone_count || 0) },
];

// ==================== 纯CSS柱状图 ====================

interface BarRow {
  billing_month: string;
  total_fee: number;
  monthly_rent: number;
  call_fee: number;
  recording_fee: number;
  crbt_fee: number;
  flash_msg_fee: number;
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
function BarChart({ data, field, height = 260 }: { data: BarRow[]; field?: BarField; height?: number }) {
  const [activeField, setActiveField] = useState<BarField>(field || 'total_fee');
  const values = data.map(d => Number(d[activeField]) || 0);
  const maxVal = Math.max(...values, 1);

  const changes = data.map((d, i) => {
    if (i === 0) return null;
    const prev = Number(data[i - 1][activeField]) || 0;
    const cur = Number(d[activeField]) || 0;
    if (prev === 0) return null;
    return ((cur - prev) / prev * 100).toFixed(1);
  });

  return (
    <div>
      <MetricSelector activeField={activeField} onChange={setActiveField} />
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: data.length > 6 ? 12 : 24, height, padding: '0 8px 28px', borderBottom: `1px solid ${COLORS.border}` }}>
        {data.map((d, i) => {
          const val = Number(d[activeField]) || 0;
          const pct = maxVal > 0 ? (val / maxVal) * 100 : 0;
          const change = changes[i];
          const isUp = change !== null && Number(change) > 0;
          const isDown = change !== null && Number(change) < 0;
          return (
            <div key={d.billing_month} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 40 }}>
              <div style={{ fontSize: 11, color: COLORS.textDark, marginBottom: 4, whiteSpace: 'nowrap', fontWeight: 500 }}>{moneyWan(val)}</div>
              <Tooltip title={`${d.billing_month} ${BAR_FIELD_LABELS[activeField]}: ${money(val)}`}>
                <div style={{ width: '100%', maxWidth: 56, minHeight: 2, height: `${Math.max(pct, 1)}%`, background: FEE_BAR_COLORS[activeField], borderRadius: '3px 3px 0 0', transition: 'height 0.4s ease' }} />
              </Tooltip>
              <div style={{ fontSize: 11, color: COLORS.textMuted, marginTop: 6, whiteSpace: 'nowrap' }}>{d.billing_month?.slice(5) || ''}</div>
              {change !== null && (
                <div style={{ fontSize: 10, color: isUp ? COLORS.danger : isDown ? COLORS.confirmed : COLORS.textMuted, whiteSpace: 'nowrap' }}>
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
function YoyBarChart({ data, height = 280 }: { data: L1MonthlyRow[]; height?: number }) {
  const [activeField, setActiveField] = useState<BarField>('total_fee');

  const maxVal = Math.max(
    ...data.map(d => Number(d[activeField]) || 0),
    ...data.map(d => Number(d.last_year_fee) || 0),
    1,
  );

  return (
    <div>
      <MetricSelector activeField={activeField} onChange={setActiveField} />
      {/* 图例 */}
      <div style={{ display: 'flex', gap: 24, marginBottom: 12, fontSize: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 14, height: 10, background: COLORS.sage, borderRadius: 2 }} />
          <span style={{ color: COLORS.textMuted }}>本年</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 14, height: 10, background: COLORS.border, borderRadius: 2, border: `1px dashed ${COLORS.textMuted}` }} />
          <span style={{ color: COLORS.textMuted }}>去年同期</span>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: data.length > 6 ? 16 : 32, height, padding: '0 8px 28px', borderBottom: `1px solid ${COLORS.border}` }}>
        {data.map((d) => {
          const curVal = Number(d[activeField]) || 0;
          const prevVal = Number(d.last_year_fee) || 0;
          const curPct = maxVal > 0 ? (curVal / maxVal) * 100 : 0;
          const prevPct = maxVal > 0 ? (prevVal / maxVal) * 100 : 0;
          const yoyChange = d.yoy_change;
          const isUp = yoyChange !== null && Number(yoyChange) > 0;
          const isDown = yoyChange !== null && Number(yoyChange) < 0;
          const monthLabel = d.billing_month?.slice(5) || '';

          return (
            <div key={d.billing_month} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 50 }}>
              {/* 数值 + 同比 */}
              <div style={{ fontSize: 10, marginBottom: 2, whiteSpace: 'nowrap' }}>
                <span style={{ color: COLORS.textDark, fontWeight: 500 }}>{moneyWan(curVal)}</span>
                {yoyChange !== null && (
                  <span style={{ marginLeft: 4, color: isUp ? COLORS.danger : isDown ? COLORS.confirmed : COLORS.textMuted }}>
                    {isUp ? '+' : ''}{yoyChange}%
                  </span>
                )}
              </div>
              {/* 双柱容器 */}
              <div style={{ display: 'flex', gap: 3, width: '100%', maxWidth: 60, alignItems: 'flex-end', height: `${Math.max(curPct, prevPct, 1)}%`, flex: '0 0 auto' }}>
                {/* 去年柱 */}
                <Tooltip title={d.last_year_month ? `${d.last_year_month}: ${money(prevVal)}` : '无去年同期数据'}>
                  <div style={{
                    flex: 1, minHeight: 2, height: prevVal > 0 ? `${maxVal > 0 ? (prevVal / maxVal) * 100 : 0}%` : '0%',
                    maxHeight: prevPct > 0 ? `${prevPct}%` : '0%',
                    background: 'transparent',
                    border: prevVal > 0 ? `1.5px dashed ${COLORS.textMuted}` : 'none',
                    borderRadius: '2px 2px 0 0',
                    opacity: prevVal > 0 ? 0.6 : 0,
                  }} />
                </Tooltip>
                {/* 今年柱 */}
                <Tooltip title={`${d.billing_month}: ${money(curVal)}`}>
                  <div style={{
                    flex: 1, minHeight: 2,
                    height: curVal > 0 ? `${curPct}%` : '0%',
                    background: FEE_BAR_COLORS[activeField],
                    borderRadius: '2px 2px 0 0',
                    transition: 'height 0.4s ease',
                  }} />
                </Tooltip>
              </div>
              {/* 月份 */}
              <div style={{ fontSize: 11, color: COLORS.textMuted, marginTop: 6, whiteSpace: 'nowrap' }}>{monthLabel}</div>
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
  const [loading, setLoading] = useState(false);
  const [analysisData, setAnalysisData] = useState<Record<string, unknown> | null>(null);
  const [mainTab, setMainTab] = useState<MainTab>('comparison');

  // Monthly comparison data
  const [monthlyData, setMonthlyData] = useState<MonthlyRow[]>([]);
  const [monthlyLoading, setMonthlyLoading] = useState(false);

  // Phone analysis data
  const [phoneData, setPhoneData] = useState<PhoneAnalysisResult | null>(null);
  const [phoneLoading, setPhoneLoading] = useState(false);

  // L1 monthly analysis data
  const [l1MonthlyData, setL1MonthlyData] = useState<L1MonthlyResult | null>(null);
  const [l1MonthlyLoading, setL1MonthlyLoading] = useState(false);
  const [selectedL1OrgId, setSelectedL1OrgId] = useState<number | null>(null);

  // Org selectors for L2/Department
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [selectedParentOrgId, setSelectedParentOrgId] = useState<number | null>(null);

  // Phone search
  const [phoneSearch, setPhoneSearch] = useState('');

  const fetchBatches = useCallback(async () => {
    try { setBatches(await getBillBatches()); } catch { /* */ }
  }, []);

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { /* */ }
  }, []);

  const fetchMonthly = useCallback(async () => {
    setMonthlyLoading(true);
    try {
      const data = await apiGet<MonthlyRow[]>('/allocation/analysis/monthly-comparison');
      setMonthlyData(data);
    } catch {
      setMonthlyData([]);
    } finally {
      setMonthlyLoading(false);
    }
  }, []);

  useEffect(() => { fetchBatches(); fetchOrgs(); fetchMonthly(); }, [fetchBatches, fetchOrgs, fetchMonthly]);

  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  // L1 monthly fetch
  const fetchL1Monthly = useCallback(async (orgId: number) => {
    setL1MonthlyLoading(true);
    try {
      const data = await apiGet<L1MonthlyResult>(`/allocation/analysis/l1-monthly?orgId=${orgId}`);
      setL1MonthlyData(data);
    } catch {
      setL1MonthlyData(null);
    } finally {
      setL1MonthlyLoading(false);
    }
  }, []);

  // Trigger L1 fetch on org selection
  useEffect(() => {
    if (dimension === '一级分行' && selectedL1OrgId) {
      fetchL1Monthly(selectedL1OrgId);
    }
  }, [dimension, selectedL1OrgId, fetchL1Monthly]);

  // Phone number search handler
  const doPhoneSearch = useCallback(async () => {
    if (!phoneSearch.trim()) return;
    setPhoneLoading(true);
    try {
      const data = await apiGet<PhoneAnalysisResult>(`/allocation/analysis?batchId=${selectedBatchId || 0}&dimension=PHONE&phoneNumber=${phoneSearch.trim()}`);
      setPhoneData(data);
    } catch {
      setPhoneData(null);
    } finally {
      setPhoneLoading(false);
    }
  }, [selectedBatchId, phoneSearch]);

  const fetchAnalysis = useCallback(async () => {
    if (!selectedBatchId) return;
    if (dimension === '单个号码' || dimension === '一级分行') return; // handled separately
    setLoading(true);
    try {
      const dimCode = DIM_MAP[dimension];
      let url = `/allocation/analysis?batchId=${selectedBatchId}&dimension=${dimCode}`;
      if (dimension === '二级分行') url += `&orgId=${selectedL1OrgId || 0}`;
      if (dimension === '部门' && selectedParentOrgId) url += `&orgId=${selectedParentOrgId}`;
      const data = await apiGet<Record<string, unknown>>(url);
      setAnalysisData(data);
    } catch {
      setAnalysisData(null);
    } finally {
      setLoading(false);
    }
  }, [selectedBatchId, dimension, selectedL1OrgId, selectedParentOrgId]);

  useEffect(() => { fetchAnalysis(); }, [fetchAnalysis]);

  // Org dropdowns
  const l1Orgs = useMemo(() => orgList.filter(o => o.type === 2), [orgList]);

  const parentOrgOptions = useMemo(() => {
    if (dimension === '部门') {
      return orgList.map(o => ({ label: `${o.name} (${ORG_TYPE_LABEL[o.type] || o.type})`, value: o.id }));
    }
    return [];
  }, [orgList, dimension]);

  // Parse analysis data
  const rows: FeeRow[] = analysisData?.rows as FeeRow[] || [];
  const allData = dimension === '全部' ? analysisData : null;

  const breakdownList = (allData?.fee_breakdown || []) as { name: string; value: number; percent: string }[];
  const topOrgs = (allData?.top_orgs || []) as FeeRow[];

  // Deparment columns (add org_type)
  const deptColumns = [
    { title: '组织名称', dataIndex: 'org_name', key: 'org_name', width: 180, fixed: 'left' as const },
    { title: '类型', dataIndex: 'org_type', key: 'org_type', width: 90,
      render: (v: number) => ORG_TYPE_LABEL[v] || v || '-' },
    ...feeColumns.slice(1),
  ];

  // ==================== 月度对比Tab ====================

  const monthlyColumns = [
    { title: '月份', dataIndex: 'billing_month', key: 'billing_month', width: 100, fixed: 'left' as const },
    { title: '总费用', dataIndex: 'total_fee', key: 'total_fee', width: 120, render: (v: number) => <strong>{money(v)}</strong>,
      sorter: (a: MonthlyRow, b: MonthlyRow) => (a.total_fee || 0) - (b.total_fee || 0), defaultSortOrder: 'descend' as const },
    { title: '月租费', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 110, render: money },
    { title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 110, render: money },
    { title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 110, render: money },
    { title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 110, render: money },
    { title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 110, render: money },
    { title: '号码数', dataIndex: 'phone_count', key: 'phone_count', width: 80 },
    { title: '组织数', dataIndex: 'org_count', key: 'org_count', width: 80 },
  ];

  const renderComparisonTab = () => {
    if (monthlyLoading) return <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>加载中...</div>;
    if (monthlyData.length === 0) return <Empty description="暂无月度数据" />;

    const latest = monthlyData[monthlyData.length - 1];
    const prev = monthlyData.length >= 2 ? monthlyData[monthlyData.length - 2] : null;
    const totalChange = prev && Number(prev.total_fee) > 0
      ? ((Number(latest.total_fee) - Number(prev.total_fee)) / Number(prev.total_fee) * 100).toFixed(1)
      : null;

    return (
      <>
        <Row gutter={16} style={{ marginBottom: 20 }}>
          <Col span={4}><Statistic title="最新月份" value={latest.billing_month} valueStyle={{ fontSize: 18, color: COLORS.sage }} /></Col>
          <Col span={5}>
            <Statistic title="最新月总费用" value={Number(latest.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} />
            {totalChange !== null && <div style={{ fontSize: 12, color: Number(totalChange) > 0 ? COLORS.danger : COLORS.confirmed }}>
              环比 {Number(totalChange) > 0 ? '+' : ''}{totalChange}%
            </div>}
          </Col>
          <Col span={3}><Statistic title="号码数" value={latest.phone_count} /></Col>
          <Col span={3}><Statistic title="组织数" value={latest.org_count} /></Col>
          <Col span={4}><Statistic title="数据月份" value={`${monthlyData.length}个月`} /></Col>
        </Row>

        <Card size="small" title="月度费用对比" style={{ marginBottom: 16 }}>
          <BarChart data={monthlyData} />
        </Card>

        <Card size="small" title="月度费用明细">
          <Table columns={monthlyColumns} dataSource={monthlyData} rowKey="billing_month" size="small"
            pagination={false} scroll={{ x: 1000 }} />
        </Card>
      </>
    );
  };

  // ==================== 一级分行分析 ====================

  const l1DetailColumns = [
    { title: '月份', dataIndex: 'billing_month', key: 'billing_month', width: 100, fixed: 'left' as const },
    { title: '本年费用', dataIndex: 'total_fee', key: 'total_fee', width: 120, render: (v: number) => <strong>{money(v)}</strong> },
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
            <YoyBarChart data={l1MonthlyData.rows} height={240} />
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

  const renderPhoneContent = () => (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col>
          <Input.Search
            prefix={<PhoneOutlined />}
            placeholder="输入号码查询费用清单"
            allowClear
            style={{ width: 320 }}
            value={phoneSearch}
            onChange={e => setPhoneSearch(e.target.value)}
            onSearch={doPhoneSearch}
            enterButton="查询"
            loading={phoneLoading}
          />
        </Col>
      </Row>

      {phoneData && phoneData.rows && phoneData.rows.length > 0 ? (
        <>
          <Row gutter={16} style={{ marginBottom: 20 }}>
            <Col span={4}><Statistic title="号码" value={phoneData.phone_number} valueStyle={{ fontSize: 16, color: COLORS.sage }} /></Col>
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
            <BarChart data={phoneData.rows as unknown as BarRow[]} height={220} />
          </Card>

          <Card size="small" title="费用清单">
            <Table columns={phoneDetailColumns} dataSource={phoneData.rows} rowKey="billing_month" size="small"
              pagination={false} scroll={{ x: 1100 }} />
          </Card>
        </>
      ) : phoneSearch && !phoneLoading ? (
        <Empty description="未找到该号码的费用数据" />
      ) : (
        <Empty description="请输入号码查询费用清单" />
      )}
    </>
  );

  // ==================== 费用分析Tab ====================

  const renderAllContent = () => (
    <>
      <Row gutter={16} style={{ marginBottom: 20 }}>
        <Col span={4}><Statistic title="费用总额" value={Number(allData?.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} /></Col>
        <Col span={3}><Statistic title="月租费" value={Number(allData?.monthly_rent || 0).toFixed(2)} prefix="¥" /></Col>
        <Col span={3}><Statistic title="通话费" value={Number(allData?.call_fee || 0).toFixed(2)} prefix="¥" /></Col>
        <Col span={3}><Statistic title="录音费" value={Number(allData?.recording_fee || 0).toFixed(2)} prefix="¥" /></Col>
        <Col span={3}><Statistic title="彩铃费" value={Number(allData?.crbt_fee || 0).toFixed(2)} prefix="¥" /></Col>
        <Col span={3}><Statistic title="闪信费" value={Number(allData?.flash_msg_fee || 0).toFixed(2)} prefix="¥" /></Col>
        <Col span={2}><Statistic title="号码数" value={allData?.phone_count || 0} /></Col>
        <Col span={2}><Statistic title="组织数" value={allData?.org_count || 0} /></Col>
      </Row>

      {breakdownList.length > 0 && (
        <Card size="small" title="费用构成" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            {breakdownList.map(item => (
              <Col span={Math.max(4, Math.floor(24 / breakdownList.length))} key={item.name}>
                <Statistic title={item.name} value={Number(item.value || 0).toFixed(2)} prefix="¥"
                  suffix={<span style={{ fontSize: 12, color: COLORS.textMuted }}>({item.percent})</span>} />
              </Col>
            ))}
          </Row>
        </Card>
      )}

      {allData?.unassigned_fee && Number(allData.unassigned_fee) > 0 && (
        <Card size="small" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={8}><Statistic title="未归属费用" value={Number(allData.unassigned_fee).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.danger }} /></Col>
            <Col span={8}><Statistic title="未归属号码数" value={allData.unassigned_phones || 0} /></Col>
            <Col span={8}><Statistic title="未归属占比" value={allData.total_fee && Number(allData.total_fee) > 0 ? (Number(allData.unassigned_fee) / Number(allData.total_fee) * 100).toFixed(1) + '%' : '0%'} /></Col>
          </Row>
        </Card>
      )}

      {topOrgs.length > 0 && (
        <Card size="small" title="费用TOP10组织">
          <Table columns={[
            { title: '排名', key: 'rank', width: 60, render: (_: unknown, __: unknown, i: number) => i + 1 },
            { title: '组织名称', dataIndex: 'org_name', key: 'org_name' },
            { title: '费用合计', dataIndex: 'total_fee', key: 'total_fee', render: money },
            { title: '号码数', dataIndex: 'phone_count', key: 'phone_count', width: 80 },
          ]} dataSource={topOrgs} rowKey="org_id" size="small" pagination={false} />
        </Card>
      )}
    </>
  );

  const renderL2Content = () => (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col><span style={{ marginRight: 8 }}>选择一级分行：</span>
          <Select style={{ width: 240 }} placeholder="选择一级分行" value={selectedL1OrgId} onChange={setSelectedL1OrgId}
            options={l1Orgs.map(o => ({ label: o.name, value: o.id }))} />
        </Col>
      </Row>
      {rows.length > 0 ? (
        <Table columns={feeColumns} dataSource={rows} rowKey="org_id" size="small" loading={loading}
          pagination={{ pageSize: 50, showSizeChanger: true, showTotal: t => `共 ${t} 条` }} scroll={{ x: 1000 }} />
      ) : <Empty description={selectedL1OrgId ? '该分行下暂无数据' : '请选择一级分行'} />}
    </>
  );

  const renderDeptContent = () => (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col><span style={{ marginRight: 8 }}>选择上级组织：</span>
          <Select style={{ width: 300 }} placeholder="选择上级组织" showSearch optionFilterProp="label"
            value={selectedParentOrgId} onChange={setSelectedParentOrgId} options={parentOrgOptions} />
        </Col>
      </Row>
      {rows.length > 0 ? (
        <Table columns={deptColumns} dataSource={rows} rowKey="org_id" size="small" loading={loading}
          pagination={{ pageSize: 50, showSizeChanger: true, showTotal: t => `共 ${t} 条` }} scroll={{ x: 1100 }} />
      ) : <Empty description={selectedParentOrgId ? '该组织下暂无数据' : '请选择上级组织'} />}
    </>
  );

  const renderAnalysisTab = () => (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        {dimension !== '单个号码' && dimension !== '一级分行' && (
          <Col>
            <span style={{ marginRight: 8 }}>账单月份：</span>
            <Select style={{ width: 180 }} placeholder="选择月份" value={selectedBatchId} onChange={setSelectedBatchId}
              options={[...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month))
                .map(b => ({ label: `${b.billing_month} (${b.total_count}条)`, value: b.id }))} />
          </Col>
        )}
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
      <Tabs activeKey={mainTab} onChange={k => setMainTab(k as MainTab)} items={[
        { key: 'comparison', label: <><SwapOutlined /> 总费用对比</>, children: renderComparisonTab() },
        { key: 'analysis', label: <><BarChartOutlined /> 费用分析</>, children: renderAnalysisTab() },
      ]} />
    </Card>
  );
}
