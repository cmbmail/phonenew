import { useState, useEffect } from 'react';
import { Card, Table, Tag, Button, Space, Modal, Input, Select, message, Descriptions } from 'antd';
import { CheckOutlined, UndoOutlined, DownloadOutlined } from '@ant-design/icons';
import type { BillBatch } from '../types/bill';
import type { AllocationResult } from '../types/allocation';
import { CONFIRM_STATUS_MAP } from '../types/allocation';
import {
  getBillBatches,
  getAllocationResults,
  confirmAllocation,
  confirmAllAllocation,
  withdrawAllocation,
  getExportSummaryUrl,
  getExportDetailUrl,
} from '../api/allocation';

export default function AllocationPage() {
  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [resultsLoading, setResultsLoading] = useState(false);
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

  useEffect(() => {
    if (selectedBatchId) fetchResults(selectedBatchId);
  }, [selectedBatchId]);

  const handleConfirm = async (batchId: number, orgId: number) => {
    try {
      await confirmAllocation(batchId, orgId);
      message.success('确认成功');
      fetchResults(batchId);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '确认失败');
    }
  };

  const handleConfirmAll = async () => {
    if (!selectedBatchId) return;
    try {
      const res = await confirmAllAllocation(selectedBatchId);
      message.success(`批量确认完成：${res.confirmed_count} 条`);
      fetchResults(selectedBatchId);
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

  const resultColumns = [
    {
      title: '组织名称', dataIndex: 'org_name', key: 'org_name', width: 180,
      render: (name: string, r: AllocationResult) =>
        r.org_id === -1 ? <Tag color="red">未归属</Tag> : name,
    },
    {
      title: '月租费', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '合计', dataIndex: 'total_fee', key: 'total_fee', width: 110,
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

  const totalFee = results.reduce((sum, r) => sum + (r.total_fee || 0), 0);
  const confirmedCount = results.filter(r => r.confirm_status === 1).length;
  const pendingCount = results.filter(r => r.confirm_status === 0).length;

  return (
    <div>
      <Card>
        <Space style={{ marginBottom: 16 }}>
          <span>选择账单批次：</span>
          <Select
            style={{ width: 300 }}
            placeholder="请选择批次"
            loading={loading}
            value={selectedBatchId}
            onChange={setSelectedBatchId}
            options={batches.map(b => ({ label: `${b.batch_no} (${b.billing_month})`, value: b.id }))}
          />
          {selectedBatchId && results.length > 0 && (
            <>
              <Button type="primary" icon={<CheckOutlined />} onClick={handleConfirmAll}>
                批量确认
              </Button>
              <Button icon={<DownloadOutlined />}
                onClick={() => handleExport(getExportSummaryUrl(selectedBatchId))}>
                导出汇总
              </Button>
              <Button icon={<DownloadOutlined />}
                onClick={() => handleExport(getExportDetailUrl(selectedBatchId))}>
                导出明细
              </Button>
            </>
          )}
        </Space>

        {selectedBatchId && (
          <>
            <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="组织数">{results.length}</Descriptions.Item>
              <Descriptions.Item label="总费用">¥{totalFee.toFixed(2)}</Descriptions.Item>
              <Descriptions.Item label="已确认">{confirmedCount}</Descriptions.Item>
              <Descriptions.Item label="待确认">{pendingCount}</Descriptions.Item>
            </Descriptions>

            <Table
              columns={resultColumns}
              dataSource={results}
              rowKey="id"
              size="small"
              loading={resultsLoading}
              pagination={{ pageSize: 20 }}
            />
          </>
        )}
      </Card>

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
