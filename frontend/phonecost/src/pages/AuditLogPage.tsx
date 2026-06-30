import { useState, useEffect, useCallback } from 'react';
import { Card, Table, Select, Input, Tag, Row, Col, Space, DatePicker, Popover, Typography } from 'antd';
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
  EXPORT_BRANCH_BILL: '导出分行账单',
  EXPORT_COST_CENTER_MAPPING: '导出成本中心对照表',
  CLEAR_EXCEPTION: '解除例外',
  SYNC_FROM_MATCH: '同步当前数据',
  UPDATE_EXCEPTION_REASON: '编辑例外原因',
  CREATE_SNAPSHOT: '创建快照',
  BATCH_CLEAR_EXCEPTION: '批量解除例外',
  ORG_IMPORT: '导入组织架构',
  ORG_CREATE: '新建组织',
  ORG_UPDATE: '编辑组织',
  ORG_DELETE: '删除组织',
  ORG_REBUILD_PATHS: '重建组织路径',
  AUTH_LOGIN: '登录',
  AUTH_LOGIN_FAILED: '登录失败',
  AUTH_LOGIN_DISABLED: '账号已停用',
  AUTH_CHANGE_PASSWORD: '修改密码',
  USER_CREATE: '新建用户',
  USER_UPDATE: '编辑用户',
  USER_DELETE: '删除用户',
  USER_RESET_PASSWORD: '重置密码',
  BACKUP_FULL: '全量备份',
  BACKUP_INCREMENTAL: '增量备份',
  BACKUP_RESTORE: '恢复备份',
  BACKUP_DELETE: '删除备份',
  TEMPLATE_CREATE: '新建模板',
  TEMPLATE_UPDATE: '编辑模板',
  TEMPLATE_DELETE: '删除模板',
  TEMPLATE_ACTIVATE: '激活模板',
  UPGRADE_PACKAGE_UPLOAD: '上传升级包',
  UPGRADE_APPLIED: '应用升级',
  UPGRADE_FAILED: '升级失败',
  UPGRADE_ROLLBACK: '回滚升级',
  UPGRADE_PACKAGE_DELETE: '删除升级包',
};

