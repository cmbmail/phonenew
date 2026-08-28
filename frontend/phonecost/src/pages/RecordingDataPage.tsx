import { useState, useEffect, useCallback } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Tag, Row, Col, message, Input, Button, Dropdown, Progress, Popconfirm, DatePicker, Space, Modal, Select, Form } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, DeleteOutlined, PlusOutlined, ExportOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { RecordingDataBatch, RecordingDataEntry, ImportProgress } from '../types/import';

import {
  getRecordingDataBatches,
  getRecordingDataMonths,
  importRecordingData,
  downloadRecordingDataTemplate,
  getRecordingDataProgress,
  getRecordingDataEntriesByMonth,
  deleteRecordingDataBatch,
  addRecordingDataEntry,
  exportRecordingData,
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';
import dayjs from 'dayjs';

export default function RecordingDataPage() {
  const { t } = useTranslation();
  const isAdmin = useAuthStore((s) => s.role === 1);

  // ==================== Batch list state ====================
  const [batches, setBatches] = useState<RecordingDataBatch[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBatch, setSelectedBatch] = useState<RecordingDataBatch | null>(null);
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
      message.success(t('recordingData.importSuccess', { count: p.total }));
      fetchBatches();
      fetchMonths();
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(t('recordingData.importFailed', { error: p.message || t('common.unknown') }));
      setUploading(false);
    },
  });

  // ==================== Entry detail state ====================
  const [entries, setEntries] = useState<RecordingDataEntry[]>([]);
  const [_entriesTotal, setEntriesTotal] = useState(0);
  const [entriesPage, setEntriesPage] = useState(0);
  const [entriesPageSize, setEntriesPageSize] = useState(50);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [filteredCount, setFilteredCount] = useState(0);

  // Add modal
  const [addModal, setAddModal] = useState(false);
  const [addLoading, setAddLoading] = useState(false);
  const [addForm] = Form.useForm();

  // ==================== Data fetching ====================

  const fetchMonths = useCallback(async () => {
    try {
      const data = await getRecordingDataMonths();
      setAvailableMonths(data);
    } catch { /* silent */ }
  }, []);

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getRecordingDataBatches(selectedMonth);
      setBatches(data);
      if (!selectedBatch && data.length > 0) {
        setSelectedBatch(data[data.length - 1]);
      }
    } catch {
      message.error(t('recordingData.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t, selectedMonth]);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);
  useEffect(() => { fetchMonths(); }, [fetchMonths]);

  // Fetch entries for the selected batch
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
      const data = await getRecordingDataEntriesByMonth(billingMonth, keyword || undefined, page, size);
      setEntries(data.entries);
      setEntriesTotal(data.total);
      setFilteredCount(data.filtered ?? data.total);
      setEntriesPage(data.page);
      setEntriesPageSize(data.size);
    } catch {
      message.error(t('recordingData.fetchFailed'));
    } finally {
      setEntriesLoading(false);
    }
  }, [batches]);

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

  const handleImportClick = () => {
    setImportMonthModal(true);
  };

  const handleConfirmMonth = () => {
    if (!importBillingMonth) {
      message.warning(t('recordingData.selectMonthFirst'));
      return;
    }
    setImportMonthModal(false);
    // Trigger file picker after closing modal
    setTimeout(() => {
      document.getElementById('recording-upload-input')?.click();
    }, 100);
  };

  const handleFileSelected = async (file: File) => {
    const month = importBillingMonth;
    setUploading(true);
    try {
      const result = await importRecordingData(file, month);
      startPolling(result.batch_id, getRecordingDataProgress);
    } catch (err) {
      message.error(t('recordingData.importFailed', { error: err instanceof Error ? err.message : t('common.unknown') }));
      setUploading(false);
    }
  };

  const handleDelete = async (batchId: number) => {
    try {
      await deleteRecordingDataBatch(batchId);
      message.success(t('recordingData.deleteSuccess'));
      if (selectedBatch?.id === batchId) {
        setSelectedBatch(null);
        setEntries([]);
      }
      fetchBatches();
      fetchMonths();
    } catch (_err) {
      message.error(t('recordingData.deleteFailed'));
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
      await addRecordingDataEntry(values);
      message.success(t('recordingData.addSuccess'));
      setAddModal(false);
      addForm.resetFields();
      fetchBatches();
      fetchMonths();
    } catch (err) {
      if (err instanceof Error) {
        message.error(t('recordingData.addFailed', { error: err.message }));
      }
    } finally {
      setAddLoading(false);
    }
  };

  const handleExport = () => {
    exportRecordingData();
    message.info(t('recordingData.exportStarted'));
  };

  // ==================== Batch list columns ====================

  const batchColumns = [
    {
      title: t('recordingData.month'), dataIndex: 'billing_month', key: 'billing_month', width: 140,
      render: (month: string) => (
        <span style={{ fontWeight: selectedBatch?.billing_month === month ? 600 : 400 }}>
          {month || t('recordingData.monthNotSet')}
        </span>
      ),
    },
    {
      title: t('recordingData.recordCount'), dataIndex: 'total_count', key: 'total_count', width: 120,
      render: (v: number) => (
        <span style={{ fontWeight: 500 }}>
          {v != null ? v.toLocaleString() : '-'}
          <span style={{ color: COLORS.textMuted, fontSize: 12, marginLeft: 6 }}>
            {t('recordingData.countUnit')}
          </span>
        </span>
      ),
    },
    {
      title: t('recordingData.batchNo'), dataIndex: 'batch_no', key: 'batch_no', width: 200,
      render: (v: string) => <span style={{ color: COLORS.textMuted, fontSize: 12 }}>{v}</span>,
    },
    {
      title: t('recordingData.importTime'), dataIndex: 'created_at', key: 'created_at', width: 130,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
    },
    {
      title: t('common.delete'), key: 'delete', width: 70,
      render: (_: unknown, record: RecordingDataBatch) => isAdmin ? (
        <Popconfirm
          title={t('recordingData.deleteConfirm')}
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
    { title: t('recordingData.extension'), dataIndex: 'extension', key: 'extension', width: 120 },
    { title: t('recordingData.phoneNumber'), dataIndex: 'phone_number', key: 'phone_number', width: 150 },
    { title: t('recordingData.deptName'), dataIndex: 'dept_name', key: 'dept_name', width: 200 },
    {
      title: t('recordingData.statusCol'), dataIndex: 'status', key: 'status', width: 100,
      render: (v: number | null) => v === 1
        ? <Tag color={COLORS.danger}>{t('recordingData.statusClosed')}</Tag>
        : <Tag color={COLORS.confirmed}>{t('recordingData.statusActive')}</Tag>,
    },
    {
      title: t('recordingData.closeTimeCol'), dataIndex: 'close_time', key: 'close_time', width: 160,
      render: (v: string | null) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-',
    },
    {
      title: t('recordingData.remark'), dataIndex: 'remark', key: 'remark', width: 200,
      render: (v: string) => v || '-',
    },
  ];

  // ==================== Render ====================

  return (
    <div>
      {/* Top toolbar: month filter + import/add/export buttons */}
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Select
            style={{ width: 150 }}
            placeholder={t('recordingData.filterByMonth')}
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
                {t('recordingData.addLabel')}
              </Button>
            )}
            <Button icon={<UploadOutlined />} onClick={handleImportClick} loading={uploading && !importPolling} disabled={uploading}>
              {t('recordingData.importShortLabel')}
            </Button>
            <Button icon={<DownloadOutlined />} onClick={() => downloadRecordingDataTemplate()}>
              {t('recordingData.downloadTemplate')}
            </Button>
            <input type="file" accept=".xlsx,.xls" id="recording-upload-input" style={{ display: 'none' }}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleFileSelected(f); e.target.value = ''; } }} />
            {importPolling && importProgress && (
              <Progress
                percent={importPercent}
                size="small"
                style={{ width: 200, display: 'inline-block', verticalAlign: 'middle' }}
                format={() => `${importProgress.processed}/${importProgress.total}`}
              />
            )}
            <Button icon={<ExportOutlined />} onClick={handleExport}>
              {t('recordingData.exportLabel')}
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
            ? t('recordingData.monthResults', { month: selectedBatch.billing_month })
            : t('recordingData.batchResults', { batch: selectedBatch.batch_no })}
          extra={
            <Space>
              <Input
                prefix={<SearchOutlined />}
                placeholder={t('recordingData.searchPlaceholder')}
                allowClear
                size="small"
                style={{ width: 200 }}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onPressEnter={handleSearch}
              />
              <Button size="small" type="primary" onClick={handleSearch} icon={<SearchOutlined />}>
                {t('common.search')}
              </Button>
              {appliedSearch && (
                <span style={{ color: COLORS.textMuted, fontSize: 12 }}>
                  {t('recordingData.searchResult', { count: filteredCount })}
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
        title={t('recordingData.selectImportMonth')}
        open={importMonthModal}
        onOk={handleConfirmMonth}
        onCancel={() => setImportMonthModal(false)}
        okText={t('recordingData.confirmImport')}
        okButtonProps={{ disabled: !importBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('recordingData.selectMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          format="YYYY年MM月"
          value={importBillingMonth ? dayjs(importBillingMonth, 'YYYY-MM') : null}
          onChange={(date) => setImportBillingMonth(date ? date.format('YYYY-MM') : '')}
          allowClear={false}
        />
      </Modal>

      {/* Add modal */}
      <Modal
        title={t('recordingData.addTitle')}
        open={addModal}
        onOk={handleAddOk}
        onCancel={() => { setAddModal(false); addForm.resetFields(); }}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={addLoading}
        destroyOnClose
      >
        <Form form={addForm} layout="vertical" preserve={false}>
          <Form.Item name="billing_month" label={t('recordingData.month')} rules={[{ required: true }]}>
            <DatePicker picker="month" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="extension" label={t('recordingData.extension')}>
            <Input />
          </Form.Item>
          <Form.Item name="phone_number" label={t('recordingData.phoneNumber')}>
            <Input />
          </Form.Item>
          <Form.Item name="dept_name" label={t('recordingData.deptName')}>
            <Input />
          </Form.Item>
          <Form.Item name="remark" label={t('recordingData.remark')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
