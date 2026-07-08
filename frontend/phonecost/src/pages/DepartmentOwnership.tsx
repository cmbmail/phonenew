import { useState, useEffect, useCallback, useMemo } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Select, Row, Col, message, Empty, Input, Statistic, Tabs, Button, Dropdown, Progress, Modal, Form } from 'antd';
import { SearchOutlined, CameraOutlined, UploadOutlined, DownloadOutlined, EditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { DirectoryBatch, DirectoryEntry, DataSnapshot, ImportProgress } from '../types/import';
import { getDirectoryBatches, getSnapshots, getBillBatches, importDirectory, downloadDirectoryTemplate, getDirectoryProgress, updateDirectoryEntry } from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { apiGet } from '../lib/request';
import type { BillBatch } from '../types/bill';

export default function DepartmentOwnership() {
  const { t } = useTranslation();

  // Current data state
  const [batches, setBatches] = useState<DirectoryBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [entries, setEntries] = useState<DirectoryEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [uploading, setUploading] = useState(false);

  // Edit modal state
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingEntry, setEditingEntry] = useState<DirectoryEntry | null>(null);
  const [editLoading, setEditLoading] = useState(false);
  const [editForm] = Form.useForm();

  // Async import progress
  const { progress: importProgress, polling: importPolling, startPolling, percent: importPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(`部门归属导入完成：${p.total} 条`);
      fetchBatches();
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(`导入失败：${p.message || '未知错误'}`);
      setUploading(false);
    },
  });

  // Snapshot state
  const [snapshots, setSnapshots] = useState<DataSnapshot[]>([]);
  const [billBatches, setBillBatches] = useState<BillBatch[]>([]);
  const [snapshotsLoading, setSnapshotsLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('current');
  const [selectedSnapshotMonth, setSelectedSnapshotMonth] = useState<string | null>(null);
  const [snapshotEntries, setSnapshotEntries] = useState<DirectoryEntry[]>([]);
  const [snapshotEntriesLoading, setSnapshotEntriesLoading] = useState(false);
  const [snapshotSearch, setSnapshotSearch] = useState('');
  const [pageSize, setPageSize] = useState(50);

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try { setBatches(await getDirectoryBatches()); } catch { message.error(t('deptOwnership.fetchFailed')); } finally { setLoading(false); }
  }, [t]);

  const handleUpload = async (file: File) => {
    setUploading(true);
    try {
      const result = await importDirectory(file);
      startPolling(result.batch_id, getDirectoryProgress);
    } catch (err) {
      message.error(`导入失败：${err instanceof Error ? err.message : '未知错误'}`);
      setUploading(false);
    }
  };

  const fetchEntries = useCallback(async () => {
    if (!selectedBatchId) return;
    setEntriesLoading(true);
    try {
      const data = await apiGet<{ entries: DirectoryEntry[] }>(`/import/directory/entries/${selectedBatchId}`);
      setEntries(data.entries);
    } catch {
      message.error(t('deptOwnership.fetchFailed'));
    } finally {
      setEntriesLoading(false);
    }
  }, [selectedBatchId, t]);

  const fetchSnapshots = useCallback(async () => {
    setSnapshotsLoading(true);
    try {
      const [snaps, bills] = await Promise.all([getSnapshots(), getBillBatches()]);
      setSnapshots(snaps);
      setBillBatches(bills);
    } catch { message.error(t('deptOwnership.snapshotFetchFailed')); } finally { setSnapshotsLoading(false); }
  }, [t]);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);

  useEffect(() => {
    if (activeTab === 'snapshot') {
      fetchSnapshots();
    }
  }, [activeTab, fetchSnapshots]);

  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.id - a.id);
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  useEffect(() => { fetchEntries(); }, [fetchEntries]);

  // Edit handlers
  const handleEdit = (record: DirectoryEntry) => {
    setEditingEntry(record);
    editForm.setFieldsValue({
      dept_path: record.dept_path,
      alloc_dept: record.alloc_dept,
      org_code: record.org_code,
      cost_center: record.cost_center,
      remark: record.remark,
    });
    setEditModalOpen(true);
  };

  const handleEditSave = async () => {
    if (!editingEntry) return;
    try {
      const values = await editForm.validateFields();
      setEditLoading(true);
      await updateDirectoryEntry(editingEntry.id, {
        dept_path: values.dept_path,
        alloc_dept: values.alloc_dept || '',
        org_code: values.org_code || '',
        cost_center: values.cost_center || '',
        remark: values.remark || '',
      });
      message.success(t('deptOwnership.editSuccess'));
      setEditModalOpen(false);
      fetchEntries();
    } catch (err) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setEditLoading(false);
    }
  };

  // Snapshot month options
  const snapshotMonthOptions = useMemo(() => {
    const m = new Map<string, string>();
    snapshots.forEach(s => {
      const bill = billBatches.find(b => b.id === s.bill_batch_id);
      if (bill?.billing_month) m.set(bill.billing_month, bill.billing_month);
    });
    return [...m.keys()].sort().reverse().map(month => ({ label: month, value: month }));
  }, [snapshots, billBatches]);

  useEffect(() => {
    if (activeTab === 'snapshot' && snapshotMonthOptions.length > 0 && !selectedSnapshotMonth) {
      setSelectedSnapshotMonth(snapshotMonthOptions[0].value);
    }
  }, [activeTab, snapshotMonthOptions, selectedSnapshotMonth]);

  useEffect(() => {
    if (activeTab !== 'snapshot' || !selectedSnapshotMonth) return;
    const snap = snapshots.find(s => {
      const bill = billBatches.find(b => b.id === s.bill_batch_id);
      return bill?.billing_month === selectedSnapshotMonth;
    });
    if (!snap?.directory_batch_id) { setSnapshotEntries([]); return; }
    setSnapshotEntriesLoading(true);
    apiGet<{ entries: DirectoryEntry[] }>(`/import/directory/entries/${snap.directory_batch_id}`)
      .then(data => setSnapshotEntries(data.entries))
      .catch(() => { message.error(t('deptOwnership.fetchFailed')); setSnapshotEntries([]); })
      .finally(() => setSnapshotEntriesLoading(false));
  }, [activeTab, selectedSnapshotMonth, snapshots, billBatches, t]);

  // Search filter - current data
  const filteredEntries = useMemo(() => {
    const kw = search.trim().toLowerCase();
    if (!kw) return entries;
    return entries.filter(e =>
      String(e.phone_number || '').toLowerCase().includes(kw) ||
      String(e.dept_path || '').toLowerCase().includes(kw) ||
      String(e.alloc_dept || '').toLowerCase().includes(kw) ||
      String(e.org_code || '').toLowerCase().includes(kw) ||
      String(e.cost_center || '').toLowerCase().includes(kw) ||
      String(e.remark || '').toLowerCase().includes(kw)
    );
  }, [entries, search]);

  // Search filter - snapshot data
  const filteredSnapshotEntries = useMemo(() => {
    const kw = snapshotSearch.trim().toLowerCase();
    if (!kw) return snapshotEntries;
    return snapshotEntries.filter(e =>
      String(e.phone_number || '').toLowerCase().includes(kw) ||
      String(e.dept_path || '').toLowerCase().includes(kw) ||
      String(e.alloc_dept || '').toLowerCase().includes(kw) ||
      String(e.org_code || '').toLowerCase().includes(kw) ||
      String(e.cost_center || '').toLowerCase().includes(kw) ||
      String(e.remark || '').toLowerCase().includes(kw)
    );
  }, [snapshotEntries, snapshotSearch]);

  // Current snapshot info
  const selectedSnapshot = useMemo(() => {
    if (!selectedSnapshotMonth) return null;
    return snapshots.find(s => {
      const bill = billBatches.find(b => b.id === s.bill_batch_id);
      return bill?.billing_month === selectedSnapshotMonth;
    }) || null;
  }, [selectedSnapshotMonth, snapshots, billBatches]);

  // Columns for current data (with edit)
  const currentColumns = [
    { title: t('deptOwnership.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 200, ellipsis: true },
    { title: t('deptOwnership.allocDeptCol'), dataIndex: 'alloc_dept', key: 'alloc_dept', width: 120 },
    { title: t('deptOwnership.orgCodeCol'), dataIndex: 'org_code', key: 'org_code', width: 100 },
    { title: t('deptOwnership.costCenterCol'), dataIndex: 'cost_center', key: 'cost_center', width: 100 },
    { title: t('deptOwnership.remarkCol'), dataIndex: 'remark', key: 'remark', width: 150, ellipsis: true },
    {
      title: t('deptOwnership.editCol'), key: 'edit', width: 70, fixed: 'right' as const,
      render: (_: unknown, r: DirectoryEntry) => (
        <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(r)} />
      ),
    },
  ];

  // Columns for snapshot (read-only, no edit)
  const snapshotColumns = [
    { title: t('deptOwnership.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 200, ellipsis: true },
    { title: t('deptOwnership.allocDeptCol'), dataIndex: 'alloc_dept', key: 'alloc_dept', width: 120 },
    { title: t('deptOwnership.orgCodeCol'), dataIndex: 'org_code', key: 'org_code', width: 100 },
    { title: t('deptOwnership.costCenterCol'), dataIndex: 'cost_center', key: 'cost_center', width: 100 },
    { title: t('deptOwnership.remarkCol'), dataIndex: 'remark', key: 'remark', width: 150, ellipsis: true },
  ];

  const currentDataContent = (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <span style={{ marginRight: 8 }}>{t('deptOwnership.selectBatch')}</span>
          <Select style={{ width: 280 }} placeholder={t('deptOwnership.selectBatchPlaceholder')} loading={loading}
            value={selectedBatchId} onChange={setSelectedBatchId}
            options={[...batches].sort((a, b) => b.id - a.id).map(b => ({ label: `${b.batch_no} (${b.total_count}条)`, value: b.id }))} />
        </Col>
        <Col flex="auto" />
        <Col>
          <Dropdown menu={{ items: [
            { key: 'import', icon: <UploadOutlined />, label: '导入部门归属', disabled: uploading },
            { key: 'template', icon: <DownloadOutlined />, label: '下载模板' },
          ], onClick: ({ key }) => {
            if (key === 'import') document.getElementById('dept-upload-input')?.click();
            if (key === 'template') downloadDirectoryTemplate();
          } }}>
            <Button type="primary" icon={<UploadOutlined />} loading={uploading && !importPolling}>导入部门归属</Button>
          </Dropdown>
          <input type="file" accept=".xlsx,.xls" id="dept-upload-input" style={{ display: 'none' }} onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleUpload(f); e.target.value = ''; } }} />
          {importPolling && importProgress && (
            <Progress
              percent={importPercent}
              size="small"
              style={{ width: 200, marginLeft: 12, display: 'inline-block', verticalAlign: 'middle' }}
              format={() => importProgress.message || `${importProgress.processed}/${importProgress.total}`}
            />
          )}
        </Col>
      </Row>
      {selectedBatchId && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}><Statistic title={t('deptOwnership.totalCount')} value={filteredEntries.length} /></Col>
        </Row>
      )}
      {selectedBatchId && (
        <Input prefix={<SearchOutlined />} placeholder={t('deptOwnership.searchPlaceholder')} allowClear value={search}
          onChange={e => setSearch(e.target.value)} style={{ width: 360, marginBottom: 12 }} />
      )}
      {selectedBatchId && filteredEntries.length > 0 ? (
        <Table columns={currentColumns} dataSource={filteredEntries} rowKey="id" size="small" loading={entriesLoading}
          pagination={{ pageSize, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => t('common.paginationTotal', { total }), onChange: (_p, s) => setPageSize(s) }}
          scroll={{ x: 800 }} />
      ) : (!entriesLoading && <Empty description={t('deptOwnership.noData')} />)}
    </>
  );

  const snapshotContent = snapshots.length === 0 && !snapshotsLoading ? (
    <Empty description={t('deptOwnership.snapshotNoData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
  ) : (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <span style={{ marginRight: 8 }}>{t('deptOwnership.snapshotSelectMonth')}</span>
          <Select style={{ width: 160 }} placeholder={t('deptOwnership.snapshotMonthPlaceholder')} loading={snapshotsLoading}
            value={selectedSnapshotMonth} onChange={setSelectedSnapshotMonth}
            options={snapshotMonthOptions} />
        </Col>
        {selectedSnapshot && (
          <Col style={{ color: COLORS.textMuted, fontSize: 13 }}>
            {t('deptOwnership.snapshotBatchInfo', { directoryBatch: selectedSnapshot.directory_batch_id ?? '-', matched: selectedSnapshot.matched_count })}
          </Col>
        )}
      </Row>
      {selectedSnapshotMonth && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}><Statistic title={t('deptOwnership.totalCount')} value={filteredSnapshotEntries.length} /></Col>
        </Row>
      )}
      {selectedSnapshotMonth && (
        <Input prefix={<SearchOutlined />} placeholder={t('deptOwnership.searchPlaceholder')} allowClear value={snapshotSearch}
          onChange={e => setSnapshotSearch(e.target.value)} style={{ width: 360, marginBottom: 12 }} />
      )}
      {selectedSnapshotMonth && filteredSnapshotEntries.length > 0 ? (
        <Table columns={snapshotColumns} dataSource={filteredSnapshotEntries} rowKey="id" size="small" loading={snapshotEntriesLoading}
          pagination={{ pageSize, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => t('common.paginationTotal', { total }), onChange: (_p, s) => setPageSize(s) }}
          scroll={{ x: 700 }} />
      ) : (!snapshotEntriesLoading && selectedSnapshotMonth && <Empty description={t('deptOwnership.noData')} />)}
    </>
  );

  return (
    <div>
      <Card>
        <Tabs activeKey={activeTab} onChange={(key) => { setActiveTab(key); if (key === 'snapshot') setSelectedSnapshotMonth(null); }} items={[
          { key: 'current', label: t('deptOwnership.currentDataTab'), children: currentDataContent },
          { key: 'snapshot', label: <><CameraOutlined /> {t('deptOwnership.snapshotTab')}</>, children: snapshotContent },
        ]} />
      </Card>

      <Modal
        title={t('deptOwnership.editTitle')}
        open={editModalOpen}
        onOk={handleEditSave}
        onCancel={() => setEditModalOpen(false)}
        confirmLoading={editLoading}
        width={520}
      >
        <Form form={editForm} layout="vertical">
          <Form.Item name="dept_path" label={t('deptOwnership.deptPathCol')} rules={[{ required: true, message: t('deptOwnership.deptPathRequired') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="alloc_dept" label={t('deptOwnership.allocDeptCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="org_code" label={t('deptOwnership.orgCodeCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="cost_center" label={t('deptOwnership.costCenterCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="remark" label={t('deptOwnership.remarkCol')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
