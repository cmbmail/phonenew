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
} from '../api/import';
import type {
  OwnershipBatch,
  DirectoryBatch,
} from '../types/import';
import type { BillBatch } from '../types/bill';
import { IMPORT_STATUS_MAP } from '../types/import';
import dayjs from 'dayjs';

const { Text } = Typography;

export default function DataImport() {
  const [activeTab, setActiveTab] = useState('ownership');

  // ==================== Ownership Tab ====================
  const [ownershipFileList, setOwnershipFileList] = useState<UploadFile[]>([]);
  const [ownershipUploading, setOwnershipUploading] = useState(false);
  const [ownershipBatches, setOwnershipBatches] = useState<OwnershipBatch[]>([]);
  const [ownershipLoading, setOwnershipLoading] = useState(false);

  const handleOwnershipUpload = async () => {
    if (ownershipFileList.length === 0) {
      message.warning('请先选择文件');
      return;
    }
    setOwnershipUploading(true);
    try {
      const file = ownershipFileList[0].originFileObj!;
      const result = await importOwnership(file);
      message.success(
        `号码归属导入成功：${result.total_count} 条，例外 ${result.exception_count ?? 0} 条`
      );
      setOwnershipFileList([]);
      fetchOwnershipBatches();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '导入失败');
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
      message.error('获取批次列表失败');
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
      message.warning('请先选择文件');
      return;
    }
    setDirectoryUploading(true);
    try {
      const file = directoryFileList[0].originFileObj!;
      const result = await importDirectory(file);
      message.success(
        `通讯录导入成功：${result.total_count} 条，借调 ${result.seconded_count ?? 0} 条`
      );
      setDirectoryFileList([]);
      fetchDirectoryBatches();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '导入失败');
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
      message.error('获取批次列表失败');
    } finally {
      setDirectoryLoading(false);
    }
  };

  // ==================== Bill Tab ====================
  const [billFileList, setBillFileList] = useState<UploadFile[]>([]);
  const [billUploading, setBillUploading] = useState(false);
  const [billBatches, setBillBatches] = useState<BillBatch[]>([]);
  const [billLoading, setBillLoading] = useState(false);

  // Match state
  const [matchBillBatchId, setMatchBillBatchId] = useState<number | null>(null);
  const [matchOwnershipBatchId, setMatchOwnershipBatchId] = useState<number | null>(null);
  const [matchDirectoryBatchId, setMatchDirectoryBatchId] = useState<number | null>(null);
  const [matching, setMatching] = useState(false);

  const handleBillUpload = async () => {
    if (billFileList.length === 0) {
      message.warning('请先选择文件');
      return;
    }
    setBillUploading(true);
    try {
      const file = billFileList[0].originFileObj!;
      const result = await importBill(file);
      message.success(
        `账单导入成功：${result.total_count} 条，金额 ¥${result.total_amount?.toFixed(2)}，月份 ${result.billing_month}`
      );
      setBillFileList([]);
      fetchBillBatches();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '导入失败');
    } finally {
      setBillUploading(false);
    }
  };

  const fetchBillBatches = async () => {
    setBillLoading(true);
    try {
      const data = await getBillBatches();
      setBillBatches(data);
    } catch {
      message.error('获取批次列表失败');
    } finally {
      setBillLoading(false);
    }
  };

  const handleMatch = async () => {
    if (!matchBillBatchId) {
      message.warning('请选择账单批次');
      return;
    }
    setMatching(true);
    try {
      const result = await matchOwnership({
        bill_batch_id: matchBillBatchId,
        ownership_batch_id: matchOwnershipBatchId ?? undefined,
        directory_batch_id: matchDirectoryBatchId ?? undefined,
      });
      message.success(`归属匹配完成：${result.matched_count} 条已匹配`);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '匹配失败');
    } finally {
      setMatching(false);
    }
  };

  // ==================== Common render helpers ====================
  const renderImportStatus = (status: number) => {
    const info = IMPORT_STATUS_MAP[status] || { label: '未知', color: 'default' };
    return <Tag color={info.color}>{info.label}</Tag>;
  };

  const ownershipColumns = [
    { title: '批次号', dataIndex: 'batch_no', key: 'batch_no' },
    { title: '文件名', dataIndex: 'file_name', key: 'file_name' },
    { title: '总条数', dataIndex: 'total_count', key: 'total_count' },
    { title: '例外数', dataIndex: 'exception_count', key: 'exception_count' },
    { title: '状态', dataIndex: 'import_status', key: 'import_status', render: renderImportStatus },
    {
      title: '导入时间', dataIndex: 'created_at', key: 'created_at',
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
  ];

  const directoryColumns = [
    { title: '批次号', dataIndex: 'batch_no', key: 'batch_no' },
    { title: '文件名', dataIndex: 'file_name', key: 'file_name' },
    { title: '总条数', dataIndex: 'total_count', key: 'total_count' },
    { title: '借调数', dataIndex: 'seconded_count', key: 'seconded_count' },
    { title: '状态', dataIndex: 'import_status', key: 'import_status', render: renderImportStatus },
    {
      title: '导入时间', dataIndex: 'created_at', key: 'created_at',
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
  ];

  const billColumns = [
    { title: '批次号', dataIndex: 'batch_no', key: 'batch_no' },
    { title: '月份', dataIndex: 'billing_month', key: 'billing_month' },
    { title: '文件名', dataIndex: 'file_name', key: 'file_name' },
    { title: '条数', dataIndex: 'total_count', key: 'total_count' },
    {
      title: '总金额', dataIndex: 'total_amount', key: 'total_amount',
      render: (v: number) => v != null ? `¥${v.toFixed(2)}` : '-',
    },
    { title: '状态', dataIndex: 'import_status', key: 'import_status', render: renderImportStatus },
    {
      title: '导入时间', dataIndex: 'created_at', key: 'created_at',
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
        <Button icon={<UploadOutlined />}>选择文件</Button>
      </Upload>
      <Button
        type="primary"
        onClick={onUpload}
        loading={uploading}
        disabled={fileList.length === 0}
      >
        开始导入
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
                  <PhoneOutlined /> 号码归属
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
                      刷新批次列表
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
                  <ContactsOutlined /> 通讯录
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
                      刷新批次列表
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
                  <FileTextOutlined /> 电信账单
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
                  <div style={{ marginTop: 24 }}>
                    <Button
                      size="small"
                      onClick={fetchBillBatches}
                      loading={billLoading}
                      style={{ marginBottom: 8 }}
                    >
                      刷新批次列表
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
                        <LinkOutlined /> 归属匹配
                      </span>
                    }
                    style={{ marginTop: 24 }}
                    size="small"
                  >
                    <Alert
                      message="归属优先级：P0 例外标记 > P1 通讯录 > P2 号码归属 > P3 未归属"
                      type="info"
                      showIcon
                      style={{ marginBottom: 16 }}
                    />
                    <Space wrap>
                      <div>
                        <Text type="secondary">账单批次</Text>
                        <br />
                        <Select
                          style={{ width: 240 }}
                          placeholder="选择账单批次"
                          value={matchBillBatchId}
                          onChange={setMatchBillBatchId}
                          options={billBatches.map((b) => ({
                            value: b.id,
                            label: `${b.batch_no} (${b.billing_month})`,
                          }))}
                        />
                      </div>
                      <div>
                        <Text type="secondary">号码归属批次</Text>
                        <br />
                        <Select
                          style={{ width: 240 }}
                          placeholder="选择归属批次（可选）"
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
                        <Text type="secondary">通讯录批次</Text>
                        <br />
                        <Select
                          style={{ width: 240 }}
                          placeholder="选择通讯录批次（可选）"
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
                        执行匹配
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
