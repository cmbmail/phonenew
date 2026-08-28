import { useState, useEffect, useCallback } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Row, Col, message, Input, Button, Popconfirm, Space, Modal, Form, Progress, DatePicker, Select } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, DeleteOutlined, PlusOutlined, ExportOutlined, EditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { DirectoryBatch, DirectoryEntry, ImportProgress } from '../types/import';
import {
  importDirectory,
  getDirectoryBatches,
  downloadDirectoryTemplate,
  getDirectoryProgress,
  addDirectoryEntry,
  updateDirectoryEntry,
  deleteDirectoryEntry,
  exportAllDirectoryEntries,
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';
import { apiGet, apiDelete } from '../lib/request';
import dayjs from 'dayjs';

const DirectoryPage: React.FC = () => {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);
  const isAdmin = useAuthStore((s) => s.role === 1);

  // ==================== Batch list state ====================
  const [batches, setBatches] = useState<DirectoryBatch[]>([]);
  const [batchLoading, setBatchLoading] = useState(false);
  const [selectedBatch, setSelectedBatch] = useState<DirectoryBatch | null>(null);
  const [batchPageSize, setBatchPageSize] = useState(6);

  // Month filter
  const [availableMonths, setAvailableMonths] = useState<string[]>([]);
  const [selectedMonth, setSelectedMonth] = useState<string | undefined>(undefined);

  // Import
  const [uploading, setUploading] = useState(false);
  const [importMonthModal, setImportMonthModal] = useState(false);
  const [importBillingMonth, setImportBillingMonth] = useState<string>(dayjs().format('YYYY-MM'));

  // Async import progress
  const { progress: importProgress, polling: importPolling, startPolling, percent: importPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(t('import.directoryImportSuccess', { total: p.total, seconded: p.seconded_count ?? 0 }));
      fetchBatches();
      fetchMonths();
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(t('bill.importFailedMsg', { error: p.message || t('common.unknown') }));
      setUploading(false);
    },
  });

  // Add entry modal
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [addLoading, setAddLoading] = useState(false);
  const [addForm] = Form.useForm();

  // Edit entry modal
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editLoading, setEditLoading] = useState(false);
  const [editingEntry, setEditingEntry] = useState<DirectoryEntry | null>(null);
  const [editForm] = Form.useForm();

  // Delete
  const [deletingIds, setDeletingIds] = useState<Set<number>>(new Set());

  // ==================== Entry detail state ====================
  const [entries, setEntries] = useState<DirectoryEntry[]>([]);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');

  // ==================== Data fetching ====================

  const fetchMonths = useCallback(async () => {
    try {
      const data = await apiGet<string[]>('/import/directory/months');
      setAvailableMonths(data);
    } catch { /* silent */ }
  }, []);

  const fetchBatches = useCallback(async () => {
    setBatchLoading(true);
    try {
      const data = await getDirectoryBatches(selectedMonth);
      setBatches(data);
      if (!selectedBatch && data.length > 0) {
        setSelectedBatch(data[data.length - 1]);
      }
    } catch {
      message.error(t('import.fetchFailed'));
    } finally {
      setBatchLoading(false);
    }
  }, [t, selectedMonth]);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);
  useEffect(() => { fetchMonths(); }, [fetchMonths]);

  // Fetch entries for selected batch
  const fetchEntries = useCallback(async (keyword = '') => {
    if (!selectedBatch) { setEntries([]); return; }
    setEntriesLoading(true);
    try {
      const data = await apiGet<{ entries: DirectoryEntry[] }>(`/import/directory/entries/${selectedBatch.id}`);
      let filtered = data.entries || [];
      if (keyword) {
        const kw = keyword.toLowerCase();
        filtered = filtered.filter(e =>
          (e.dept_path || '').toLowerCase().includes(kw) ||
          (e.username || '').toLowerCase().includes(kw) ||
          (e.phone_number || '').toLowerCase().includes(kw) ||
          (e.extension || '').toLowerCase().includes(kw)
        );
      }
      setEntries(filtered);
    } catch {
      message.error(t('import.fetchFailed'));
    } finally {
      setEntriesLoading(false);
    }
  }, [selectedBatch, t]);

  useEffect(() => { fetchEntries(appliedSearch); }, [fetchEntries, appliedSearch]);

  // ==================== Handlers ====================

  const handleImportClick = () => {
    setImportMonthModal(true);
  };

  const handleConfirmMonth = () => {
    if (!importBillingMonth) {
      message.warning(t('directory.selectMonthFirst'));
      return;
    }
    setImportMonthModal(false);
    // Trigger file picker after closing modal
    setTimeout(() => {
      document.getElementById('directory-upload-input')?.click();
    }, 100);
  };

  const handleFileSelected = async (file: File) => {
    const month = importBillingMonth;
    setUploading(true);
    try {
      const result = await importDirectory(file, month);
      startPolling(result.batch_id, getDirectoryProgress);
    } catch (err) {
      message.error(t('bill.importFailedMsg', { error: err instanceof Error ? err.message : t('common.unknown') }));
      setUploading(false);
    }
  };

  const handleDeleteBatch = async (batchId: number) => {
    try {
      await apiDelete(`/import/directory/batches/${batchId}`);
      message.success(t('directory.deleteSuccess'));
      if (selectedBatch?.id === batchId) {
        setSelectedBatch(null);
        setEntries([]);
      }
      fetchBatches();
      fetchMonths();
    } catch {
      message.error(t('directory.deleteFailed'));
    }
  };

  const handleSearch = () => {
    setAppliedSearch(search);
  };

  // Add entry
  const handleAddOk = async () => {
    try {
      const values = await addForm.validateFields();
      setAddLoading(true);
      await addDirectoryEntry(values);
      message.success(t('directory.addSuccess'));
      setAddModalOpen(false);
      addForm.resetFields();
      fetchBatches();
      fetchMonths();
    } catch (err) {
      if (err instanceof Error) message.error(t('directory.addFailed', { error: err.message }));
    } finally {
      setAddLoading(false);
    }
  };

  // Edit entry
  const handleEdit = (record: DirectoryEntry) => {
    editForm.setFieldsValue({
      dept_path: record.dept_path || '',
      username: record.username || '',
      extension: record.extension || '',
      phone_number: record.phone_number || '',
      remark: record.remark || '',
    });
    setEditingEntry(record);
    setEditModalOpen(true);
  };

  const handleEditOk = async () => {
    if (!editingEntry) return;
    try {
      const values = await editForm.validateFields();
      setEditLoading(true);
      await updateDirectoryEntry(editingEntry.id, {
        dept_path: values.dept_path,
        remark: values.remark,
      });
      message.success(t('directory.editSuccess'));
      setEditModalOpen(false);
      setEditingEntry(null);
      fetchEntries(appliedSearch);
    } catch (err) {
      if (err instanceof Error) message.error(t('directory.editFailed', { error: err.message }));
    } finally {
      setEditLoading(false);
    }
  };

  // Delete entry
  const handleDeleteEntry = async (id: number) => {
    setDeletingIds(prev => new Set(prev).add(id));
    try {
      await deleteDirectoryEntry(id);
      message.success(t('directory.entryDeleteSuccess'));
      fetchEntries(appliedSearch);
    } catch {
      message.error(t('directory.entryDeleteFailed'));
    } finally {
      setDeletingIds(prev => { const s = new Set(prev); s.delete(id); return s; });
    }
  };

  // Export
  const handleExport = () => {
    exportAllDirectoryEntries();
    message.info(t('directory.exportStarted'));
  };

  // ==================== Batch list columns ====================

  const batchColumns = [
    {
      title: t('import.month'), dataIndex: 'billing_month', key: 'billing_month', width: 140,
      render: (v: string) => (
        <span style={{ fontWeight: selectedBatch?.id === batches.find(b => b.billing_month === v)?.id ? 600 : 400 }}>
          {v || t('allocationDept.monthNotSet')}
        </span>
      ),
    },
    {
      title: t('directory.totalCountCol'), dataIndex: 'total_count', key: 'total_count', width: 120,
      render: (v: number) => (
        <span style={{ fontWeight: 500 }}>
          {v != null ? v.toLocaleString() : '-'}
          <span style={{ color: COLORS.textMuted, fontSize: 12, marginLeft: 6 }}>
            {t('directory.totalCountCol')}
          </span>
        </span>
      ),
    },
    {
      title: t('directory.secondedCountCol'), dataIndex: 'seconded_count', key: 'seconded_count', width: 100,
      render: (v: number) => v != null && v > 0 ? <span style={{ color: COLORS.accent }}>{v}</span> : '-',
    },
    {
      title: t('directory.importTimeCol'), dataIndex: 'created_at', key: 'created_at', width: 130,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
    },
    {
      title: t('common.delete'), key: 'delete', width: 70,
      render: (_: unknown, record: DirectoryBatch) => isAdmin ? (
        <Popconfirm
          title={t('directory.deleteConfirm')}
          description={t('directory.deleteConfirmDesc', { batchNo: record.batch_no })}
          onConfirm={(e) => { e?.stopPropagation(); handleDeleteBatch(record.id); }}
          onCancel={(e) => e?.stopPropagation()}
          okText={t('common.confirm')}
          cancelText={t('common.cancel')}
          okButtonProps={{ danger: true }}
        >
          <Button size="small" danger type="text" icon={<DeleteOutlined />}
            onClick={(e) => e.stopPropagation()} />
        </Popconfirm>
      ) : null,
    },
  ];

  // ==================== Detail columns ====================

  const detailColumns = [
    { title: t('directory.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 280, ellipsis: true,
      render: (v: string) => v || '-' },
    { title: t('directory.usernameCol'), dataIndex: 'username', key: 'username', width: 100,
      render: (v: string) => v || '-' },
    { title: t('directory.extensionCol'), dataIndex: 'extension', key: 'extension', width: 100,
      render: (v: string) => v || '-' },
    { title: t('directory.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 130,
      render: (v: string) => v || '-' },
    { title: t('directory.remarkCol'), dataIndex: 'remark', key: 'remark', width: 160, ellipsis: true,
      render: (v: string) => v || '-' },
    ...(canEdit ? [{
      title: t('directory.actionsCol'), key: 'actions', width: 120, fixed: 'right' as const,
      render: (_: unknown, record: DirectoryEntry) => (
        <Space size={0}>
          <Button size="small" type="text" icon={<EditOutlined />}
            onClick={(e) => { e.stopPropagation(); handleEdit(record); }} />
          <Popconfirm
            title={t('directory.entryDeleteConfirm')}
            onConfirm={() => handleDeleteEntry(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
          >
            <Button size="small" danger type="text" icon={<DeleteOutlined />}
              loading={deletingIds.has(record.id)}
              onClick={(e) => e.stopPropagation()} />
          </Popconfirm>
        </Space>
      ),
    }] : []),
  ];

  // ==================== Render ====================

  return (
    <div>
      {/* Top toolbar: month filter + import/export */}
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Select
            style={{ width: 150 }}
            placeholder={t('allocationDept.filterByMonth')}
            allowClear
            value={selectedMonth}
            onChange={(v) => setSelectedMonth(v)}
            options={availableMonths.map(m => ({ label: m, value: m }))}
          />
        </Col>
        <Col flex="auto" />
        <Col>
          <Space>
            {canEdit && (
              <Button icon={<PlusOutlined />} onClick={() => setAddModalOpen(true)}>
                {t('directory.addLabel')}
              </Button>
            )}
            <Button icon={<UploadOutlined />} onClick={handleImportClick} loading={uploading && !importPolling} disabled={uploading}>
              {t('directory.importLabel')}
            </Button>
            <Button icon={<DownloadOutlined />} onClick={() => downloadDirectoryTemplate()}>
              {t('directory.downloadTemplate')}
            </Button>
            <input type="file" accept=".xlsx,.xls" id="directory-upload-input" style={{ display: 'none' }}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleFileSelected(f); e.target.value = ''; } }} />
            {importPolling && importProgress && (
              <Progress
                percent={importPercent}
                size="small"
                style={{ width: 160, display: 'inline-block', verticalAlign: 'middle' }}
                format={() => `${importProgress.processed}/${importProgress.total}`}
              />
            )}
            <Button icon={<ExportOutlined />} onClick={handleExport}>
              {t('directory.exportLabel')}
            </Button>
          </Space>
        </Col>
      </Row>

      {/* Batch list card */}
      <Card>
        <Table
          columns={batchColumns}
          dataSource={batches}
          rowKey="id"
          size="small"
          loading={batchLoading}
          pagination={{
            pageSize: batchPageSize,
            showSizeChanger: true,
            pageSizeOptions: ['6', '10', '20'],
            showTotal: (total) => t('common.paginationTotal', { total }),
            onChange: (_p, s) => setBatchPageSize(s),
          }}
          onRow={(record) => ({
            onClick: () => {
              setSelectedBatch(record);
              setSearch('');
              setAppliedSearch('');
            },
            style: {
              cursor: 'pointer',
              background: selectedBatch?.id === record.id ? 'rgba(139, 157, 158, 0.08)' : undefined,
            },
          })}
        />
      </Card>

      {/* Entry detail below selected batch */}
      {selectedBatch && (
        <Card
          style={{ marginTop: 16 }}
          title={selectedBatch.billing_month
            ? t('directory.monthResults', { month: selectedBatch.billing_month })
            : t('directory.batchResults', { batch: selectedBatch.batch_no })}
          extra={
            <Space>
              <Input
                prefix={<SearchOutlined />}
                placeholder={t('directory.searchPlaceholder')}
                allowClear
                size="small"
                style={{ width: 220 }}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onPressEnter={handleSearch}
              />
              <Button size="small" type="primary" onClick={handleSearch} icon={<SearchOutlined />}>
                {t('common.search')}
              </Button>
            </Space>
          }
        >
          <Table
            columns={detailColumns}
            dataSource={entries}
            rowKey="id"
            size="small"
            loading={entriesLoading}
            scroll={{ x: 900 }}
            pagination={{
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100'],
              showTotal: (total) => t('common.paginationTotal', { total }),
            }}
          />
        </Card>
      )}

      {!selectedBatch && batches.length === 0 && (
        <Card style={{ marginTop: 16 }}>
          <div style={{ textAlign: 'center', padding: 24, color: COLORS.textMuted }}>
            {t('directory.noBatchHint')}
          </div>
        </Card>
      )}

      {/* Add entry modal */}
      <Modal
        title={t('directory.addTitle')}
        open={addModalOpen}
        onOk={handleAddOk}
        onCancel={() => { setAddModalOpen(false); addForm.resetFields(); }}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={addLoading}
        width={560}
        destroyOnClose
      >
        <Form form={addForm} layout="vertical" preserve={false}>
          <Form.Item name="dept_path" label={t('directory.deptPathCol')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="username" label={t('directory.usernameCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="extension" label={t('directory.extensionCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="phone_number" label={t('directory.phoneCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="remark" label={t('directory.remarkCol')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit entry modal */}
      <Modal
        title={t('directory.editTitle')}
        open={editModalOpen}
        onOk={handleEditOk}
        onCancel={() => { setEditModalOpen(false); setEditingEntry(null); }}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={editLoading}
        width={560}
        destroyOnClose
      >
        <Form form={editForm} layout="vertical" preserve={false}>
          <Form.Item name="dept_path" label={t('directory.deptPathCol')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="username" label={t('directory.usernameCol')}>
            <Input disabled />
          </Form.Item>
          <Form.Item name="extension" label={t('directory.extensionCol')}>
            <Input disabled />
          </Form.Item>
          <Form.Item name="phone_number" label={t('directory.phoneCol')}>
            <Input disabled />
          </Form.Item>
          <Form.Item name="remark" label={t('directory.remarkCol')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>

      {/* Import month picker modal */}
      <Modal
        title={t('directory.importMonthTitle')}
        open={importMonthModal}
        onOk={handleConfirmMonth}
        onCancel={() => setImportMonthModal(false)}
        okText={t('common.confirm')}
        okButtonProps={{ disabled: !importBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('directory.importMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          format="YYYY年MM月"
          value={importBillingMonth ? dayjs(importBillingMonth, 'YYYY-MM') : null}
          onChange={(date) => setImportBillingMonth(date ? date.format('YYYY-MM') : '')}
          allowClear={false}
        />
      </Modal>
    </div>
  );
};

export default DirectoryPage;
