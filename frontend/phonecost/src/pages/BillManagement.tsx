import { useState, useEffect } from 'react';
import { Card, Table, Tag, Button, Space, Modal, Input, message, Descriptions, Tooltip } from 'antd';
import { CheckOutlined, UndoOutlined, DownloadOutlined, CalculatorOutlined } from '@ant-design/icons';
import type { BillBatch } from '../types/bill';
import type { AllocationResult } from '../types/allocation';
import { BILL_STATUS_LABELS, BILL_STATUS_COLORS } from '../types/bill';
import { CONFIRM_STATUS_MAP } from '../types/allocation';
import {
  getBillBatches,
  getAllocationResults,
  calculateAllocation,
  confirmAllocation,
  confirmAllAllocation,
  withdrawAllocation,
  getExportSummaryUrl,
  getExportDetailUrl,
} from '../api/allocation';
import dayjs from 'dayjs';

export default function BillManagement() {
  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBatch, setSelectedBatch] = useState<BillBatch | null>(null);
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [resultsLoading, setResultsLoading] = useState(false);
  const [calculating, setCalculating] = useState(false);
  const [withdrawModal, setWithdrawModal] = useState<{ open: boolean; result?: AllocationResult }>({ open: false });
  const [withdrawReason, setWithdrawReason] = useState('');

  const fetchBatches = async () => {
    setLoading(true);
    try {
      const data = await getBillBatches();
      setBatches(data);
    } catch {
      message.error('获取账单列表失败');
    } finally {
      setLoading(false);
    }
  };

  const fetchResults = async (batchId: number) => {
    setResultsLoading(true);
    try {
      const data = await getAllocationResults(batchId);
      setResults(data);
    } catch {
      message.error('获取分摊结果失败');
    } finally {
      setResultsLoading(false);
    }
  };

  useEffect(() => { fetchBatches(); }, []);

  const handleCalculate = async (batchId: number) => {
    setCalculating(true);
    try {
      const res = await calculateAllocation(batchId);
      message.success(`分摊计算完成：${res.org_count} 个组织`);
      fetchBatches();
      fetchResults(batchId);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '分摊计算失败');
    } finally {
      setCalculating(false);
    }
  };

  const handleConfirm = async (batchId: number, orgId: number) => {
    try {
      await confirmAllocation(batchId, orgId);
      message.success('确认成功');
      fetchResults(batchId);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '确认失败');
    }
  };

  const handleConfirmAll = async (batchId: number) => {
    try {
      const res = await confirmAllAllocation(batchId);
      message.success(`批量确认完成：${res.confirmed_count} 条`);
      fetchResults(batchId);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '批量确认失败');
    }
  };

  const handleWithdraw = async () => {
    if (!withdrawModal.result || !withdrawReason.trim()) {
      message.warning('请输入撤回原因');
      return;
    }
    try {
      await withdrawAllocation(withdrawModal.result.batch_id, withdrawModal.result.org_id, withdrawReason);
      message.success('撤回成功');
      setWithdrawModal({ open: false });
      setWithdrawReason('');
      fetchResults(withdrawModal.result.batch_id);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '撤回失败');
    }
  };

  const handleExport = (url: string) => {
    window.open(url, '_blank');
  };

  const batchColumns = [
    { title: '批次号', dataIndex: 'batch_no', key: 'batch_no', width: 200 },
    { title: '月份', dataIndex: 'billing_month', key: 'billing_month', width: 90 },
    { title: '文件名', dataIndex: 'file_name', key: 'file_name', ellipsis: true },
    { title: '条数', dataIndex: 'total_count', key: 'total_count', width: 70 },
    {
      title: '总金额', dataIndex: 'total_amount', key: 'total_amount', width: 110,
      render: (v: number) => v != null ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (s: number) => <Tag color={BILL_STATUS_COLORS[s] || 'default'}>{BILL_STATUS_LABELS[s] || '未知'}</Tag>,
    },
    {
      title: '导入时间', dataIndex: 'created_at', key: 'created_at', width: 150,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
    },
    {
      title: '操作', key: 'actions', width: 280,
      render: (_: any, record: BillBatch) => (
        <Space size="small">
          <Button size="small" onClick={() => { setSelectedBatch(record); fetchResults(record.id); }}>
            查看分摊
          </Button>
          {record.status === 0 && (
            <Button size="small" type="primary" icon={<CalculatorOutlined />}
              onClick={() => handleCalculate(record.id)} loading={calculating}>
              分摊计算
            </Button>
          )}
          {record.status >= 1 && (
            <>
              <Tooltip title="导出汇总Excel">
                <Button size="small" icon={<DownloadOutlined />}
                  onClick={() => handleExport(getExportSummaryUrl(record.id))} />
              </Tooltip>
              <Tooltip title="导出明细Excel">
                <Button size="small" icon={<DownloadOutlined />}
                  onClick={() => handleExport(getExportDetailUrl(record.id))} />
              </Tooltip>
            </>
          )}
        </Space>
      ),
    },
  ];

  const resultColumns = [
    {
      title: '组织', dataIndex: 'org_name', key: 'org_name', width: 180,
      render: (name: string, r: AllocationResult) =>
        r.org_id === -1 ? <Tag color="red">未归属</Tag> : name,
    },
    { title: '号码数', dataIndex: 'phone_count', key: 'phone_count', width: 80 },
    {
      title: '月租', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 90,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 90,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 90,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 90,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 90,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '总费用', dataIndex: 'total_fee', key: 'total_fee', width: 100,
      render: (v: number) => <strong>¥{v?.toFixed(2)}</strong>,
    },
    {
      title: '确认状态', dataIndex: 'confirm_status', key: 'confirm_status', width: 90,
      render: (s: number) => {
        const info = CONFIRM_STATUS_MAP[s] || { label: '未知', color: 'default' };
        return <Tag color={info.color}>{info.label}</Tag>;
      },
    },
    {
      title: '操作', key: 'actions', width: 140,
      render: (_: any, record: AllocationResult) => (
        <Space size="small">
          {record.confirm_status === 0 && (
            <Button size="small" type="primary" icon={<CheckOutlined />}
              onClick={() => handleConfirm(record.batch_id, record.org_id)}>
              确认
            </Button>
          )}
          {record.confirm_status === 1 && (
            <Button size="small" danger icon={<UndoOutlined />}
              onClick={() => setWithdrawModal({ open: true, result: record })}>
              撤回
            </Button>
          )}
        </Space>
      ),
    },
  ];

  // Sum totals
  const totalFee = results.reduce((sum, r) => sum + (r.total_fee || 0), 0);
  const totalPhones = results.reduce((sum, r) => sum + (r.phone_count || 0), 0);
  const confirmedCount = results.filter(r => r.confirm_status === 1).length;

  return (
    <div>
      <Card>
        <Table
          columns={batchColumns}
          dataSource={batches}
          rowKey="id"
          size="small"
          loading={loading}
          pagination={{ pageSize: 10 }}
          onRow={(record) => ({
            onClick: () => { setSelectedBatch(record); fetchResults(record.id); },
            style: { cursor: 'pointer' },
          })}
        />
      </Card>

      {selectedBatch && (
        <Card
          title={`分摊结果 — ${selectedBatch.batch_no} (${selectedBatch.billing_month})`}
          style={{ marginTop: 16 }}
          extra={
            <Space>
              {results.length > 0 && (
                <Button onClick={() => handleConfirmAll(selectedBatch.id)} icon={<CheckOutlined />}>
                  全部确认
                </Button>
              )}
              <Button onClick={() => fetchResults(selectedBatch.id)}>刷新</Button>
            </Space>
          }
        >
          <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="组织数">{results.length}</Descriptions.Item>
            <Descriptions.Item label="号码总数">{totalPhones}</Descriptions.Item>
            <Descriptions.Item label="总费用">¥{totalFee.toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label="已确认">{confirmedCount}/{results.length}</Descriptions.Item>
          </Descriptions>

          <Table
            columns={resultColumns}
            dataSource={results}
            rowKey="id"
            size="small"
            loading={resultsLoading}
            pagination={{ pageSize: 20 }}
          />
        </Card>
      )}

      <Modal
        title="撤回确认"
        open={withdrawModal.open}
        onOk={handleWithdraw}
        onCancel={() => { setWithdrawModal({ open: false }); setWithdrawReason(''); }}
        okText="确认撤回"
        okButtonProps={{ danger: true }}
      >
        <p>撤回后该组织的分摊结果将变为"已撤回"状态，如需重新确认请再次操作。</p>
        <Input.TextArea
          rows={3}
          placeholder="请输入撤回原因（必填）"
          value={withdrawReason}
          onChange={(e) => setWithdrawReason(e.target.value)}
        />
      </Modal>
    </div>
  );
}