const ACTION_COLOR: Record<string, string> = {
  IMPORT_OWNERSHIP: COLORS.sage,
  IMPORT_DIRECTORY: COLORS.sage,
  IMPORT_BILL: COLORS.sage,
  MATCH_OWNERSHIP: COLORS.sage,
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
  EXPORT_BRANCH_BILL: COLORS.slate,
  EXPORT_COST_CENTER_MAPPING: COLORS.slate,
  CLEAR_EXCEPTION: COLORS.sage,
  SYNC_FROM_MATCH: COLORS.sage,
  UPDATE_EXCEPTION_REASON: COLORS.sage,
  CREATE_SNAPSHOT: COLORS.sage,
  BATCH_CLEAR_EXCEPTION: COLORS.sage,
  ORG_IMPORT: COLORS.sage,
  ORG_CREATE: COLORS.confirmed,
  ORG_UPDATE: COLORS.pending,
  ORG_DELETE: COLORS.danger,
  ORG_REBUILD_PATHS: COLORS.mauve,
  AUTH_LOGIN: COLORS.sage,
  AUTH_LOGIN_FAILED: COLORS.danger,
  AUTH_LOGIN_DISABLED: COLORS.danger,
  AUTH_CHANGE_PASSWORD: COLORS.slate,
  USER_CREATE: COLORS.confirmed,
  USER_UPDATE: COLORS.pending,
  USER_DELETE: COLORS.danger,
  USER_RESET_PASSWORD: COLORS.pending,
  BACKUP_FULL: COLORS.mauve,
  BACKUP_INCREMENTAL: COLORS.mauve,
  BACKUP_RESTORE: COLORS.pending,
  BACKUP_DELETE: COLORS.danger,
  TEMPLATE_CREATE: COLORS.confirmed,
  TEMPLATE_UPDATE: COLORS.pending,
  TEMPLATE_DELETE: COLORS.danger,
  TEMPLATE_ACTIVATE: COLORS.confirmed,
  UPGRADE_PACKAGE_UPLOAD: COLORS.mauve,
  UPGRADE_APPLIED: COLORS.confirmed,
  UPGRADE_FAILED: COLORS.danger,
  UPGRADE_ROLLBACK: COLORS.danger,
  UPGRADE_PACKAGE_DELETE: COLORS.danger,
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

/** 详情 JSON 字段名 → 中文标签 */
const DETAIL_KEY_MAP: Record<string, string> = {
  org_count: '组织数',
  branch_org_id: '分行ID',
  sub_branch_org_id: '支行ID',
  module: '模块',
  total: '总数',
  created: '新增',
  skipped: '跳过',
  updated: '更新',
  package_name: '升级包',
  target_version: '目标版本',
  previous_version: '原版本',
  backup_id: '备份ID',
  sql_statements: 'SQL语句数',
  rolled_back_from: '回滚前版本',
  rolled_back_to: '回滚后版本',
  batch_id: '批次ID',
  count: '数量',
  file_name: '文件名',
  error: '错误信息',
  amount: '金额',
  phone_number: '号码',
  from_org: '调出组织',
  to_org: '调入组织',
  reason: '原因',
  username: '用户名',
  role: '角色',
  status: '状态',
  target_username: '目标用户',
  type: '类型',
  file_path: '文件路径',
  restored_by: '恢复人',
  name: '名称',
  code: '组织代码',
  from_org_id: '调出组织ID',
  to_org_id: '调入组织ID',
  org_id: '组织ID',
};

/** 为每种操作生成一句可读的中文摘要 */
function renderDetailSummary(action: string, obj: Record<string, unknown>): string {
  switch (action) {
    case 'ALLOCATION_CALCULATE':
      return `计算 ${obj.org_count ?? '?'} 个组织的分摊`;
    case 'ALLOCATION_CONFIRM':
      return '确认分摊结果';
    case 'ALLOCATION_CONFIRM_ALL':
      return '批量确认所有分摊';
    case 'ALLOCATION_WITHDRAW':
      return '撤回分摊';
    case 'ALLOCATION_ADJUST':
      return `调整费用${obj.phone_number ? '：' + obj.phone_number : ''}（${obj.from_org_id ? '从组织#' + obj.from_org_id : ''}${obj.to_org_id ? ' → 组织#' + obj.to_org_id : ''}）`;
    case 'EXPORT_SUMMARY':
    case 'EXPORT_DETAIL':
      return obj.branch_org_id ? `导出分行#${obj.branch_org_id}` : '导出全量数据';
    case 'EXPORT_L1_SUMMARY':
      return '导出集团汇总';
    case 'EXPORT_L2_BRANCH_DETAIL':
      return obj.branch_org_id ? `导出分行#${obj.branch_org_id}明细` : '导出全部分行明细';
    case 'EXPORT_L3_SUB_BRANCH_DETAIL':
      return obj.sub_branch_org_id ? `导出支行#${obj.sub_branch_org_id}明细` : '导出全部支行明细';
    case 'EXPORT_BRANCH_BILL':
      return obj.branch_org_id ? `导出分行#${obj.branch_org_id}账单` : '导出全部分行账单';
    case 'EXPORT_COST_CENTER_MAPPING':
      return obj.branch_org_id ? `导出分行#${obj.branch_org_id}成本中心` : '导出全部成本中心对照表';
    case 'ORG_IMPORT':
      return `导入 ${obj.total ?? '?'} 个组织（新增 ${obj.created ?? '?'}，跳过 ${obj.skipped ?? '?'}，更新 ${obj.updated ?? '?'}）`;
    case 'ORG_CREATE':
      return `新建组织「${obj.name ?? '?'}」${obj.code ? '（代码：' + obj.code + '）' : ''}`;
    case 'ORG_UPDATE':
      return `编辑组织「${obj.name ?? '?'}」`;
    case 'ORG_DELETE':
      return '删除组织';
    case 'ORG_REBUILD_PATHS':
      return '重建组织路径';
    case 'AUTH_LOGIN':
      return `用户「${obj.username ?? '?'}」登录（角色：${obj.role ?? '?'}）`;
    case 'AUTH_LOGIN_FAILED':
      return `用户「${obj.username ?? '?'}」登录失败`;
    case 'AUTH_LOGIN_DISABLED':
      return `用户「${obj.username ?? '?'}」账号已停用`;
    case 'AUTH_CHANGE_PASSWORD':
      return `用户「${obj.username ?? '?'}」修改密码`;
    case 'USER_CREATE':
      return `新建用户「${obj.username ?? '?'}」（角色：${obj.role ?? '?'}，组织ID：${obj.org_id ?? '-'}）`;
    case 'USER_UPDATE':
      return `编辑用户（角色：${obj.role ?? '?'}，组织ID：${obj.org_id ?? '-'}，状态：${obj.status ?? '-'}）`;
    case 'USER_DELETE':
      return `删除用户「${obj.username ?? '?'}」`;
    case 'USER_RESET_PASSWORD':
      return `重置用户「${obj.target_username ?? '?'}」密码`;
    case 'BACKUP_FULL':
      return `全量备份${obj.file_path ? '：' + obj.file_path : ''}`;
    case 'BACKUP_INCREMENTAL':
      return `增量备份${obj.file_path ? '：' + obj.file_path : ''}`;
    case 'BACKUP_RESTORE':
      return `恢复备份${obj.restored_by ? '（恢复人：' + obj.restored_by + '）' : ''}`;
    case 'BACKUP_DELETE':
      return '删除备份';
    case 'TEMPLATE_CREATE':
      return `新建模板「${obj.name ?? '?'}」`;
    case 'TEMPLATE_UPDATE':
      return `编辑模板「${obj.name ?? '?'}」`;
    case 'TEMPLATE_DELETE':
      return '删除模板';
    case 'TEMPLATE_ACTIVATE':
      return `激活模板「${obj.name ?? '?'}」`;
    case 'BATCH_CLEAR_EXCEPTION':
      return `批量解除例外（${obj.count ?? '?'} 条）`;
    case 'UPGRADE_PACKAGE_DELETE':
      return '删除升级包';
    case 'CLEAR_EXCEPTION':
      return '解除例外';
    case 'SYNC_FROM_MATCH':
      return '同步当前数据';
    case 'UPDATE_EXCEPTION_REASON':
      return `编辑例外原因${obj.reason ? '：' + obj.reason : ''}`;
    case 'CREATE_SNAPSHOT':
      return `创建快照${obj.file_name ? '：' + obj.file_name : ''}`;
    case 'UPGRADE_PACKAGE_UPLOAD':
      return `上传升级包「${obj.package_name ?? '?'}」→ v${obj.target_version ?? '?'}`;
    case 'UPGRADE_APPLIED':
      return `升级 v${obj.previous_version ?? '?'} → v${obj.target_version ?? '?'}（${obj.sql_statements ?? '?'} 条SQL，备份#${obj.backup_id ?? '?'}）`;
    case 'UPGRADE_ROLLBACK':
      return `回滚 v${obj.rolled_back_from ?? '?'} → v${obj.rolled_back_to ?? '?'}（备份#${obj.backup_id ?? '?'}）`;
    case 'IMPORT_OWNERSHIP':
      return `导入号码归属（${obj.count ?? obj.total ?? '?'} 条）`;
    case 'IMPORT_DIRECTORY':
      return `导入通讯录（${obj.count ?? obj.total ?? '?'} 条）`;
    case 'IMPORT_BILL':
      return `导入电信账单（${obj.count ?? obj.total ?? '?'} 条）`;
    case 'MATCH_OWNERSHIP':
      return '执行归属匹配';
    default: {
      // 兜底：key=value 拼接
      return Object.entries(obj)
        .map(([k, v]) => `${DETAIL_KEY_MAP[k] || k}=${v}`)
        .join('，');
    }
  }
}

/** 将 JSON 对象渲染为可读的键值对列表 */
function renderDetailPopover(obj: Record<string, unknown>): React.ReactNode {
  const entries = Object.entries(obj);
  return (
    <div style={{ maxWidth: 360 }}>
      {entries.map(([k, v]) => (
        <Row key={k} style={{ marginBottom: 4 }}>
          <Col span={8} style={{ color: COLORS.textMuted, fontSize: 12 }}>{DETAIL_KEY_MAP[k] || k}</Col>
          <Col span={16} style={{ fontSize: 12, wordBreak: 'break-all' }}>{String(v ?? '-')}</Col>
        </Row>
      ))}
    </div>
  );
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
      render: (v: string | null, record: AuditLogEntry) => {
        if (!v) return '-';
        try {
          const obj = JSON.parse(v) as Record<string, unknown>;
          const summary = renderDetailSummary(record.action, obj);
          const popoverContent = renderDetailPopover(obj);
          return (
            <Popover content={popoverContent} title="操作详情" trigger="hover" placement="left">
              <span style={{ fontSize: 12, color: COLORS.textDark, cursor: 'pointer' }}>{summary}</span>
            </Popover>
          );
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
