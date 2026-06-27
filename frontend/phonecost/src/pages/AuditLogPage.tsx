import { useState, useEffect, useCallback } from 'react';
import { Card, Table, Select, Input, Tag, Row, Col, Space, DatePicker } from 'antd';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { COLORS } from '../theme/morandi';
import { apiGet } from '../lib/request';
import dayjs from 'dayjs';

const ACTION_MAP: Record<string, string> = {
  IMPORT_OWNERSHIP: '导入号码归属',
  IMPORT_DIRECTORY: '导入通讯录',
  IMPORT_BILL: '导入电信账单',
  MATCH_OWNERSHIP: '归属匹配',
  ALLOCATION_CALCULATE: '费用分摊计算',
  ALLOCATION_CONFIRM: '确认分摊',
  ALLOCATION_CONFIRM_ALL: '批量确认分摊',
  ALLOCATION_WITHDRAW: '撤回分摊',
  ALLOCATION_ADJUST: '费用调整',
  EXPORT_SUMMARY: '导出汇总',
  EXPORT_DETAIL: '导出明细',
  EXPORT_L1_SUMMARY: '导出集团汇总',
  EXPORT_L2_BRANCH_DETAIL: '导出分行明细',
  EXPORT_L3_SUB_BRANCH_DETAIL: '导出支行明细',
  EXPORT_COST_CENTER_MAPPING: '导出成本中心对照表',
  CLEAR_EXCEPTION: '解除例外',
  SYNC_FROM_MATCH: '同步当前数据',
  UPDATE_EXCEPTION_REASON: '编辑例外原因',
  CREATE_SNAPSHOT: '创建快照',
  BATCH_CLEAR_EXCEPTION: '批量解除例外',
};

const ACTION_COLOR: Record<string, string> = {
  IMPORT_OWNERSHIP: COLORS.sage,
  IMPORT_DIRECTORY: COLORS.sage,
  IMPORT_BILL: COLORS.sage,
  MATCH_OWNERSHIP: COLORS.info,
  ALLOCATION_CALCULATE: COLORS.mauve,
  ALLOCATION_CONFIRM: COLORS.confirmed,
  ALLOCATION_CONFIRM_ALL: COLORS.confirmed,
  ALLOCATION_WITHDRAW: COLORS.danger,
  ALLOCATION_ADJUST: COLORS.pending,
  EXPORT_SUMMARY: COLORS.slate,
  EXPORT_DETAIL: COLORS.slate,
  EXPORT_L1_SUMMARY: COLORS.slate,
  EXPORT_L2_BRANCH_DETAIL: COLORS.slate,
  EXPORT_L3_SUB_BRANCH_DETAIL: COLORS.slate,
};

interface AuditLogEntry {
  id: number;
  user_id: number;
  username: string;
  action: string;
  entity_type: string;
  entity_id: number | null;
  detail: string | null;
  ip_address: string;
  created_at: string;
}

interface PagedResult {
  content: AuditLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const AuditLogPage: React.FC = () => {
  const [data, setData] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [actionFilter, setActionFilter] = useState<string | undefined>();
  const [usernameFilter, setUsernameFilter] = useState('');
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), size: String(pageSize) });
      if (actionFilter) params.set('action', actionFilter);
      if (usernameFilter) params.set('username', usernameFilter);
      if (dateRange && dateRange[0]) params.set('startDate', dateRange[0].format('YYYY-MM-DD'));
      if (dateRange && dateRange[1]) params.set('endDate', dateRange[1].format('YYYY-MM-DD'));
      const result = await apiGet<PagedResult>(`/audit-logs?${params.toString()}`);
      setData(result.content);
      setTotal(result.totalElements);
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, actionFilter, usernameFilter, dateRange]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const columns = [
    { title: '时间', dataIndex: 'created_at', key: 'created_at', width: 170,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm:ss') },
    { title: '操作人', dataIndex: 'username', key: 'username', width: 100 },
    { title: '操作类型', dataIndex: 'action', key: 'action', width: 150,
      render: (v: string) => <Tag color={ACTION_COLOR[v] || COLORS.sage}>{ACTION_MAP[v] || v}</Tag> },
    { title: '对象类型', dataIndex: 'entity_type', key: 'entity_type', width: 120,
      render: (v: string) => v || '-' },
    { title: '对象ID', dataIndex: 'entity_id', key: 'entity_id', width: 80,
      render: (v: number | null) => v ?? '-' },
    { title: '详情', dataIndex: 'detail', key: 'detail', width: 300, ellipsis: true,
      render: (v: string | null) => {
        if (!v) return '-';
        try {
          const obj = JSON.parse(v);
          return <span style={{ fontSize: 12, color: COLORS.textMuted }}>{JSON.stringify(obj)}</span>;
        } catch {
          return <span style={{ fontSize: 12, color: COLORS.textMuted }}>{v}</span>;
        }
      }},
    { title: 'IP地址', dataIndex: 'ip_address', key: 'ip_address', width: 130,
      render: (v: string) => v || '-' },
  ];

  const actionOptions = Object.entries(ACTION_MAP).map(([k, v]) => ({ label: v, value: k }));

  return (
    <Card title="操作日志" styles={{ body: { padding: '16px 20px' } }}>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Space>
            <Select style={{ width: 180 }} placeholder="操作类型" allowClear value={actionFilter} onChange={v => { setActionFilter(v); setPage(0); }} options={actionOptions} />
            <Input prefix={<SearchOutlined />} placeholder="搜索用户名" allowClear style={{ width: 160 }} value={usernameFilter} onChange={e => { setUsernameFilter(e.target.value); setPage(0); }} onPressEnter={() => fetchData()} />
            <DatePicker.RangePicker style={{ width: 260 }} value={dateRange} onChange={v => setDateRange(v)} />
            <ReloadOutlined style={{ color: COLORS.sage, cursor: 'pointer' }} onClick={fetchData} />
          </Space>
        </Col>
      </Row>
      <Table columns={columns} dataSource={data} rowKey="id" size="small" loading={loading}
        pagination={{
          current: page + 1,
          pageSize,
          total,
          showSizeChanger: true,
          pageSizeOptions: ['20', '50', '100'],
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, ps) => { setPage(p - 1); setPageSize(ps); },
        }}
        scroll={{ x: 1100 }} />
    </Card>
  );
};

export default AuditLogPage;
