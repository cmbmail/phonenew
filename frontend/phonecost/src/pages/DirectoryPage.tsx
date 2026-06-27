import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Tag, Row, Col, message, Empty, Input, Statistic, Upload, Button, Space, Tabs, DatePicker, Modal, Tooltip, Popconfirm } from 'antd';
import { SearchOutlined, UploadOutlined, ReloadOutlined, CameraOutlined, CheckCircleOutlined, SyncOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import type { DirectoryBatch, DirectoryEntry } from '../types/import';
import { importDirectory, getDirectoryBatches, setDirectoryMonth, getDirectorySnapshots, clearDirectoryException, syncDirectoryFromMatch, batchClearDirectoryException } from '../api/import';
import { COLORS } from '../theme/morandi';
import { apiGet } from '../lib/request';
import dayjs from 'dayjs';

const LEVEL_LABELS = ['集团', '一级分行', '二级分行/部门', '部门代码', '部门代码'];
const DIFF_BG = 'rgba(196,123,108,0.12)'; // subtle danger tint for differing fields

function splitDeptPath(deptPath: string): string[] {
  const parts = deptPath.split('-').map(s => s.trim()).filter(Boolean);
  return Array.from({ length: 5 }, (_, i) => parts[i] || '');
}

/** Build a map from phone_number -> non-exception entry for quick lookup */
function buildCurrentMap(entries: DirectoryEntry[]): Map<string, DirectoryEntry> {
  const map = new Map<string, DirectoryEntry>();
  for (const e of entries) {
    if (e.is_seconded !== 1 && e.phone_number) {
      if (!map.has(e.phone_number)) map.set(e.phone_number, e);
    }
  }
  return map;
}

interface FieldDiff {
  level0: boolean; level1: boolean; level2: boolean; level3: boolean; level4: boolean;
  username: boolean; extension: boolean; phone_number: boolean;
  hasDiff: boolean;
  matched: boolean;
}

function compareFields(ex: DirectoryEntry, current: DirectoryEntry | undefined): FieldDiff {
  if (!current) return { level0: false, level1: false, level2: false, level3: false, level4: false, username: false, extension: false, phone_number: false, hasDiff: false, matched: false };
  const eLevels = splitDeptPath(ex.dept_path);
  const cLevels = splitDeptPath(current.dept_path);
  const diffs = {
    level0: eLevels[0] !== cLevels[0],
    level1: eLevels[1] !== cLevels[1],
    level2: eLevels[2] !== cLevels[2],
    level3: eLevels[3] !== cLevels[3],
    level4: eLevels[4] !== cLevels[4],
    username: (ex.username || '') !== (current.username || ''),
    extension: (ex.extension || '') !== (current.extension || ''),
    phone_number: false, // phone_number is the matching key, always same
  };
  const hasDiff = Object.values(diffs).some(v => v);
  return { ...diffs, hasDiff, matched: true };
}

const DirectoryPage: React.FC = () => {
  const [batches, setBatches] = useState<DirectoryBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [entries, setEntries] = useState<DirectoryEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [uploading, setUploading] = useState(false);
  const [processingIds, setProcessingIds] = useState<Set<number>>(new Set());

  // Snapshot state
  const [snapshots, setSnapshots] = useState<DirectoryBatch[]>([]);
  const [snapshotsLoading, setSnapshotsLoading] = useState(false);
  const [selectedSnapshotMonth, setSelectedSnapshotMonth] = useState<string | null>(null);
  const [snapshotEntries, setSnapshotEntries] = useState<DirectoryEntry[]>([]);
  const [snapshotEntriesLoading, setSnapshotEntriesLoading] = useState(false);
  const [snapshotSearch, setSnapshotSearch] = useState('');
  const [exceptionSearch, setExceptionSearch] = useState('');

  // Create snapshot modal
  const [snapshotModalOpen, setSnapshotModalOpen] = useState(false);
  const [snapshotMonth, setSnapshotMonth] = useState<string>('');

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getDirectoryBatches();
      setBatches(data);
    } catch {
      message.error('获取批次列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchSnapshots = useCallback(async () => {
    setSnapshotsLoading(true);
    try {
      const data = await getDirectorySnapshots();
      setSnapshots(data);
    } catch {
      message.error('获取快照列表失败');
    } finally {
      setSnapshotsLoading(false);
    }
  }, []);

  const fetchEntries = useCallback(async () => {
    if (!selectedBatchId) { setEntries([]); return; }
    setEntriesLoading(true);
    try {
      const data = await apiGet<DirectoryEntry[]>(`/import/directory/entries/${selectedBatchId}`);
      setEntries(data);
    } catch {
      message.error('获取通讯录数据失败');
    } finally {
      setEntriesLoading(false);
    }
  }, [selectedBatchId]);

  useEffect(() => { fetchBatches(); fetchSnapshots(); }, [fetchBatches, fetchSnapshots]);
  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const latest = [...batches].sort((a, b) => b.id - a.id)[0];
      setSelectedBatchId(latest.id);
    }
  }, [batches, selectedBatchId]);
  useEffect(() => { fetchEntries(); }, [fetchEntries]);

  useEffect(() => {
    if (!selectedSnapshotMonth) { setSnapshotEntries([]); return; }
    const snap = snapshots.find(s => s.billing_month === selectedSnapshotMonth);
    if (!snap) { setSnapshotEntries([]); return; }
    setSnapshotEntriesLoading(true);
    apiGet<DirectoryEntry[]>(`/import/directory/entries/${snap.id}`)
      .then(setSnapshotEntries)
      .catch(() => message.error('获取快照数据失败'))
      .finally(() => setSnapshotEntriesLoading(false));
  }, [selectedSnapshotMonth, snapshots]);

  const handleUpload = async (file: File) => {
    setUploading(true);
    try {
      const result = await importDirectory(file);
      message.success(`通讯录导入成功：${result.total_count} 条，例外 ${result.seconded_count ?? 0} 条`);
      fetchBatches();
      if (result.batch_id) setSelectedBatchId(result.batch_id);
    } catch (err) {
      message.error(`导入失败：${err instanceof Error ? err.message : '未知错误'}`);
    } finally {
      setUploading(false);
    }
    return false;
  };

  const handleCreateSnapshot = async () => {
    if (!selectedBatchId || !snapshotMonth) { message.warning('请选择批次和月份'); return; }
    try {
      await setDirectoryMonth(selectedBatchId, snapshotMonth);
      message.success(`快照已创建：${snapshotMonth}`);
      setSnapshotModalOpen(false);
      setSnapshotMonth('');
      fetchBatches();
      fetchSnapshots();
      setSelectedSnapshotMonth(snapshotMonth);
    } catch (err) {
      message.error(`创建快照失败：${err instanceof Error ? err.message : '未知错误'}`);
    }
  };

  const handleClearException = async (id: number) => {
    setProcessingIds(prev => new Set(prev).add(id));
    try {
      await clearDirectoryException(id);
      message.success('已解除例外');
      fetchEntries();
    } catch { message.error('解除例外失败'); }
    finally { setProcessingIds(prev => { const s = new Set(prev); s.delete(id); return s; }); }
  };

  const handleSyncFromMatch = async (id: number) => {
    setProcessingIds(prev => new Set(prev).add(id));
    try {
      await syncDirectoryFromMatch(id);
      message.success('已同步当前数据到例外记录');
      fetchEntries();
    } catch (err) {
      message.error(`同步失败：${err instanceof Error ? err.message : '未知错误'}`);
    }
    finally { setProcessingIds(prev => { const s = new Set(prev); s.delete(id); return s; }); }
  };

  const handleBatchClear = async () => {
    const ids = exceptionEntries.map(e => e.id);
    if (ids.length === 0) return;
    try {
      const result = await batchClearDirectoryException(ids);
      message.success(`已批量解除例外：${result.cleared} 条`);
      fetchEntries();
    } catch { message.error('批量解除失败'); }
  };

  // Derived data
  const exceptionCount = entries.filter(e => e.is_seconded === 1).length;
  const filteredEntries = search
    ? entries.filter(e => e.phone_number.includes(search) || e.username.includes(search) || e.extension.includes(search) || e.dept_path.includes(search))
    : entries;

  const exceptionEntries = entries.filter(e => e.is_seconded === 1);
  const filteredExceptionEntries = exceptionSearch
    ? exceptionEntries.filter(e => e.phone_number.includes(exceptionSearch) || e.username.includes(exceptionSearch) || e.extension.includes(exceptionSearch) || e.dept_path.includes(exceptionSearch))
    : exceptionEntries;

  const currentMap = useMemo(() => buildCurrentMap(entries), [entries]);
  const diffsMap = useMemo(() => {
    const map = new Map<number, FieldDiff>();
    for (const ex of exceptionEntries) {
      const current = currentMap.get(ex.phone_number);
      map.set(ex.id, compareFields(ex, current));
    }
    return map;
  }, [exceptionEntries, currentMap]);

  const filteredSnapshotEntries = snapshotSearch
    ? snapshotEntries.filter(e => e.phone_number.includes(snapshotSearch) || e.username.includes(snapshotSearch) || e.extension.includes(snapshotSearch) || e.dept_path.includes(snapshotSearch))
    : snapshotEntries;

  const snapshotMonthOptions = [...new Set(snapshots.map(s => s.billing_month!))].sort().reverse().map(m => ({ label: m, value: m }));

  const matchedCount = exceptionEntries.filter(e => currentMap.has(e.phone_number)).length;
  const diffCount = [...diffsMap.values()].filter(d => d.hasDiff).length;

  // Column helpers
  const baseColumns = [
    { title: LEVEL_LABELS[0], key: 'level0', width: 140, fixed: 'left' as const,
      render: (_: unknown, r: DirectoryEntry) => splitDeptPath(r.dept_path)[0] || '-' },
    { title: LEVEL_LABELS[1], key: 'level1', width: 120,
      render: (_: unknown, r: DirectoryEntry) => splitDeptPath(r.dept_path)[1] || '-' },
    { title: LEVEL_LABELS[2], key: 'level2', width: 160,
      render: (_: unknown, r: DirectoryEntry) => splitDeptPath(r.dept_path)[2] || '-' },
    { title: LEVEL_LABELS[3], key: 'level3', width: 120,
      render: (_: unknown, r: DirectoryEntry) => splitDeptPath(r.dept_path)[3] || '-' },
    { title: LEVEL_LABELS[4], key: 'level4', width: 120,
      render: (_: unknown, r: DirectoryEntry) => splitDeptPath(r.dept_path)[4] || '-' },
    { title: '用户名称(员工ID)', dataIndex: 'username', key: 'username', width: 130 },
    { title: '分机号码', dataIndex: 'extension', key: 'extension', width: 100 },
    { title: '外线号码', dataIndex: 'phone_number', key: 'phone_number', width: 130 },
  ];

  const columns = [
    ...baseColumns,
    { title: '例外', dataIndex: 'is_seconded', key: 'is_seconded', width: 70,
      render: (v: number) => v === 1 ? <Tag color={COLORS.danger}>例外</Tag> : '-' },
  ];

  // Exception columns with diff highlighting
  const exceptionColumns = [
    { title: LEVEL_LABELS[0], key: 'level0', width: 140, fixed: 'left' as const,
      render: (_: unknown, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        const val = splitDeptPath(r.dept_path)[0] || '-';
        return <span style={diff?.level0 ? { background: DIFF_BG, borderRadius: 3, padding: '0 4px' } : undefined}>{val}</span>;
      }},
    { title: LEVEL_LABELS[1], key: 'level1', width: 120,
      render: (_: unknown, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        const val = splitDeptPath(r.dept_path)[1] || '-';
        return <span style={diff?.level1 ? { background: DIFF_BG, borderRadius: 3, padding: '0 4px' } : undefined}>{val}</span>;
      }},
    { title: LEVEL_LABELS[2], key: 'level2', width: 160,
      render: (_: unknown, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        const val = splitDeptPath(r.dept_path)[2] || '-';
        return <span style={diff?.level2 ? { background: DIFF_BG, borderRadius: 3, padding: '0 4px' } : undefined}>{val}</span>;
      }},
    { title: LEVEL_LABELS[3], key: 'level3', width: 120,
      render: (_: unknown, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        const val = splitDeptPath(r.dept_path)[3] || '-';
        return <span style={diff?.level3 ? { background: DIFF_BG, borderRadius: 3, padding: '0 4px' } : undefined}>{val}</span>;
      }},
    { title: LEVEL_LABELS[4], key: 'level4', width: 120,
      render: (_: unknown, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        const val = splitDeptPath(r.dept_path)[4] || '-';
        return <span style={diff?.level4 ? { background: DIFF_BG, borderRadius: 3, padding: '0 4px' } : undefined}>{val}</span>;
      }},
    { title: '用户名称(员工ID)', dataIndex: 'username', key: 'username', width: 130,
      render: (v: string, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        return <span style={diff?.username ? { background: DIFF_BG, borderRadius: 3, padding: '0 4px' } : undefined}>{v || '-'}</span>;
      }},
    { title: '分机号码', dataIndex: 'extension', key: 'extension', width: 100,
      render: (v: string, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        return <span style={diff?.extension ? { background: DIFF_BG, borderRadius: 3, padding: '0 4px' } : undefined}>{v || '-'}</span>;
      }},
    { title: '外线号码', dataIndex: 'phone_number', key: 'phone_number', width: 130 },
    { title: '例外关键词', dataIndex: 'seconded_keyword', key: 'seconded_keyword', width: 100,
      render: (v: string) => v ? <Tag color={COLORS.pending}>{v}</Tag> : '-' },
    { title: '匹配', key: 'match', width: 80,
      render: (_: unknown, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        if (!diff?.matched) return <Tooltip title="未匹配到当前数据"><ExclamationCircleOutlined style={{ color: COLORS.textMuted }} /></Tooltip>;
        if (diff.hasDiff) return <Tooltip title="有字段差异（高亮显示）"><ExclamationCircleOutlined style={{ color: COLORS.danger }} /></Tooltip>;
        return <Tooltip title="完全匹配"><CheckCircleOutlined style={{ color: COLORS.confirmed }} /></Tooltip>;
      }},
    { title: '操作', key: 'actions', width: 180, fixed: 'right' as const,
      render: (_: unknown, r: DirectoryEntry) => {
        const diff = diffsMap.get(r.id);
        const loading = processingIds.has(r.id);
        return (
          <Space size="small">
            <Popconfirm title="解除例外标记？" onConfirm={() => handleClearException(r.id)}>
              <Button size="small" icon={<CheckCircleOutlined />} loading={loading}>解除例外</Button>
            </Popconfirm>
            {diff?.matched && diff.hasDiff && (
              <Popconfirm title="将当前数据同步到此例外记录？" onConfirm={() => handleSyncFromMatch(r.id)}>
                <Button size="small" type="primary" icon={<SyncOutlined />} loading={loading}>同步数据</Button>
              </Popconfirm>
            )}
          </Space>
        );
      }},
  ];

  // Current data tab content
  const currentDataContent = (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Space>
            <span>批次：</span>
            <Select style={{ width: 300 }} placeholder="选择通讯录批次" loading={loading} value={selectedBatchId} onChange={setSelectedBatchId}
              options={[...batches].sort((a, b) => b.id - a.id).map(b => ({ label: `${b.batch_no}${b.billing_month ? ` [${b.billing_month}]` : ''} (${b.total_count}条)`, value: b.id }))} />
            <Button icon={<ReloadOutlined />} onClick={fetchBatches} loading={loading} />
          </Space>
        </Col>
        <Col flex="auto" />
        <Col>
          <Space>
            <Button icon={<CameraOutlined />} onClick={() => setSnapshotModalOpen(true)} disabled={!selectedBatchId}>制作快照</Button>
            <Upload accept=".xlsx,.xls" showUploadList={false} beforeUpload={handleUpload} disabled={uploading}>
              <Button type="primary" icon={<UploadOutlined />} loading={uploading}>导入通讯录</Button>
            </Upload>
          </Space>
        </Col>
      </Row>
      {selectedBatchId && (
        <Row gutter={16} style={{ marginBottom: 12 }}>
          <Col span={4}><Statistic title="总条数" value={filteredEntries.length} /></Col>
          <Col span={4}><Statistic title="例外数" value={exceptionCount} valueStyle={{ color: exceptionCount > 0 ? COLORS.danger : undefined }} /></Col>
        </Row>
      )}
      {selectedBatchId && <Input prefix={<SearchOutlined />} placeholder="搜索号码/员工ID/分机/部门" allowClear value={search} onChange={e => setSearch(e.target.value)} style={{ width: 400, marginBottom: 12 }} />}
      {selectedBatchId && filteredEntries.length > 0 ? (
        <Table columns={columns} dataSource={filteredEntries} rowKey="id" size="small" loading={entriesLoading}
          pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => `共 ${total} 条` }} scroll={{ x: 1200 }} />
      ) : (!entriesLoading && <Empty description={selectedBatchId ? '无匹配数据' : '请选择批次或导入通讯录'} />)}
    </>
  );

  // Exception tab content
  const exceptionContent = !selectedBatchId ? (
    <Empty description="请先在当前数据 Tab 选择批次" image={Empty.PRESENTED_IMAGE_SIMPLE} />
  ) : exceptionEntries.length === 0 ? (
    <Empty description="当前批次无例外号码" image={Empty.PRESENTED_IMAGE_SIMPLE} />
  ) : (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col span={4}><Statistic title="例外总数" value={exceptionEntries.length} valueStyle={{ color: COLORS.danger }} /></Col>
        <Col span={4}><Statistic title="已匹配" value={matchedCount} valueStyle={{ color: COLORS.confirmed }} /></Col>
        <Col span={4}><Statistic title="存在差异" value={diffCount} valueStyle={{ color: diffCount > 0 ? COLORS.pending : undefined }} /></Col>
      </Row>
      <Row gutter={16} align="middle" style={{ marginBottom: 12 }}>
        <Col>
          <Input prefix={<SearchOutlined />} placeholder="搜索号码/员工ID/分机/部门" allowClear value={exceptionSearch} onChange={e => setExceptionSearch(e.target.value)} style={{ width: 400 }} />
        </Col>
        <Col flex="auto" />
        <Col>
          <Popconfirm title={`确认批量解除全部 ${exceptionEntries.length} 条例外？`} onConfirm={handleBatchClear}>
            <Button danger>批量解除例外</Button>
          </Popconfirm>
        </Col>
      </Row>
      <Table columns={exceptionColumns} dataSource={filteredExceptionEntries} rowKey="id" size="small" loading={entriesLoading}
        pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => `共 ${total} 条` }} scroll={{ x: 1500 }} />
      <div style={{ marginTop: 8, color: COLORS.textMuted, fontSize: 12 }}>
        <ExclamationCircleOutlined style={{ marginRight: 4 }} />
        高亮字段表示例外记录与当前数据（按外线号码匹配）不一致，可选择「同步数据」将当前数据覆盖到例外记录，或「解除例外」取消例外标记。
      </div>
    </>
  );

  // Snapshot tab content
  const snapshotContent = snapshots.length === 0 && !snapshotsLoading ? (
    <Empty description="暂无快照。在当前数据 Tab 中点击「制作快照」创建。" image={Empty.PRESENTED_IMAGE_SIMPLE} />
  ) : (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Space><span>月份：</span><Select style={{ width: 160 }} placeholder="选择月份" loading={snapshotsLoading} value={selectedSnapshotMonth} onChange={setSelectedSnapshotMonth} options={snapshotMonthOptions} /></Space>
        </Col>
      </Row>
      {selectedSnapshotMonth && (
        <Row gutter={16} style={{ marginBottom: 12 }}>
          <Col span={4}><Statistic title="总条数" value={filteredSnapshotEntries.length} /></Col>
          <Col span={4}><Statistic title="例外数" value={snapshotEntries.filter(e => e.is_seconded === 1).length} valueStyle={{ color: snapshotEntries.some(e => e.is_seconded === 1) ? COLORS.danger : undefined }} /></Col>
        </Row>
      )}
      {selectedSnapshotMonth && <Input prefix={<SearchOutlined />} placeholder="搜索号码/员工ID/分机/部门" allowClear value={snapshotSearch} onChange={e => setSnapshotSearch(e.target.value)} style={{ width: 400, marginBottom: 12 }} />}
      {selectedSnapshotMonth && filteredSnapshotEntries.length > 0 ? (
        <Table columns={columns} dataSource={filteredSnapshotEntries} rowKey="id" size="small" loading={snapshotEntriesLoading}
          pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => `共 ${total} 条` }} scroll={{ x: 1200 }} />
      ) : (!snapshotEntriesLoading && <Empty description={selectedSnapshotMonth ? '该月份无数据' : '请选择月份'} />)}
    </>
  );

  return (
    <Card title="通讯录" styles={{ body: { padding: '16px 20px' } }}>
      <Tabs items={[
        { key: 'current', label: '当前数据', children: currentDataContent },
        { key: 'exception', label: `例外号码 (${exceptionEntries.length})`, children: exceptionContent },
        { key: 'snapshot', label: '快照', children: snapshotContent },
      ]} />
      <Modal title="制作快照" open={snapshotModalOpen} onOk={handleCreateSnapshot} onCancel={() => { setSnapshotModalOpen(false); setSnapshotMonth(''); }} okText="确定" okButtonProps={{ disabled: !snapshotMonth }}>
        <div style={{ marginBottom: 12 }}>将当前选中批次的通讯录数据标记为指定月份的快照，方便后续按月查看。</div>
        <DatePicker picker="month" style={{ width: '100%' }} placeholder="选择年月" onChange={(_, dateString) => setSnapshotMonth(typeof dateString === 'string' ? dateString : '')} disabledDate={(current) => current && current > dayjs()} />
      </Modal>
    </Card>
  );
};

export default DirectoryPage;
