import { useState } from 'react';
import {
  Card,
  Tabs,
  Upload,
  Button,
  Table,
  Tag,
  Space,
  message,
  Select,
  Alert,
  Typography,
} from 'antd';
import {
  UploadOutlined,
  PhoneOutlined,
  ContactsOutlined,
  FileTextOutlined,
  LinkOutlined,
} from '@ant-design/icons';
import type { UploadFile } from 'antd';
import {
  importOwnership,
  importDirectory,
  importBill,
  getOwnershipBatches,
  getDirectoryBatches,
  getBillBatches,
  matchOwnership,
  getActiveImportTemplate,
} from '../api/import';
import type {
  OwnershipBatch,
  DirectoryBatch,
} from '../types/import';
import type { BillBatch } from '../types/bill';
import { IMPORT_STATUS_MAP } from '../types/import';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';

const { Text } = Typography;

export default function DataImport() {
  const [activeTab, setActiveTab] = useState('ownership');
  const { t } = useTranslation();

  // ==================== Ownership Tab ====================
  const [ownershipFileList, setOwnershipFileList] = useState<UploadFile[]>([]);
  const [ownershipUploading, setOwnershipUploading] = useState(false);
  const [ownershipBatches, setOwnershipBatches] = useState<OwnershipBatch[]>([]);
  const [ownershipLoading, setOwnershipLoading] = useState(false);

  const handleOwnershipUpload = async () => {
    if (ownershipFileList.length === 0) {
      message.warning(t('import.selectFileFirst'));
      return;
    }
    setOwnershipUploading(true);
    try {
      const file = ownershipFileList[0].originFileObj!;
      const result = await importOwnership(file);
      message.success(t('import.ownershipImportSuccess', { total: result.total_count, exceptions: result.exception_count ?? 0 }));
      setOwnershipFileList([]);
      fetchOwnershipBatches();
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('import.importFailed'));
    } finally {
      setOwnershipUploading(false);
    }
  };

  const fetchOwnershipBatches = async () => {
    setOwnershipLoading(true);
    try {
      const data = await getOwnershipBatches();
      setOwnershipBatches(data);
    } catch {
      message.error(t('import.fetchFailed'));
    } finally {
      setOwnershipLoading(false);
    }
  };

  // ==================== Directory Tab ====================
  const [directoryFileList, setDirectoryFileList] = useState<UploadFile[]>([]);
  const [directoryUploading, setDirectoryUploading] = useState(false);
  const [directoryBatches, setDirectoryBatches] = useState<DirectoryBatch[]>([]);
  const [directoryLoading, setDirectoryLoading] = useState(false);

  const handleDirectoryUpload = async () => {
    if (directoryFileList.length === 0) {
      message.warning(t('import.selectFileFirst'));
      return;
    }
    setDirectoryUploading(true);
    try {
      const file = directoryFileList[0].originFileObj!;
      const result = await importDirectory(file);
      message.success(t('import.directoryImportSuccess', { total: result.total_count, seconded: result.seconded_count ?? 0 }));
      setDirectoryFileList([]);
      fetchDirectoryBatches();
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('import.importFailed'));
    } finally {
      setDirectoryUploading(false);
    }
  };

  const fetchDirectoryBatches = async () => {
    setDirectoryLoading(true);
    try {
      const data = await getDirectoryBatches();
      setDirectoryBatches(data);
    } catch {
      message.error(t('import.fetchFailed'));
    } finally {
      setDirectoryLoading(false);
    }
  };

  // ==================== Bill Tab ====================
  const [billFileList, setBillFileList] = useState<UploadFile[]>([]);
  const [billUploading, setBillUploading] = useState(false);
  const [billBatches, setBillBatches] = useState<BillBatch[]>([]);
  const [billLoading, setBillLoading] = useState(false);
  const [activeTemplate, setActiveTemplate] = useState<{ id: number; name: string; operator: string } | null>(null);

  // Match state
  const [matchBillBatchId, setMatchBillBatchId] = useState<number | null>(null);
  const [matchOwnershipBatchId, setMatchOwnershipBatchId] = useState<number | null>(null);
  const [matchDirectoryBatchId, setMatchDirectoryBatchId] = useState<number | null>(null);
  const [matching, setMatching] = useState(false);

  const handleBillUpload = async () => {
    if (billFileList.length === 0) {
      message.warning(t('import.selectFileFirst'));
      return;
    }
    setBillUploading(true);
    try {
      const file = billFileList[0].originFileObj!;
      const result = await importBill(file);
      message.success(t('import.billImportSuccess', { count: result.total_count, amount: (result.total_amount ?? 0).toFixed(2), month: result.billing_month }));
      setBillFileList([]);
      fetchBillBatches();
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('import.importFailed'));
    } finally {
      setBillUploading(false);
    }
  };

  const fetchBillBatches = async () => {
    setBillLoading(true);
    try {
      const data = await getBillBatches();
      setBillBatches(data);
      try { setActiveTemplate(await getActiveImportTemplate()); } catch { /* ignore */ }
    } catch {
      message.error(t('import.fetchFailed'));
    } finally {
      setBillLoading(false);
    }
  };

  const handleMatch = async () => {
    if (!matchBillBatchId) {
      message.warning(t('import.selectBillBatch'));
      return;
    }
    setMatching(true);
    try {
      const result = await matchOwnership({
        bill_batch_id: matchBillBatchId,
        ownership_batch_id: matchOwnershipBatchId ?? undefined,
        directory_batch_id: matchDirectoryBatchId ?? undefined,
      });
      message.success(t('import.matchSuccess', { count: result.matched_count }));
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('import.matchFailed'));
    } finally {
      setMatching(false);
    }
  };

  // ==================== Common render helpers ====================
  const renderImportStatus = (status: number) => {
    const info = IMPORT_STATUS_MAP[status] || { label: t('common.unknown'), color: 'default' };
    return <Tag color={info.color}>{info.label}</Tag>;
  };

  const ownershipColumns = [
    { title: t('import.batchNo'), dataIndex: 'batch_no', key: 'batch_no' },
    { title: t('import.fileName'), dataIndex: 'file_name', key: 'file_name' },
    { title: t('import.totalCount'), dataIndex: 'total_count', key: 'total_count' },
    { title: t('import.exceptionCount'), dataIndex: 'exception_count', key: 'exception_count' },
    { title: t('import.status'), dataIndex: 'import_status', key: 'import_status', render: renderImportStatus },
    {
      title: t('import.importTime'), dataIndex: 'created_at', key: 'created_at',
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
  ];

  const directoryColumns = [
    { title: t('import.batchNo'), dataIndex: 'batch_no', key: 'batch_no' },
    { title: t('import.fileName'), dataIndex: 'file_name', key: 'file_name' },
    { title: t('import.totalCount'), dataIndex: 'total_count', key: 'total_count' },
    { title: t('import.secondedCount'), dataIndex: 'seconded_count', key: 'seconded_count' },
    { title: t('import.status'), dataIndex: 'import_status', key: 'import_status', render: renderImportStatus },
    {
      title: t('import.importTime'), dataIndex: 'created_at', key: 'created_at',
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
  ];

  const billColumns = [
    { title: t('import.batchNo'), dataIndex: 'batch_no', key: 'batch_no' },
    { title: t('import.month'), dataIndex: 'billing_month', key: 'billing_month' },
    { title: t('import.fileName'), dataIndex: 'file_name', key: 'file_name' },
    { title: t('import.count'), dataIndex: 'total_count', key: 'total_count' },
    {
      title: t('import.totalAmountCol'), dataIndex: 'total_amount', key: 'total_amount',
      render: (v: number) => v != null ? `¥${v.toFixed(2)}` : '-',
    },
    { title: t('import.status'), dataIndex: 'import_status', key: 'import_status', render: renderImportStatus },
    {
      title: t('import.importTime'), dataIndex: 'created_at', key: 'created_at',
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
  ];

  // ==================== Upload area component ====================
  const renderUploadArea = (
    fileList: UploadFile[],
    setFileList: (f: UploadFile[]) => void,
    uploading: boolean,
    onUpload: () => void,
    accept: string,
  ) => (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Upload
        accept={accept}
        maxCount={1}
        fileList={fileList}
        beforeUpload={() => false}
        onChange={({ fileList: fl }) => setFileList(fl)}
      >
        <Button icon={<UploadOutlined />}>{t('import.selectFile')}</Button>
      </Upload>
      <Button
        type="primary"
        onClick={onUpload}
        loading={uploading}
        disabled={fileList.length === 0}
      >
        {t('import.startImport')}
      </Button>
    </Space>
  );

  return (
    <div>
      <Card>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'ownership',
              label: (
                <span>
                  <PhoneOutlined /> {t('import.ownershipTab')}
                </span>
              ),
              children: (
                <div>
                  {renderUploadArea(
                    ownershipFileList,
                    setOwnershipFileList,
                    ownershipUploading,
                    handleOwnershipUpload,
                    '.xlsx,.xls',
                  )}
                  <div style={{ marginTop: 24 }}>
                    <Button
                      size="small"
                      onClick={fetchOwnershipBatches}
                      loading={ownershipLoading}
                      style={{ marginBottom: 8 }}
                    >
                      {t('import.refreshBatches')}
                    </Button>
                    <Table
                      columns={ownershipColumns}
                      dataSource={ownershipBatches}
                      rowKey="id"
                      size="small"
                      loading={ownershipLoading}
                      pagination={{ pageSize: 10 }}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: 'directory',
              label: (
                <span>
                  <ContactsOutlined /> {t('import.directoryTab')}
                </span>
              ),
              children: (
                <div>
                  {renderUploadArea(
                    directoryFileList,
                    setDirectoryFileList,
                    directoryUploading,
                    handleDirectoryUpload,
                    '.xlsx,.xls',
                  )}
                  <div style={{ marginTop: 24 }}>
                    <Button
                      size="small"
                      onClick={fetchDirectoryBatches}
                      loading={directoryLoading}
                      style={{ marginBottom: 8 }}
                    >
                      {t('import.refreshBatches')}
                    </Button>
                    <Table
                      columns={directoryColumns}
                      dataSource={directoryBatches}
                      rowKey="id"
                      size="small"
                      loading={directoryLoading}
                      pagination={{ pageSize: 10 }}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: 'bill',
              label: (
                <span>
                  <FileTextOutlined /> {t('import.billTab')}
                </span>
              ),
              children: (
                <div>
                  {renderUploadArea(
                    billFileList,
                    setBillFileList,
                    billUploading,
                    handleBillUpload,
                    '.xlsx,.xls',
                  )}
                  {activeTemplate && (
                    <Alert
                      message={t('import.activeTemplate', { name: activeTemplate.name, operator: activeTemplate.operator })}
                      type="success"
                      showIcon
                      style={{ marginBottom: 16 }}
                    />
                  )}
                  <div style={{ marginTop: 24 }}>
                    <Button
                      size="small"
                      onClick={fetchBillBatches}
                      loading={billLoading}
                      style={{ marginBottom: 8 }}
                    >
                      {t('import.refreshBatches')}
                    </Button>
                    <Table
                      columns={billColumns}
                      dataSource={billBatches}
                      rowKey="id"
                      size="small"
                      loading={billLoading}
                      pagination={{ pageSize: 10 }}
                    />
                  </div>
                  {/* Match section */}
                  <Card
                    title={
                      <span>
                        <LinkOutlined /> {t('import.matchTitle')}
                      </span>
                    }
                    style={{ marginTop: 24 }}
                    size="small"
                  >
                    <Alert
                      message={t('import.matchPriority')}
                      type="info"
                      showIcon
                      style={{ marginBottom: 16 }}
                    />
                    <Space wrap>
                      <div>
                        <Text type="secondary">{t('import.matchBillBatch')}</Text>
                        <br />
                        <Select
                          style={{ width: 240 }}
                          placeholder={t('import.selectBillBatch')}
                          value={matchBillBatchId}
                          onChange={setMatchBillBatchId}
                          options={billBatches.map((b) => ({
                            value: b.id,
                            label: `${b.batch_no} (${b.billing_month})`,
                          }))}
                        />
                      </div>
                      <div>
                        <Text type="secondary">{t('import.matchOwnershipBatch')}</Text>
                        <br />
                        <Select
                          style={{ width: 240 }}
                          placeholder={t('import.matchOwnershipBatch')}
                          allowClear
                          value={matchOwnershipBatchId}
                          onChange={setMatchOwnershipBatchId}
                          options={ownershipBatches.map((b) => ({
                            value: b.id,
                            label: `${b.batch_no} (${b.total_count}条)`,
                          }))}
                        />
                      </div>
                      <div>
                        <Text type="secondary">{t('import.matchDirectoryBatch')}</Text>
                        <br />
                        <Select
                          style={{ width: 240 }}
                          placeholder={t('import.matchDirectoryBatch')}
                          allowClear
                          value={matchDirectoryBatchId}
                          onChange={setMatchDirectoryBatchId}
                          options={directoryBatches.map((b) => ({
                            value: b.id,
                            label: `${b.batch_no} (${b.total_count}条)`,
                          }))}
                        />
                      </div>
                      <Button
                        type="primary"
                        onClick={handleMatch}
                        loading={matching}
                        disabled={!matchBillBatchId}
                        icon={<LinkOutlined />}
                        style={{ marginTop: 22 }}
                      >
                        {t('import.executeMatch')}
                      </Button>
                    </Space>
                  </Card>
                </div>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
