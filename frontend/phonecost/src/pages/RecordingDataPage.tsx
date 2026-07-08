import { useState, useEffect, useCallback } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Select, Tag, Row, Col, message, Input, Statistic, Button, Dropdown, Progress, Popconfirm } from 'antd';
import { SearchOutlined, CameraOutlined, UploadOutlined, DownloadOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { RecordingDataBatch, RecordingDataEntry, ImportProgress } from '../types/import';
import { IMPORT_STATUS_MAP } from '../types/import';
import {
  getRecordingDataBatches,
  importRecordingData,
  downloadRecordingDataTemplate,
  getRecordingDataProgress,
  getRecordingDataEntries,
  deleteRecordingDataBatch,
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';

export default function RecordingDataPage() {
  const { t } = useTranslation();
  const isAdmin = useAuthStore((s) => s.role === 1);

  const [batches, setBatches] = useState<RecordingDataBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [entries, setEntries] = useState<RecordingDataEntry[]>([]);
  const [entriesTotal, setEntriesTotal] = useState(0);
  const [entriesPage, setEntriesPage] = useState(0);
  const [entriesPageSize, setEntriesPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [uploading, setUploading] = useState(false);

  const { progress: importProgress, polling: importPolling, startPolling, percent: importPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(t('recordingData.importSuccess', { count: p.total }));
      fetchBatches();
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(t('recordingData.importFailed', { error: p.message || t('common.unknown') }));
      setUploading(false);
    },
  });

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getRecordingDataBatches();
      setBatches(data);
      if (!selectedBatchId && data.length > 0) {
        const sorted = [...data].sort((a, b) => b.id - a.id);
        setSelectedBatchId(sorted[0].id);
      }
    } catch {
      message.error(t('recordingData.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t, selectedBatchId]);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);

  useEffect(() => {
    if (selectedBatchId) {
      fetchEntries(selectedBatchId, entriesPage, entriesPageSize);
    }
  }, [selectedBatchId]);

  const fetchEntries = async (batchId: number, page = 0, size = 50) => {
    setEntriesLoading(true);
    try {
      const data = await getRecordingDataEntries(batchId, page, size);
      setEntries(data.entries);
      setEntriesTotal(data.total);
      setEntriesPage(data.page);
      setEntriesPageSize(data.size);
    } catch {
      message.error(t('recordingData.fetchFailed'));
    } finally {
      setEntriesLoading(false);
    }
  };

  const handleUpload = async (file: File) => {
    setUploading(true);
    try {
      const result = await importRecordingData(file);
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
      if (selectedBatchId === batchId) {
        setSelectedBatchId(null);
        setEntries([]);
      }
      fetchBatches();
    } catch (err) {
      message.error(t('recordingData.deleteFailed'));
    }
  };

  // Filter entries by search
  const filteredEntries = search.trim()
    ? entries.filter(e =>
        (e.extension || '').includes(search.trim()) ||
        (e.phone_number || '').includes(search.trim()) ||
        (e.dept_name || '').includes(search.trim()) ||
        (e.remark || '').includes(search.trim()))
    : entries;

  // Table columns
  const columns = [
    {
      title: t('recordingData.extension'), dataIndex: 'extension', key: 'extension', width: 120,
    },
    {
      title: t('recordingData.phoneNumber'), dataIndex: 'phone_number', key: 'phone_number', width: 150,
    },
    {
      title: t('recordingData.deptName'), dataIndex: 'dept_name', key: 'dept_name', width: 200,
    },
    {
      title: t('recordingData.remark'), dataIndex: 'remark', key: 'remark', width: 200,
      render: (v: string) => v || '-',
    },
  ];

  // Batch select
  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  return (
    <div>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col>
          <Select
            style={{ width: 300 }}
            placeholder={t('recordingData.selectBatch')}
            value={selectedBatchId}
            onChange={(v) => { setSelectedBatchId(v); setEntriesPage(0); }}
            loading={loading}
            options={batches.sort((a, b) => b.id - a.id).map(b => ({
              label: `${b.batch_no} (${b.total_count}${t('recordingData.countUnit')})`,
              value: b.id,
            }))}
          />
        </Col>
        <Col>
          {isAdmin && selectedBatchId && (
            <Popconfirm
              title={t('recordingData.deleteConfirm')}
              onConfirm={() => handleDelete(selectedBatchId)}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
              okButtonProps={{ danger: true }}
            >
              <Button size="small" danger icon={<DeleteOutlined />}>{t('common.delete')}</Button>
            </Popconfirm>
          )}
        </Col>
        <Col flex="auto" />
        <Col>
          <Dropdown menu={{ items: [
            { key: 'import', icon: <UploadOutlined />, label: t('recordingData.importMenu'), disabled: uploading },
            { key: 'template', icon: <DownloadOutlined />, label: t('import.downloadTemplate') },
          ], onClick: ({ key }) => {
            if (key === 'import') document.getElementById('recording-upload-input')?.click();
            if (key === 'template') downloadRecordingDataTemplate();
          } }}>
            <Button type="primary" icon={<UploadOutlined />} loading={uploading && !importPolling}>
              {t('recordingData.importMenu')}
            </Button>
          </Dropdown>
          <input type="file" accept=".xlsx,.xls" id="recording-upload-input" style={{ display: 'none' }}
            onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleUpload(f); e.target.value = ''; } }} />
          {importPolling && importProgress && (
            <Progress
              percent={importPercent}
              size="small"
              style={{ width: 200, marginLeft: 12, display: 'inline-block', verticalAlign: 'middle' }}
              format={() => `${importProgress.processed}/${importProgress.total}`}
            />
          )}
        </Col>
      </Row>

      {/* Stats */}
      {selectedBatch && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col><Statistic title={t('recordingData.totalCount')} value={selectedBatch.total_count} /></Col>
          <Col>
            <Statistic
              title={t('recordingData.importStatus')}
              value={IMPORT_STATUS_MAP[selectedBatch.import_status]?.label || t('common.unknown')}
              valueStyle={{ color: IMPORT_STATUS_MAP[selectedBatch.import_status]?.color }}
            />
          </Col>
        </Row>
      )}

      {/* Search */}
      <Input
        prefix={<SearchOutlined />}
        placeholder={t('recordingData.searchPlaceholder')}
        allowClear
        size="small"
        style={{ width: 240, marginBottom: 12 }}
        value={search}
        onChange={(e) => setSearch(e.target.value)}
      />

      {/* Entry table */}
      <Table
        columns={columns}
        dataSource={filteredEntries}
        rowKey="id"
        size="small"
        loading={entriesLoading}
        pagination={{
          current: entriesPage + 1,
          pageSize: entriesPageSize,
          total: entriesTotal,
          showSizeChanger: true,
          pageSizeOptions: ['20', '50', '100'],
          showTotal: (total) => t('common.paginationTotal', { total }),
          onChange: (p, s) => {
            if (selectedBatchId) fetchEntries(selectedBatchId, p - 1, s);
          },
        }}
      />
    </div>
  );
}
