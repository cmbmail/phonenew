import { useState, useEffect, useCallback } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Tag, Row, Col, message, Input, Button, Dropdown, Progress, Popconfirm, Space, Modal, Select, Form, DatePicker } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, EditOutlined, PlusOutlined, ExportOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { OwnershipBatch, OwnershipEntry, ImportProgress } from '../types/import';
import {
  importOwnership,
  downloadOwnershipTemplate,
  getOwnershipProgress,
  getOwnershipBatches,
  getOwnershipMonths,
  getOwnershipEntriesByMonth,
  deleteOwnershipBatch,
  addOwnershipEntry,
  updateOwnershipEntry,
  exportBranchOwnership,
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';
import dayjs from 'dayjs';

const BranchOwnershipPage: React.FC = () => {
  const { t } = useTranslation();
  const isAdmin = useAuthStore((s) => s.role === 1);

  // ==================== Batch list state ====================
  const [batches, setBatches] = useState<OwnershipBatch[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBatch, setSelectedBatch] = useState<OwnershipBatch | null>(null);
  const [batchPageSize, setBatchPageSize] = useState(6);

  // Month filter
  const [availableMonths, setAvailableMonths] = useState<string[]>([]);
  const [selectedMonth, setSelectedMonth] = useState<string | undefined>(undefined);

  // Import
  const [uploading, setUploading] = useState(false);
  const [importMonthModal, setImportMonthModal] = useState<{ open: boolean; file: File | null }>({ open: false, file: null });
  const [importBillingMonth, setImportBillingMonth] = useState<string>(dayjs().format('YYYY-MM'));

  // Async import progress
  const { progress: importProgress, polling: importPolling, startPolling, percent: importPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(t('phoneOwnership.importSuccess', { total: p.total, exceptionCount: p.exception_count ?? 0 }));
      fetchBatches();
      fetchMonths();
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(t('phoneOwnership.importFailed', { error: p.message || t('common.unknown') }));
      setUploading(false);
    },
  });

  // ==================== Entry detail state ====================
  const [entries, setEntries] = useState<OwnershipEntry[]>([]);
  const [_entriesTotal, setEntriesTotal] = useState(0);
  const [entriesPage, setEntriesPage] = useState(0);
  const [entriesPageSize, setEntriesPageSize] = useState(50);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [filteredCount, setFilteredCount] = useState(0);

  // Edit modal
  const [editModal, setEditModal] = useState<{ open: boolean; entry: OwnershipEntry | null }>({ open: false, entry: null });
  const [editLoading, setEditLoading] = useState(false);
  const [editForm] = Form.useForm();

  // Add modal
  const [addModal, setAddModal] = useState(false);
  const [addLoading, setAddLoading] = useState(false);
  const [addForm] = Form.useForm();

  // ==================== Data fetching ====================

  const fetchMonths = useCallback(async () => {
    try {
      const data = await getOwnershipMonths();
      setAvailableMonths(data);
    } catch { /* silent */ }
  }, []);

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getOwnershipBatches(selectedMonth);
      setBatches(data);
      if (!selectedBatch && data.length > 0) {
        setSelectedBatch(data[data.length - 1]);
      }
    } catch {
      message.error(t('phoneOwnership.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t, selectedMonth]);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);
  useEffect(() => { fetchMonths(); }, [fetchMonths]);

  // Fetch entries for the selected batch (by billing_month)
  const fetchEntries = useCallback(async (batchId: number, keyword = '', page = 0, size = 50) => {
    setEntriesLoading(true);
    try {
      const batch = batches.find(b => b.id === batchId);
      const billingMonth = batch?.billing_month || '';
      if (!billingMonth) {
        setEntries([]);
        setEntriesTotal(0);
        setFilteredCount(0);
        setEntriesLoading(false);
        return;
      }
      const data = await getOwnershipEntriesByMonth(billingMonth, keyword || undefined, page, size);
      setEntries(data.entries);
      setEntriesTotal(data.total);
      setFilteredCount(data.filtered ?? data.total);
      setEntriesPage(data.page);
      setEntriesPageSize(data.size);
    } catch {
      message.error(t('phoneOwnership.fetchFailed'));
    } finally {
      setEntriesLoading(false);
    }
  }, [batches, t]);

  // When a batch is selected, load entries
  useEffect(() => {
    if (selectedBatch) {
      fetchEntries(selectedBatch.id, appliedSearch);
    } else {
      setEntries([]);
      setEntriesTotal(0);
      setFilteredCount(0);
    }
  }, [selectedBatch, fetchEntries]);

  // ==================== Handlers ====================

  const handleFileSelected = (file: File) => {
    setImportMonthModal({ open: true, file });
  };

  const handleConfirmImport = async () => {
    if (!importMonthModal.file || !importBillingMonth) {
      message.warning(t('phoneOwnership.selectMonthFirst'));
      return;
    }
    const file = importMonthModal.file;
    const month = importBillingMonth;
    setImportMonthModal({ open: false, file: null });
    setUploading(true);
    try {
      const result = await importOwnership(file, month);
      startPolling(result.batch_id, getOwnershipProgress);
    } catch (err) {
      message.error(t('phoneOwnership.importFailed', { error: err instanceof Error ? err.message : t('common.unknown') }));
      setUploading(false);
    }
  };

  const handleDelete = async (batchId: number) => {
    try {
      await deleteOwnershipBatch(batchId);
      message.success(t('phoneOwnership.deleteSuccess'));
      if (selectedBatch?.id === batchId) {
        setSelectedBatch(null);
        setEntries([]);
      }
      fetchBatches();
      fetchMonths();
    } catch {
      message.error(t('phoneOwnership.deleteFailed'));
    }
  };

  const handleSearch = () => {
    setAppliedSearch(search);
    if (selectedBatch) {
      fetchEntries(selectedBatch.id, search, 0, entriesPageSize);
    }
  };

  // Add
  const handleAddOk = async () => {
    try {
      const values = await addForm.validateFields();
      setAddLoading(true);
      await addOwnershipEntry(values);
      message.success(t('phoneOwnership.addSuccess'));
      setAddModal(false);
      addForm.resetFields();
      fetchBatches();
      fetchMonths();
    } catch (err) {
      if (err instanceof Error) {
        message.error(t('phoneOwnership.addFailed', { error: err.message }));
      }
    } finally {
      setAddLoading(false);
    }
  };

  // Edit
  const handleEdit = (record: OwnershipEntry) => {
    editForm.setFieldsValue({
      phone_number: record.phone_number,
      l1_branch: record.l1_branch || '',
      l2_branch: record.l2_branch || '',
      status: record.status ?? 0,
    });
    setEditModal({ open: true, entry: record });
  };

  const handleEditOk = async () => {
    if (!editModal.entry) return;
    try {
      const values = await editForm.validateFields();
      setEditLoading(true);
      await updateOwnershipEntry(editModal.entry.id, {
        l1_branch: values.l1_branch,
        l2_branch: values.l2_branch,
        status: values.status,
      });
      message.success(t('phoneOwnership.editSuccess'));
      setEditModal({ open: false, entry: null });
      if (selectedBatch) fetchEntries(selectedBatch.id, appliedSearch, entriesPage, entriesPageSize);
    } catch (err) {
      if (err instanceof Error) {
        message.error(t('phoneOwnership.editFailed', { error: err.message }));
      }
    } finally {
      setEditLoading(false);
    }
  };

  const handleExport = () => {
    exportBranchOwnership();
    message.info(t('phoneOwnership.exportStarted'));
  };

  // ==================== Batch list columns ====================

  const batchColumns = [
    {
      title: t('phoneOwnership.monthCol'), dataIndex: 'billing_month', key: 'billing_month', width: 140,
      render: (month: string) => (
        <span style={{ fontWeight: selectedBatch?.billing_month === month ? 600 : 400 }}>
          {month || t('phoneOwnership.monthNotSet')}
        </span>
      ),
    },
    {
      title: t('phoneOwnership.recordCountCol'), dataIndex: 'total_count', key: 'total_count', width: 120,
      render: (v: number) => (
        <span style={{ fontWeight: 500 }}>
          {v != null ? v.toLocaleString() : '-'}
          <span style={{ color: COLORS.textMuted, fontSize: 12, marginLeft: 6 }}>
            {t('phoneOwnership.countUnit')}
          </span>
        </span>
      ),
    },
    {
      title: t('phoneOwnership.batchNoCol'), dataIndex: 'batch_no', key: 'batch_no', width: 200,
      render: (v: string) => <span style={{ color: COLORS.textMuted, fontSize: 12 }}>{v}</span>,
    },
    {
      title: t('phoneOwnership.importTimeCol'), dataIndex: 'created_at', key: 'created_at', width: 130,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
    },
    {
      title: t('common.delete'), key: 'delete', width: 70,
      render: (_: unknown, record: OwnershipBatch) => isAdmin ? (
        <Popconfirm
          title={t('phoneOwnership.deleteConfirm')}
          onConfirm={(e) => { e?.stopPropagation(); handleDelete(record.id); }}
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
    { title: t('phoneOwnership.phoneCol2'), dataIndex: 'phone_number', key: 'phone_number', width: 140, fixed: 'left' as const },
    { title: t('phoneOwnership.l1BranchCol'), dataIndex: 'l1_branch', key: 'l1_branch', width: 160,
      render: (v: string) => v || '-' },
    { title: t('phoneOwnership.l2BranchCol'), dataIndex: 'l2_branch', key: 'l2_branch', width: 160,
      render: (v: string) => v || '-' },
    { title: t('phoneOwnership.statusCol'), dataIndex: 'status', key: 'status', width: 100,
      render: (v: number) => v === 1
        ? <Tag color={COLORS.danger}>{t('phoneOwnership.statusDisconnected')}</Tag>
        : <Tag color={COLORS.confirmed}>{t('phoneOwnership.statusActive')}</Tag> },
    { title: t('phoneOwnership.updatedAtCol'), dataIndex: 'updated_at', key: 'updated_at', width: 160,
      render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-' },
    ...(isAdmin ? [{
      title: t('common.edit'), key: 'edit', width: 70,
      render: (_: unknown, record: OwnershipEntry) => (
        <Button size="small" type="text" icon={<EditOutlined />}
          onClick={(e) => { e.stopPropagation(); handleEdit(record); }} />
      ),
    }] : []),
  ];

  // ==================== Render ====================

  return (
    <div>
      {/* Top toolbar: month filter + import/add/export buttons */}
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Select
            style={{ width: 150 }}
            placeholder={t('phoneOwnership.filterByMonth')}
            allowClear
            value={selectedMonth}
            onChange={(v) => setSelectedMonth(v)}
            options={availableMonths.map(m => ({ label: m, value: m }))}
          />
        </Col>
        <Col flex="auto" />
        <Col>
          <Space>
            {isAdmin && (
              <Button icon={<PlusOutlined />} onClick={() => setAddModal(true)}>
                {t('phoneOwnership.addLabel')}
              </Button>
            )}
            <Dropdown menu={{ items: [
              { key: 'import', icon: <UploadOutlined />, label: t('phoneOwnership.importShortLabel'), disabled: uploading },
              { key: 'template', icon: <DownloadOutlined />, label: t('phoneOwnership.downloadTemplate') },
            ], onClick: ({ key }) => {
              if (key === 'import') document.getElementById('ownership-upload-input')?.click();
              if (key === 'template') downloadOwnershipTemplate();
            } }}>
              <Button icon={<UploadOutlined />} loading={uploading && !importPolling}>
                {t('phoneOwnership.importShortLabel')}
              </Button>
            </Dropdown>
            <input type="file" accept=".xlsx,.xls" id="ownership-upload-input" style={{ display: 'none' }}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleFileSelected(f); e.target.value = ''; } }} />
            {importPolling && importProgress && (
              <Progress
                percent={importPercent}
                size="small"
                style={{ width: 200, display: 'inline-block', verticalAlign: 'middle' }}
                format={() => importProgress.message || `${importProgress.processed}/${importProgress.total}`}
              />
            )}
            <Button icon={<ExportOutlined />} onClick={handleExport}>
              {t('phoneOwnership.exportLabel')}
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
          loading={loading}
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
            ? t('phoneOwnership.monthResults', { month: selectedBatch.billing_month })
            : t('phoneOwnership.batchResults', { batch: selectedBatch.batch_no })}
          extra={
            <Space>
              <Input
                prefix={<SearchOutlined />}
                placeholder={t('phoneOwnership.searchPlaceholder')}
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
              {appliedSearch && (
                <span style={{ color: COLORS.textMuted, fontSize: 12 }}>
                  {t('phoneOwnership.searchResult', { count: filteredCount })}
                </span>
              )}
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
              current: entriesPage + 1,
              pageSize: entriesPageSize,
              total: filteredCount,
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100'],
              showTotal: (total) => t('common.paginationTotal', { total }),
              onChange: (p, s) => {
                if (selectedBatch) fetchEntries(selectedBatch.id, appliedSearch, p - 1, s);
              },
            }}
          />
        </Card>
      )}

      {/* Import month picker modal */}
      <Modal
        title={t('phoneOwnership.selectImportMonth')}
        open={importMonthModal.open}
        onOk={handleConfirmImport}
        onCancel={() => setImportMonthModal({ open: false, file: null })}
        okText={t('phoneOwnership.confirmImport')}
        okButtonProps={{ disabled: !importBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('phoneOwnership.selectMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          value={importBillingMonth ? dayjs(importBillingMonth, 'YYYY-MM') : null}
          onChange={(_, dateString) => setImportBillingMonth(dateString as string)}
          allowClear={false}
        />
        {importMonthModal.file && (
          <p style={{ marginTop: 8, color: COLORS.textMuted, fontSize: 12 }}>
            {t('phoneOwnership.importFile')}：{importMonthModal.file.name}
          </p>
        )}
      </Modal>

      {/* Add modal */}
      <Modal
        title={t('phoneOwnership.addTitle')}
        open={addModal}
        onOk={handleAddOk}
        onCancel={() => { setAddModal(false); addForm.resetFields(); }}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={addLoading}
        destroyOnClose
      >
        <Form form={addForm} layout="vertical" preserve={false}>
          <Form.Item name="phone_number" label={t('phoneOwnership.phoneCol2')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="l1_branch" label={t('phoneOwnership.l1BranchCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="l2_branch" label={t('phoneOwnership.l2BranchCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="full_path" label={t('phoneOwnership.fullPathCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="status" label={t('phoneOwnership.statusCol')} initialValue={0}>
            <Select options={[
              { label: t('phoneOwnership.statusActive'), value: 0 },
              { label: t('phoneOwnership.statusDisconnected'), value: 1 },
            ]} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit modal */}
      <Modal
        title={t('phoneOwnership.editTitle')}
        open={editModal.open}
        onOk={handleEditOk}
        onCancel={() => setEditModal({ open: false, entry: null })}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={editLoading}
        destroyOnClose
      >
        <Form form={editForm} layout="vertical" preserve={false}>
          <Form.Item name="phone_number" label={t('phoneOwnership.phoneCol2')}>
            <Input disabled />
          </Form.Item>
          <Form.Item name="l1_branch" label={t('phoneOwnership.l1BranchCol')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="l2_branch" label={t('phoneOwnership.l2BranchCol')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="status" label={t('phoneOwnership.statusCol')} rules={[{ required: true }]}>
            <Select options={[
              { label: t('phoneOwnership.statusActive'), value: 0 },
              { label: t('phoneOwnership.statusDisconnected'), value: 1 },
            ]} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default BranchOwnershipPage;
