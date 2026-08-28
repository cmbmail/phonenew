import { useState, useEffect, useCallback } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Tag, Row, Col, message, Input, Button, Dropdown, Popconfirm, DatePicker, Space, Modal, Select, Progress } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, DeleteOutlined, PlusOutlined, ExportOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AllocDeptBatch, AllocDeptEntry, ImportProgress } from '../types/import';
import {
  getAllocDeptBatches,
  getAllocDeptMonths,
  importAllocDept,
  downloadAllocDeptTemplate,
  getAllocDeptProgress,
  getAllocDeptEntriesByMonth,
  deleteAllocDeptBatch,
  addAllocDeptEntry,
  exportAllocDept,
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';
import dayjs from 'dayjs';

const AllocationDeptPage: React.FC = () => {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);
  const isAdmin = useAuthStore((s) => s.role === 1);

  // ==================== Batch list state ====================
  const [batches, setBatches] = useState<AllocDeptBatch[]>([]);
  const [batchLoading, setBatchLoading] = useState(false);
  const [selectedBatch, setSelectedBatch] = useState<AllocDeptBatch | null>(null);
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
      message.success(t('allocationDept.importSuccess', { count: p.total }));
      fetchBatches();
      fetchMonths();
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(`${t('allocationDept.importFailed')}：${p.message || t('common.unknown')}`);
      setUploading(false);
    },
  });

  // Add entry modal
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [addLoading, setAddLoading] = useState(false);

  // ==================== Entry detail state ====================
  const [entries, setEntries] = useState<AllocDeptEntry[]>([]);
  const [_entriesTotal, setEntriesTotal] = useState(0);
  const [entriesPage, setEntriesPage] = useState(0);
  const [entriesPageSize, setEntriesPageSize] = useState(50);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [filteredCount, setFilteredCount] = useState(0);

  // ==================== Data fetching ====================

  const fetchMonths = useCallback(async () => {
    try {
      const data = await getAllocDeptMonths();
      setAvailableMonths(data);
    } catch { /* silent */ }
  }, []);

  const fetchBatches = useCallback(async () => {
    setBatchLoading(true);
    try {
      const data = await getAllocDeptBatches(selectedMonth);
      setBatches(data);
      if (!selectedBatch && data.length > 0) {
        setSelectedBatch(data[data.length - 1]);
      }
    } catch {
      message.error(t('allocationDept.fetchBatchesFailed'));
    } finally {
      setBatchLoading(false);
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
      const data = await getAllocDeptEntriesByMonth(billingMonth, keyword || undefined, page, size);
      setEntries(data.entries);
      setEntriesTotal(data.total);
      setFilteredCount(data.filtered ?? data.total);
      setEntriesPage(data.page);
      setEntriesPageSize(data.size);
    } catch {
      message.error(t('allocationDept.fetchEntriesFailed'));
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
      message.warning(t('allocationDept.selectMonthFirst'));
      return;
    }
    const file = importMonthModal.file;
    const month = importBillingMonth;
    setImportMonthModal({ open: false, file: null });
    setUploading(true);
    try {
      const result = await importAllocDept(file, month);
      startPolling(result.batch_id, getAllocDeptProgress);
    } catch (err) {
      message.error(`${t('allocationDept.importFailed')}：${err instanceof Error ? err.message : t('common.unknown')}`);
      setUploading(false);
    }
  };

  const handleDeleteBatch = async (batchId: number) => {
    try {
      await deleteAllocDeptBatch(batchId);
      message.success(t('allocationDept.deleteSuccess'));
      if (selectedBatch?.id === batchId) {
        setSelectedBatch(null);
        setEntries([]);
      }
      fetchBatches();
      fetchMonths();
    } catch (_err) {
      message.error(t('allocationDept.deleteFailed'));
    }
  };

  const handleSearch = () => {
    setAppliedSearch(search);
    if (selectedBatch) {
      fetchEntries(selectedBatch.id, search, 0, entriesPageSize);
    }
  };

  // Add entry
  const handleAddOk = async () => {
    try {
      setAddLoading(true);
      await addAllocDeptEntry({
        billing_month: importBillingMonth,
        phone_number: '',
        branch: '',
        dept_name: '',
        full_path: '',
        org_code: '',
        cost_center: '',
      });
      message.success(t('allocationDept.addSuccess'));
      setAddModalOpen(false);
      fetchBatches();
      fetchMonths();
    } catch (err) {
      message.error(`${t('allocationDept.addFailed')}：${err instanceof Error ? err.message : ''}`);
    } finally {
      setAddLoading(false);
    }
  };

  // Export
  const handleExport = () => {
    if (selectedBatch?.billing_month) {
      exportAllocDept(selectedBatch.billing_month);
    } else {
      message.warning(t('allocationDept.selectMonthForExport'));
    }
  };

  // ==================== Batch list columns ====================

  const batchColumns = [
    {
      title: t('allocationDept.month'), dataIndex: 'billing_month', key: 'billing_month', width: 140,
      render: (month: string) => (
        <span style={{ fontWeight: selectedBatch?.billing_month === month ? 600 : 400 }}>
          {month || t('allocationDept.monthNotSet')}
        </span>
      ),
    },
    {
      title: t('allocationDept.recordCount'), dataIndex: 'total_count', key: 'total_count', width: 120,
      render: (v: number) => (
        <span style={{ fontWeight: 500 }}>
          {v != null ? v.toLocaleString() : '-'}
          <span style={{ color: COLORS.textMuted, fontSize: 12, marginLeft: 6 }}>
            {t('allocationDept.recordCount')}
          </span>
        </span>
      ),
    },
    {
      title: t('allocationDept.batchNo'), dataIndex: 'batch_no', key: 'batch_no', width: 200,
      render: (v: string) => <span style={{ color: COLORS.textMuted, fontSize: 12 }}>{v}</span>,
    },
    {
      title: t('allocationDept.importTime'), dataIndex: 'created_at', key: 'created_at', width: 130,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
    },
    {
      title: t('common.delete'), key: 'delete', width: 70,
      render: (_: unknown, record: AllocDeptBatch) => isAdmin ? (
        <Popconfirm
          title={t('allocationDept.deleteBatchConfirm')}
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
    { title: t('allocationDept.fullPath'), dataIndex: 'full_path', key: 'full_path', width: 260, ellipsis: true,
      render: (v: string) => v || '-' },
    { title: t('allocationDept.branch'), dataIndex: 'branch', key: 'branch', width: 140,
      render: (v: string) => v || '-' },
    { title: t('allocationDept.deptName'), dataIndex: 'dept_name', key: 'dept_name', width: 160,
      render: (v: string) => v || '-' },
    { title: t('allocationDept.orgCode'), dataIndex: 'org_code', key: 'org_code', width: 120,
      render: (v: string) => v || '-' },
    { title: t('allocationDept.costCenter'), dataIndex: 'cost_center', key: 'cost_center', width: 120,
      render: (v: string) => v || '-' },
    { title: t('allocationDept.exceptionCol'), dataIndex: 'is_exception', key: 'is_exception', width: 80,
      render: (v: number) => v === 1
        ? <Tag color={COLORS.danger}>{t('allocationDept.exceptionYes')}</Tag>
        : '-' },
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
                {t('allocationDept.add')}
              </Button>
            )}
            <Dropdown menu={{ items: [
              { key: 'import', icon: <UploadOutlined />, label: t('allocationDept.importLabel'), disabled: uploading },
              { key: 'template', icon: <DownloadOutlined />, label: t('allocationDept.downloadTemplate') },
            ], onClick: ({ key }) => {
              if (key === 'import') document.getElementById('alloc-dept-upload-input')?.click();
              if (key === 'template') downloadAllocDeptTemplate();
            } }}>
              <Button type="primary" icon={<UploadOutlined />} loading={uploading && !importPolling}>
                {t('allocationDept.importBtn')}
              </Button>
            </Dropdown>
            <input type="file" accept=".xlsx,.xls" id="alloc-dept-upload-input" style={{ display: 'none' }}
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
              {t('allocationDept.export')}
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
            ? t('allocationDept.monthResults', { month: selectedBatch.billing_month })
            : t('allocationDept.batchResults', { batch: selectedBatch.batch_no })}
          extra={
            <Space>
              <Input
                prefix={<SearchOutlined />}
                placeholder={t('allocationDept.searchPlaceholder')}
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
                  {t('allocationDept.searchResult', { count: filteredCount })}
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
            scroll={{ x: 1100 }}
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

      {!selectedBatch && batches.length === 0 && (
        <Card style={{ marginTop: 16 }}>
          <div style={{ textAlign: 'center', padding: 24, color: COLORS.textMuted }}>
            {t('allocationDept.noBatchHint')}
          </div>
        </Card>
      )}

      {/* Add entry modal */}
      <Modal
        title={t('allocationDept.addTitle')}
        open={addModalOpen}
        onOk={handleAddOk}
        onCancel={() => setAddModalOpen(false)}
        okText={t('allocationDept.addConfirm')}
        confirmLoading={addLoading}
        width={560}
        destroyOnClose
      >
        <p style={{ marginBottom: 12, color: COLORS.textMuted }}>
          {t('allocationDept.importMonthHint')}
        </p>
      </Modal>

      {/* Import month picker modal */}
      <Modal
        title={t('allocationDept.importMonthTitle')}
        open={importMonthModal.open}
        onOk={handleConfirmImport}
        onCancel={() => setImportMonthModal({ open: false, file: null })}
        okText={t('allocationDept.importConfirm')}
        okButtonProps={{ disabled: !importBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('allocationDept.importMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          value={importBillingMonth ? dayjs(importBillingMonth, 'YYYY-MM') : null}
          onChange={(_, dateString) => setImportBillingMonth(dateString as string)}
          allowClear={false}
        />
        {importMonthModal.file && (
          <p style={{ marginTop: 8, color: COLORS.textMuted, fontSize: 12 }}>
            {t('allocationDept.importFile')}{importMonthModal.file.name}
          </p>
        )}
      </Modal>
    </div>
  );
};

export default AllocationDeptPage;
