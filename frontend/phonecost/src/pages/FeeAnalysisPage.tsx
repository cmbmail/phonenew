import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Select, Table, Statistic, Row, Col, Input, Segmented, Empty, Tag, Tabs, Tooltip } from 'antd';
import { SearchOutlined, BarChartOutlined, SwapOutlined } from '@ant-design/icons';
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
  sheet_breakdown?: Record<string, number>;
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

// 费用类型配色（Morandi色系）
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

function BarChart({ data, field, height = 260 }: { data: MonthlyRow[]; field?: BarField; height?: number }) {
  const [activeField, setActiveField] = useState<BarField>(field || 'total_fee');
  const values = data.map(d => Number(d[activeField]) || 0);
  const maxVal = Math.max(...values, 1);

  // 计算环比
  const changes = data.map((d, i) => {
    if (i === 0) return null;
    const prev = Number(data[i - 1][activeField]) || 0;
    const cur = Number(d[activeField]) || 0;
    if (prev === 0) return null;
    return ((cur - prev) / prev * 100).toFixed(1);
  });

  return (
    <div>
      {/* 指标选择 */}
      <Row gutter={8} style={{ marginBottom: 16 }}>
        {(Object.keys(BAR_FIELD_LABELS) as BarField[]).map(f => (
          <Col key={f}>
            <span
              onClick={() => setActiveField(f)}
              style={{
                display: 'inline-block',
                padding: '2px 10px',
                fontSize: 12,
                cursor: 'pointer',
                borderRadius: 3,
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

      {/* 柱状图 */}
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: data.length > 6 ? 12 : 24, height, padding: '0 8px 28px', borderBottom: `1px solid ${COLORS.border}` }}>
        {data.map((d, i) => {
          const val = Number(d[activeField]) || 0;
          const pct = maxVal > 0 ? (val / maxVal) * 100 : 0;
          const change = changes[i];
          const isUp = change !== null && Number(change) > 0;
          const isDown = change !== null && Number(change) < 0;

          return (
            <div key={d.billing_month} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 40 }}>
              {/* 数值 */}
              <div style={{ fontSize: 11, color: COLORS.textDark, marginBottom: 4, whiteSpace: 'nowrap', fontWeight: 500 }}>
                {moneyWan(val)}
              </div>
              {/* 柱子 */}
              <Tooltip title={`${d.billing_month} ${BAR_FIELD_LABELS[activeField]}: ${money(val)}`}>
                <div style={{
                  width: '100%',
                  maxWidth: 56,
                  minHeight: 2,
                  height: `${Math.max(pct, 1)}%`,
                  background: FEE_BAR_COLORS[activeField],
                  borderRadius: '3px 3px 0 0',
                  transition: 'height 0.4s ease',
                }} />
              </Tooltip>
              {/* 月份标签 */}
              <div style={{ fontSize: 11, color: COLORS.textMuted, marginTop: 6, whiteSpace: 'nowrap' }}>
                {d.billing_month?.slice(5) || ''}
              </div>
              {/* 环比 */}
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

  // Org selectors for L2/Department
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [selectedL1OrgId, setSelectedL1OrgId] = useState<number | null>(null);
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

  const fetchAnalysis = useCallback(async () => {
    if (!selectedBatchId) return;
    setLoading(true);
    try {
      const dimCode = DIM_MAP[dimension];
      let url = `/allocation/analysis?batchId=${selectedBatchId}&dimension=${dimCode}`;
      if (dimension === '二级分行' && selectedL1OrgId) url += `&orgId=${selectedL1OrgId}`;
      if (dimension === '部门' && selectedParentOrgId) url += `&orgId=${selectedParentOrgId}`;
      if (dimension === '单个号码' && phoneSearch.trim()) url += `&phoneNumber=${phoneSearch.trim()}`;
      const data = await apiGet<Record<string, unknown>>(url);
      setAnalysisData(data);
    } catch {
      setAnalysisData(null);
    } finally {
      setLoading(false);
    }
  }, [selectedBatchId, dimension, selectedL1OrgId, selectedParentOrgId, phoneSearch]);

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

  // Phone detail columns
  const phoneColumns = [
    { title: '月份', dataIndex: 'billing_month', key: 'billing_month', width: 90 },
    { title: '号码', dataIndex: 'phone_number', key: 'phone_number', width: 130 },
    { title: '归属组织', dataIndex: 'org_name', key: 'org_name', width: 160 },
    { title: '归属来源', dataIndex: 'ownership_source', key: 'ownership_source', width: 100,
      render: (v: string) => {
        const map: Record<string, { label: string; color: string }> = {
          P0: { label: 'P0例外', color: COLORS.danger }, P1: { label: 'P1通讯录', color: COLORS.confirmed },
          P2: { label: 'P2归属表', color: COLORS.slate }, P3: { label: 'P3未归属', color: COLORS.textMuted },
        };
        const info = map[v];
        return info ? <Tag color={info.color}>{info.label}</Tag> : (v || '-');
      }},
    { title: '费用合计', dataIndex: 'total_fee', key: 'total_fee', width: 110, render: money },
    { title: '账单条数', dataIndex: 'detail_count', key: 'detail_count', width: 90 },
    { title: '类型明细', dataIndex: 'sheet_breakdown', key: 'sheet_breakdown', width: 200,
      render: (v: Record<string, number>) => v ? Object.entries(v).map(([k, val]) => `${k}:¥${Number(val).toFixed(2)}`).join(' | ') : '-' },
  ];

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

    // 汇总统计
    const latest = monthlyData[monthlyData.length - 1];
    const prev = monthlyData.length >= 2 ? monthlyData[monthlyData.length - 2] : null;
    const totalChange = prev && Number(prev.total_fee) > 0
      ? ((Number(latest.total_fee) - Number(prev.total_fee)) / Number(prev.total_fee) * 100).toFixed(1)
      : null;

    return (
      <>
        {/* 汇总卡片 */}
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
          <Col span={4}><Statistic title="数据月份" value={`${monthlyData.length}个月`} suffix={`/ ${monthlyData.length}`} /></Col>
        </Row>

        {/* 柱状图 */}
        <Card size="small" title="月度费用对比" style={{ marginBottom: 16 }}>
          <BarChart data={monthlyData} />
        </Card>

        {/* 数据表 */}
        <Card size="small" title="月度费用明细">
          <Table columns={monthlyColumns} dataSource={monthlyData} rowKey="billing_month" size="small"
            pagination={false} scroll={{ x: 1000 }} />
        </Card>
      </>
    );
  };

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

  const renderL1Content = () => rows.length > 0 ? (
    <Table columns={feeColumns} dataSource={rows} rowKey="org_id" size="small" loading={loading}
      pagination={{ pageSize: 50, showSizeChanger: true, showTotal: t => `共 ${t} 条` }} scroll={{ x: 1000 }} />
  ) : <Empty description="暂无数据" />;

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

  const renderPhoneContent = () => (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col>
          <Input prefix={<SearchOutlined />} placeholder="输入号码查询" allowClear style={{ width: 280 }}
            value={phoneSearch} onChange={e => setPhoneSearch(e.target.value)} onPressEnter={fetchAnalysis} />
          <span style={{ marginLeft: 8, color: COLORS.textMuted, fontSize: 12 }}>回车查询</span>
        </Col>
      </Row>
      {rows.length > 0 ? (
        <Table columns={phoneColumns} dataSource={rows} rowKey={(r, i) => `${r.billing_month}-${i}`} size="small" loading={loading}
          pagination={{ pageSize: 50 }} scroll={{ x: 900 }} />
      ) : <Empty description={phoneSearch ? '未找到该号码的数据' : '请输入号码查询'} />}
    </>
  );

  const renderAnalysisTab = () => (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <span style={{ marginRight: 8 }}>账单月份：</span>
          <Select style={{ width: 180 }} placeholder="选择月份" value={selectedBatchId} onChange={setSelectedBatchId}
            options={[...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month))
              .map(b => ({ label: `${b.billing_month} (${b.total_count}条)`, value: b.id }))} />
        </Col>
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
