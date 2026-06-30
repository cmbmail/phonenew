import { useState, useCallback, useEffect, useRef } from 'react';
import { Card, Table, Button, Tag, Popconfirm, message, Row, Col, Statistic, Tooltip, Modal, Steps, Typography, Tabs, Upload, Descriptions, Empty, Badge } from 'antd';
import { SafetyCertificateOutlined, DatabaseOutlined, ReloadOutlined, CloudUploadOutlined, CloudDownloadOutlined, DeleteOutlined, UndoOutlined, CheckCircleOutlined, RocketOutlined, HistoryOutlined, UploadOutlined, RollbackOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { apiGet, apiPost, apiDelete, apiUpload } from '../lib/request';
import { COLORS } from '../theme/morandi';

const { Text } = Typography;

// ==================== Backup Types ====================
interface BackupRecord {
  id: number;
  backup_type: string;
  file_path: string;
  file_size: number;
  status: string;
  table_count: number;
  row_count: number;
  trigger_type: string;
  error_message: string | null;
  base_backup_id: number | null;
  created_at: string;
  updated_at: string;
}

interface PagedResult {
  content: BackupRecord[];
  total_elements: number;
  total_pages: number;
  number: number;
  size: number;
}

interface RestoreStep {
  step: number;
  backup_id: number;
  backup_type: string;
  file_path: string;
  created_at: string;
}

interface RestoreResponse {
  chain_restore: boolean;
  steps: RestoreStep[];
}

// ==================== Version Upgrade Types ====================
interface VersionInfo {
  id: number;
  version: string;
  description: string;
  is_current: boolean;
  backup_id: number | null;
  created_at: string;
}

interface UpgradePackage {
  id: number;
  package_name: string;
  target_version: string;
  description: string | null;
  file_size: number;
  status: string;
  applied_at: string | null;
  error_message: string | null;
  created_at: string;
}

// ==================== Common Helpers ====================
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

// ==================== Backup Management Tab ====================
function BackupTab() {
  const [data, setData] = useState<BackupRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [restoreResult, setRestoreResult] = useState<{
    visible: boolean;
    chain_restore: boolean;
    steps: RestoreStep[];
  } | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const res = await apiGet<PagedResult>(`/backups?page=${page}&size=${pageSize}`);
      setData(res.content || []);
      setTotal(res.total_elements || 0);
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
      const res = await apiPost<RestoreResponse>(`/backups/${id}/restore`);
      message.success({ content: '数据恢复完成', key: 'restore' });
      setRestoreResult({
        visible: true,
        chain_restore: res.chain_restore ?? false,
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

  const fullCount = data.filter(r => r.backup_type === 'FULL' && r.status === 'SUCCESS').length;
  const incrCount = data.filter(r => r.backup_type === 'INCREMENTAL' && r.status === 'SUCCESS').length;
  const totalSize = data.filter(r => r.status === 'SUCCESS').reduce((s, r) => s + (r.file_size || 0), 0);

  const columns = [
    { title: '备份时间', dataIndex: 'created_at', key: 'created_at', width: 170, render: formatDate },
    { title: '类型', dataIndex: 'backup_type', key: 'backup_type', width: 90,
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
    { title: '触发方式', dataIndex: 'trigger_type', key: 'trigger_type', width: 80,
      render: (v: string) => v === 'AUTO' ? '自动' : '手动' },
    { title: '文件大小', dataIndex: 'file_size', key: 'file_size', width: 100, render: formatSize },
    { title: '基准备份', dataIndex: 'base_backup_id', key: 'base_backup_id', width: 80,
      render: (v: number | null) => v ? `#${v}` : '-' },
    { title: '错误信息', dataIndex: 'error_message', key: 'error_message', width: 200,
      render: (v: string | null) => v
        ? <Tooltip title={v}><span style={{ color: COLORS.danger, cursor: 'help' }}>{v.slice(0, 40)}...</span></Tooltip>
        : '-' },
    { title: '操作', key: 'actions', width: 140, fixed: 'right' as const,
      render: (_: unknown, r: BackupRecord) => (
        <>
          {r.status === 'SUCCESS' && (
            <Popconfirm
              title={
                r.backup_type === 'INCREMENTAL'
                  ? '确定从该增量备份恢复？'
                  : '确定从该全量备份恢复？'
              }
              description={
                r.backup_type === 'INCREMENTAL'
                  ? `将先自动恢复基准全量备份 #${r.base_backup_id}，再恢复此增量备份，当前数据将被覆盖`
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
    <>
      <div style={{ marginBottom: 16, display: 'flex', gap: 8 }}>
        <Button icon={<CloudUploadOutlined />} onClick={handleFullBackup}
          loading={actionLoading === 'full'}
          style={{ borderColor: COLORS.sage, color: COLORS.sage }}>全量备份</Button>
        <Button icon={<CloudDownloadOutlined />} onClick={handleIncrementalBackup}
          loading={actionLoading === 'incr'}
          style={{ borderColor: COLORS.slate, color: COLORS.slate }}>增量备份</Button>
        <Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button>
      </div>

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
            {restoreResult.chain_restore ? (
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
                title: s.backup_type === 'FULL' ? '恢复全量备份' : '恢复增量备份',
                description: (
                  <span style={{ fontSize: 12, color: COLORS.textMuted }}>
                    备份 #{s.backup_id} | {s.backup_type === 'FULL' ? '全量' : '增量'} | {formatDate(s.created_at)}
                  </span>
                ),
                status: 'finish' as const,
              }))}
            />
          </>
        )}
      </Modal>
    </>
  );
}

// ==================== Version Upgrade Tab ====================
function VersionUpgradeTab() {
  const [currentVersion, setCurrentVersion] = useState<VersionInfo | null>(null);
  const [versionHistory, setVersionHistory] = useState<VersionInfo[]>([]);
  const [packages, setPackages] = useState<UpgradePackage[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [applyingId, setApplyingId] = useState<number | null>(null);
  const [rollbackVersionId, setRollbackVersionId] = useState<number | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const fetchAll = useCallback(async () => {
    setLoading(true);
    try {
      const [ver, hist, pkgs] = await Promise.all([
        apiGet<VersionInfo>('/version/current'),
        apiGet<VersionInfo[]>('/version/history'),
        apiGet<UpgradePackage[]>('/version/packages'),
      ]);
      setCurrentVersion(ver);
      setVersionHistory(hist || []);
      setPackages(pkgs || []);
    } catch {
      message.error('加载版本信息失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchAll(); }, [fetchAll]);

  const handleUpload = async (file: File) => {
    const isZip = file.name.endsWith('.zip');
    if (!isZip) {
      message.error('请上传 ZIP 格式的升级包');
      return false;
    }

    setUploading(true);
    try {
      const formData = new FormData();
      formData.append('file', file);
      message.loading({ content: '正在上传并验证升级包...', key: 'upload', duration: 0 });
      await apiUpload<UpgradePackage>('/version/packages/upload', formData);
      message.success({ content: '升级包上传成功', key: 'upload' });
      fetchAll();
    } catch (e: unknown) {
      const errMsg = e instanceof Error ? e.message : '上传失败';
      message.error({ content: errMsg, key: 'upload' });
    } finally {
      setUploading(false);
    }
    return false;
  };

  const handleApply = async (pkgId: number) => {
    setApplyingId(pkgId);
    try {
      message.loading({ content: '正在应用升级，请勿操作...', key: 'apply', duration: 0 });
      const result = await apiPost<{ previous_version: string; target_version: string; backup_id: number; sql_statements: number }>(`/version/packages/${pkgId}/apply`);
      message.success({ content: `升级成功：${result.previous_version} → ${result.target_version}，执行 ${result.sql_statements} 条SQL`, key: 'apply' });
      fetchAll();
    } catch (e: unknown) {
      const errMsg = e instanceof Error ? e.message : '升级失败';
      message.error({ content: errMsg, key: 'apply' });
    } finally {
      setApplyingId(null);
    }
  };

  const handleRollback = async (versionId: number) => {
    setRollbackVersionId(versionId);
    try {
      message.loading({ content: '正在回滚，请勿操作...', key: 'rollback', duration: 0 });
      const result = await apiPost<{ rolled_back_from: string; rolled_back_to: string; backup_id: number }>(`/version/packages/${versionId}/rollback`);
      message.success({ content: `回滚成功：${result.rolled_back_from} → ${result.rolled_back_to}`, key: 'rollback' });
      fetchAll();
    } catch (e: unknown) {
      const errMsg = e instanceof Error ? e.message : '回滚失败';
      message.error({ content: errMsg, key: 'rollback' });
    } finally {
      setRollbackVersionId(null);
    }
  };

  const handleDeletePackage = async (pkgId: number) => {
    try {
      await apiDelete(`/version/packages/${pkgId}`);
      message.success('升级包已删除');
      fetchAll();
    } catch {
      message.error('删除失败');
    }
  };

  const statusMap: Record<string, { label: string; color: string }> = {
    UPLOADED: { label: '待应用', color: COLORS.pending },
    APPLIED: { label: '已应用', color: COLORS.confirmed },
    FAILED: { label: '失败', color: COLORS.danger },
    ROLLED_BACK: { label: '已回滚', color: COLORS.slate },
  };

  const pkgColumns = [
    { title: '上传时间', dataIndex: 'created_at', key: 'created_at', width: 160, render: formatDate },
    { title: '升级包', dataIndex: 'package_name', key: 'package_name', width: 180,
      render: (v: string) => <span style={{ fontWeight: 500 }}>{v}</span> },
    { title: '目标版本', dataIndex: 'target_version', key: 'target_version', width: 100,
      render: (v: string) => <Tag style={{ fontFamily: 'monospace' }}>{v}</Tag> },
    { title: '描述', dataIndex: 'description', key: 'description', width: 200,
      render: (v: string | null) => v || '-' },
    { title: '文件大小', dataIndex: 'file_size', key: 'file_size', width: 90, render: formatSize },
    { title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => {
        const info = statusMap[v] || { label: v, color: COLORS.textMuted };
        return <Tag color={info.color}>{info.label}</Tag>;
      } },
    { title: '错误信息', dataIndex: 'error_message', key: 'error_message', width: 180,
      render: (v: string | null) => v
        ? <Tooltip title={v}><span style={{ color: COLORS.danger, cursor: 'help', fontSize: 12 }}>{v.slice(0, 40)}...</span></Tooltip>
        : '-' },
    { title: '操作', key: 'actions', width: 200, fixed: 'right' as const,
      render: (_: unknown, r: UpgradePackage) => (
        <>
          {(r.status === 'UPLOADED' || r.status === 'FAILED') && (
            <Popconfirm
              title="确定应用此升级包？"
              description="系统将自动备份当前数据库，然后执行升级SQL脚本。"
              onConfirm={() => handleApply(r.id)}
              okText="应用升级"
              cancelText="取消"
            >
              <Button type="link" size="small" icon={<ThunderboltOutlined />}
                loading={applyingId === r.id}
                style={{ color: COLORS.confirmed, padding: '0 4px' }}>应用</Button>
            </Popconfirm>
          )}
          {r.status === 'APPLIED' && (
            <Popconfirm
              title="确定回滚此版本？"
              description="将恢复升级前的数据库备份，当前版本数据将被覆盖。"
              onConfirm={() => {
                // Find the version history entry for this target version to get its ID
                const versionEntry = versionHistory.find(v => v.version === r.target_version);
                if (versionEntry) {
                  handleRollback(versionEntry.id);
                } else {
                  message.error('未找到版本记录');
                }
              }}
              okText="确定回滚"
              cancelText="取消"
            >
              <Button type="link" size="small" icon={<RollbackOutlined />}
                loading={rollbackVersionId != null}
                style={{ color: COLORS.pending, padding: '0 4px' }}>回滚</Button>
            </Popconfirm>
          )}
          {r.status !== 'APPLIED' && (
            <Popconfirm title="确定删除该升级包？" onConfirm={() => handleDeletePackage(r.id)} okText="删除" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />} style={{ padding: '0 4px' }}>删除</Button>
            </Popconfirm>
          )}
        </>
      ) },
  ];

  const historyColumns = [
    { title: '版本号', dataIndex: 'version', key: 'version', width: 100,
      render: (v: string, r: VersionInfo) => (
        <span style={{ fontFamily: 'monospace', fontWeight: r.is_current ? 600 : 400 }}>{v}</span>
      ) },
    { title: '描述', dataIndex: 'description', key: 'description', width: 250 },
    { title: '关联备份', dataIndex: 'backup_id', key: 'backup_id', width: 80,
      render: (v: number | null) => v ? `#${v}` : '-' },
    { title: '创建时间', dataIndex: 'created_at', key: 'created_at', width: 160, render: formatDate },
    { title: '状态', key: 'current', width: 80,
      render: (_: unknown, r: VersionInfo) => r.is_current
        ? <Badge status="success" text="当前" />
        : <Text type="secondary">历史</Text> },
  ];

  return (
    <div>
      {/* Current Version Info */}
      <div style={{ marginBottom: 20, padding: '16px 20px', background: COLORS.cream, borderRadius: 10, border: `1px solid ${COLORS.border}` }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <RocketOutlined style={{ fontSize: 20, color: COLORS.sage }} />
            <div>
              <div style={{ fontSize: 13, color: COLORS.textMuted, marginBottom: 4 }}>当前系统版本</div>
              <div style={{ fontSize: 22, fontWeight: 600, fontFamily: 'monospace', color: COLORS.charcoal }}>
                {currentVersion?.version || '加载中...'}
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Upload
              beforeUpload={handleUpload}
              showUploadList={false}
              accept=".zip"
            >
              <Button icon={<UploadOutlined />} loading={uploading}
                style={{ borderColor: COLORS.sage, color: COLORS.sage }}>
                上传升级包
              </Button>
            </Upload>
            <Button icon={<ReloadOutlined />} onClick={fetchAll} loading={loading}>刷新</Button>
          </div>
        </div>
        {currentVersion?.description && (
          <div style={{ marginTop: 8, fontSize: 13, color: COLORS.textMuted, paddingLeft: 36 }}>
            {currentVersion.description}
          </div>
        )}
      </div>

      {/* Upgrade Packages Table */}
      <div style={{ marginBottom: 4 }}>
        <Text strong style={{ fontSize: 14 }}>升级包管理</Text>
      </div>
      <Table
        columns={pkgColumns}
        dataSource={packages}
        rowKey="id"
        size="small"
        loading={loading}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无升级包" /> }}
        scroll={{ x: 1100 }}
      />

      {/* Version History */}
      <div style={{ marginTop: 28, marginBottom: 4 }}>
        <Text strong style={{ fontSize: 14 }}><HistoryOutlined style={{ marginRight: 6 }} />版本历史</Text>
      </div>
      <Table
        columns={historyColumns}
        dataSource={versionHistory}
        rowKey="id"
        size="small"
        loading={loading}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无版本记录" /> }}
        scroll={{ x: 600 }}
      />

      <div style={{ marginTop: 16, padding: 12, background: COLORS.cream, borderRadius: 8, fontSize: 12, color: COLORS.textMuted }}>
        <p style={{ margin: '4px 0' }}><strong>升级包格式：</strong>ZIP文件，包含 manifest.json（版本号和描述）和 upgrade.sql（SQL迁移脚本）。</p>
        <p style={{ margin: '4px 0' }}><strong>升级流程：</strong>上传 → 应用（自动备份 → 执行SQL → 更新版本号）。</p>
        <p style={{ margin: '4px 0' }}><strong>回滚说明：</strong>回滚将恢复升级前的数据库备份，并回退版本号。</p>
      </div>
    </div>
  );
}

// ==================== Main Page ====================
export default function DataMaintenancePage() {
  const tabItems = [
    {
      key: 'backup',
      label: <span><DatabaseOutlined style={{ marginRight: 6 }} />备份管理</span>,
      children: <BackupTab />,
    },
    {
      key: 'version',
      label: <span><RocketOutlined style={{ marginRight: 6 }} />版本更新</span>,
      children: <VersionUpgradeTab />,
    },
  ];

  return (
    <Card
      title={<><SafetyCertificateOutlined style={{ marginRight: 8 }} />数据维护</>}
      styles={{ body: { padding: '16px 20px' } }}
    >
      <Tabs items={tabItems} type="card" />
    </Card>
  );
}
