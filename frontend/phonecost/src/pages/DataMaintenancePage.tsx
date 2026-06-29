import { useState, useCallback, useEffect } from 'react';
import { Card, Table, Button, Tag, Popconfirm, message, Row, Col, Statistic, Tooltip, Modal, Steps, Typography } from 'antd';
import { SafetyCertificateOutlined, DatabaseOutlined, ReloadOutlined, CloudUploadOutlined, CloudDownloadOutlined, DeleteOutlined, UndoOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { apiGet, apiPost, apiDelete } from '../lib/request';
import { COLORS } from '../theme/morandi';

const { Text } = Typography;

interface BackupRecord {
  id: number;
  backupType: string;
  filePath: string;
  fileSize: number;
  status: string;
  tableCount: number;
  rowCount: number;
  triggerType: string;
  errorMessage: string | null;
  baseBackupId: number | null;
  createdAt: string;
  updatedAt: string;
}

interface PagedResult {
  content: BackupRecord[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const formatSize = (bytes: number) => {
  if (!bytes || bytes === 0) return '-';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
};

const formatDate = (s: string) => {
  if (!s) return '-';
  return s.replace('T', ' ').slice(0, 19);
};

export default function DataMaintenancePage() {
  const [data, setData] = useState<BackupRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [restoreResult, setRestoreResult] = useState<{
    visible: boolean;
    chainRestore: boolean;
    steps: { step: number; backupId: number; backupType: string; createdAt: string }[];
  } | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const res = await apiGet<PagedResult>(`/backups?page=${page}&size=${pageSize}`);
      setData(res.content || []);
      setTotal(res.totalElements || 0);
    } catch {
      message.error('加载备份列表失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const handleFullBackup = async () => {
    setActionLoading('full');
    try {
      message.loading({ content: '正在执行全量备份...', key: 'backup', duration: 0 });
      await apiPost('/backups/full');
      message.success({ content: '全量备份完成', key: 'backup' });
      fetchData();
    } catch {
      message.error({ content: '全量备份失败', key: 'backup' });
    } finally {
      setActionLoading(null);
    }
  };

  const handleIncrementalBackup = async () => {
    setActionLoading('incr');
    try {
      message.loading({ content: '正在执行增量备份...', key: 'backup', duration: 0 });
      await apiPost('/backups/incremental');
      message.success({ content: '增量备份完成', key: 'backup' });
      fetchData();
    } catch {
      message.error({ content: '增量备份失败', key: 'backup' });
    } finally {
      setActionLoading(null);
    }
  };

  const handleRestore = async (id: number) => {
    setActionLoading('restore-' + id);
    try {
      message.loading({ content: '正在恢复数据，请勿操作...', key: 'restore', duration: 0 });
      const res = await apiPost<{
        chainRestore: boolean;
        steps: { step: number; backupId: number; backupType: string; createdAt: string }[];
      }>(`/backups/${id}/restore`);
      message.success({ content: '数据恢复完成', key: 'restore' });
      // Show restore result modal
      setRestoreResult({
        visible: true,
        chainRestore: res.chainRestore ?? false,
        steps: res.steps ?? [],
      });
      fetchData();
    } catch {
      message.error({ content: '数据恢复失败', key: 'restore' });
    } finally {
      setActionLoading(null);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await apiDelete(`/backups/${id}`);
      message.success('备份已删除');
      fetchData();
    } catch {
      message.error('删除失败');
    }
  };

  const successCount = data.filter(r => r.status === 'SUCCESS').length;
  const fullCount = data.filter(r => r.backupType === 'FULL' && r.status === 'SUCCESS').length;
  const incrCount = data.filter(r => r.backupType === 'INCREMENTAL' && r.status === 'SUCCESS').length;
  const totalSize = data.filter(r => r.status === 'SUCCESS').reduce((s, r) => s + (r.fileSize || 0), 0);

  const columns = [
    { title: '备份时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: formatDate },
    { title: '类型', dataIndex: 'backupType', key: 'backupType', width: 90,
      render: (v: string) => v === 'FULL'
        ? <Tag color={COLORS.sage}>全量备份</Tag>
        : <Tag color={COLORS.slate}>增量备份</Tag> },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => {
        const map: Record<string, { label: string; color: string }> = {
          SUCCESS: { label: '成功', color: COLORS.confirmed },
          FAILED: { label: '失败', color: COLORS.danger },
          IN_PROGRESS: { label: '进行中', color: COLORS.pending },
        };
        const info = map[v] || { label: v, color: COLORS.textMuted };
        return <Tag color={info.color}>{info.label}</Tag>;
      } },
    { title: '触发方式', dataIndex: 'triggerType', key: 'triggerType', width: 80,
      render: (v: string) => v === 'AUTO' ? '自动' : '手动' },
    { title: '文件大小', dataIndex: 'fileSize', key: 'fileSize', width: 100, render: formatSize },
    { title: '基准备份', dataIndex: 'baseBackupId', key: 'baseBackupId', width: 80,
      render: (v: number | null) => v ? `#${v}` : '-' },
    { title: '错误信息', dataIndex: 'errorMessage', key: 'errorMessage', width: 200,
      render: (v: string | null) => v
        ? <Tooltip title={v}><span style={{ color: COLORS.danger, cursor: 'help' }}>{v.slice(0, 40)}...</span></Tooltip>
        : '-' },
    { title: '操作', key: 'actions', width: 140, fixed: 'right' as const,
      render: (_: unknown, r: BackupRecord) => (
        <>
          {r.status === 'SUCCESS' && (
            <Popconfirm
              title={
                r.backupType === 'INCREMENTAL'
                  ? '确定从该增量备份恢复？'
                  : '确定从该全量备份恢复？'
              }
              description={
                r.backupType === 'INCREMENTAL'
                  ? `将先自动恢复基准全量备份 #${r.baseBackupId}，再恢复此增量备份，当前数据将被覆盖`
                  : '将覆盖当前所有数据为该备份时的状态'
              }
              onConfirm={() => handleRestore(r.id)}
              okText="确定恢复"
              cancelText="取消"
            >
              <Button type="link" size="small" icon={<UndoOutlined />}
                loading={actionLoading === 'restore-' + r.id}
                style={{ color: COLORS.pending, padding: '0 4px' }}>恢复</Button>
            </Popconfirm>
          )}
          <Popconfirm title="确定删除该备份？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger icon={<DeleteOutlined />} style={{ padding: '0 4px' }}>删除</Button>
          </Popconfirm>
        </>
      ) },
  ];

  return (
    <Card title={<><SafetyCertificateOutlined style={{ marginRight: 8 }} />数据维护</>}
      styles={{ body: { padding: '16px 20px' } }}
      extra={
        <div style={{ display: 'flex', gap: 8 }}>
          <Button icon={<CloudUploadOutlined />} onClick={handleFullBackup}
            loading={actionLoading === 'full'}
            style={{ borderColor: COLORS.sage, color: COLORS.sage }}>全量备份</Button>
          <Button icon={<CloudDownloadOutlined />} onClick={handleIncrementalBackup}
            loading={actionLoading === 'incr'}
            style={{ borderColor: COLORS.slate, color: COLORS.slate }}>增量备份</Button>
          <Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button>
        </div>
      }
    >
      <Row gutter={16} style={{ marginBottom: 20 }}>
        <Col span={6}><Statistic title="备份总数" value={total} prefix={<DatabaseOutlined />} /></Col>
        <Col span={6}><Statistic title="全量备份" value={fullCount} valueStyle={{ color: COLORS.sage }} /></Col>
        <Col span={6}><Statistic title="增量备份" value={incrCount} valueStyle={{ color: COLORS.slate }} /></Col>
        <Col span={6}><Statistic title="备份总大小" value={formatSize(totalSize)} valueStyle={{ color: COLORS.charcoal }} /></Col>
      </Row>

      <Table
        columns={columns}
        dataSource={data}
        rowKey="id"
        size="small"
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: t => `共 ${t} 条`,
          onChange: (p, s) => { setPage(p - 1); setPageSize(s); },
        }}
        scroll={{ x: 1000 }}
      />

      <div style={{ marginTop: 16, padding: 12, background: COLORS.cream, borderRadius: 8, fontSize: 12, color: COLORS.textMuted }}>
        <p style={{ margin: '4px 0' }}><strong>备份策略：</strong>每月1日凌晨2:00自动执行全量备份，每日凌晨2:30自动执行增量备份。</p>
        <p style={{ margin: '4px 0' }}><strong>全量备份：</strong>备份整个数据库的所有表结构和数据。</p>
        <p style={{ margin: '4px 0' }}><strong>增量备份：</strong>仅备份自上次成功备份以来有变更的数据（基于 updated_at 字段）。</p>
        <p style={{ margin: '4px 0' }}><strong>恢复说明：</strong>恢复增量备份时，系统将自动先恢复基准全量备份，再恢复增量数据，无需手动操作。</p>
      </div>

      <Modal
        title={<><CheckCircleOutlined style={{ color: COLORS.confirmed, marginRight: 8 }} />恢复完成</>}
        open={restoreResult?.visible ?? false}
        onOk={() => setRestoreResult(null)}
        onCancel={() => setRestoreResult(null)}
        okText="确定"
        cancelButtonProps={{ style: { display: 'none' } }}
        width={520}
      >
        {restoreResult && (
          <>
            {restoreResult.chainRestore ? (
              <div style={{ marginBottom: 12 }}>
                <Text type="secondary">本次为链式恢复，系统自动按顺序执行了以下步骤：</Text>
              </div>
            ) : (
              <div style={{ marginBottom: 12 }}>
                <Text type="secondary">全量备份恢复完成。</Text>
              </div>
            )}
            <Steps
              direction="vertical"
              size="small"
              current={restoreResult.steps.length}
              items={restoreResult.steps.map((s) => ({
                title: s.backupType === 'FULL' ? '恢复全量备份' : '恢复增量备份',
                description: (
                  <span style={{ fontSize: 12, color: COLORS.textMuted }}>
                    备份 #{s.backupId} | {s.backupType === 'FULL' ? '全量' : '增量'} | {formatDate(s.createdAt)}
                  </span>
                ),
                status: 'finish' as const,
              }))}
            />
          </>
        )}
      </Modal>
    </Card>
  );
}
