import { useState, useEffect, useCallback } from 'react';
import { Card, Table, Tag, Button, Space, Modal, Input, message, Descriptions } from 'antd';
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
import { useTranslation } from 'react-i18next';
import { getErrorMessage } from '../types/api';
import dayjs from 'dayjs';

export default function BillManagement() {
  const { t } = useTranslation();
  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBatch, setSelectedBatch] = useState<BillBatch | null>(null);
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [resultsLoading, setResultsLoading] = useState(false);
  const [calculatingId, setCalculatingId] = useState<number | null>(null);
  const [withdrawModal, setWithdrawModal] = useState<{ open: boolean; result?: AllocationResult }>({ open: false });
  const [withdrawReason, setWithdrawReason] = useState('');

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getBillBatches();
      setBatches(data);
    } catch {
      message.error(t('bill.fetchBatchesFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);

  const fetchResults = useCallback(async (batchId: number) => {
    setResultsLoading(true);
    try {
      const data = await getAllocationResults(batchId);
      setResults(data);
    } catch {
      message.error(t('bill.fetchResultsFailed'));
    } finally {
      setResultsLoading(false);
    }
  }, [t]);

  const handleCalculate = async (batchId: number) => {
    setCalculatingId(batchId);
    try {
      const res = await calculateAllocation(batchId);
      message.success(t('bill.calculateSuccess', { orgCount: res.org_count }));
      const updatedBatches = await getBillBatches();
      setBatches(updatedBatches);
      // Update selectedBatch to reflect new status
      const updated = updatedBatches.find(b => b.id === batchId);
      if (updated) setSelectedBatch(updated);
      fetchResults(batchId);
    } catch (err) {
      message.error(getErrorMessage(err, t('bill.calcFailed')));
    } finally {
      setCalculatingId(null);
    }
  };

  const handleConfirm = async (batchId: number, orgId: number) => {
    try {
      await confirmAllocation(batchId, orgId);
      message.success(t('bill.confirmSuccess'));
      fetchResults(batchId);
    } catch (err) {
      message.error(getErrorMessage(err, t('bill.confirmFailed')));
    }
  };

  const handleConfirmAll = async (batchId: number) => {
    try {
      const res = await confirmAllAllocation(batchId);
      message.success(t('bill.confirmAllSuccess', { count: res.confirmed_count }));
      fetchResults(batchId);
    } catch (err) {
      message.error(getErrorMessage(err, t('bill.confirmAllFailed')));
    }
  };

  const handleWithdraw = async () => {
    if (!withdrawModal.result || !withdrawReason.trim()) {
      message.warning(t('bill.withdrawReasonRequired'));
      return;
    }
    try {
      await withdrawAllocation(withdrawModal.result.batch_id, withdrawModal.result.org_id, withdrawReason);
      message.success(t('bill.withdrawSuccess'));
      setWithdrawModal({ open: false });
      setWithdrawReason('');
      fetchResults(withdrawModal.result.batch_id);
    } catch (err) {
      message.error(getErrorMessage(err, t('bill.withdrawFailed')));
    }
  };

  const handleExport = (url: string) => {
    window.open(url, '_blank', 'noopener,noreferrer');
  };

  const batchColumns = [
    { title: t('bill.batchNo'), dataIndex: 'batch_no', key: 'batch_no', width: 200 },
    { title: t('bill.month'), dataIndex: 'billing_month', key: 'billing_month', width: 90 },
    { title: t('bill.fileName'), dataIndex: 'file_name', key: 'file_name', ellipsis: true },
    { title: t('bill.count'), dataIndex: 'total_count', key: 'total_count', width: 70 },
    {
      title: t('bill.totalAmountCol'), dataIndex: 'total_amount', key: 'total_amount', width: 110,
      render: (v: unknown) => v != null ? `¥${Number(v).toFixed(2)}` : '-',
    },
    {
      title: t('bill.status'), dataIndex: 'status', key: 'status', width: 90,
      render: (s: number) => <Tag color={BILL_STATUS_COLORS[s] || 'default'}>{BILL_STATUS_LABELS[s] || t('bill.unknown')}</Tag>,
    },
    {
      title: t('bill.importTime'), dataIndex: 'created_at', key: 'created_at', width: 150,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
    },
    {
      title: t('bill.actions'), key: 'actions', width: 200,
      render: (_unused: unknown, record: BillBatch) => (
        <Space size="small">
          <Button size="small" onClick={(e) => { e.stopPropagation(); setSelectedBatch(record); fetchResults(record.id); }}>
            {t('bill.viewAllocation')}
          </Button>
          {record.status === 0 && (
            <Button size="small" type="primary" icon={<CalculatorOutlined />}
              onClick={(e) => { e.stopPropagation(); handleCalculate(record.id); }} loading={calculatingId === record.id}>
              {t('bill.calculateAllocation')}
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const resultColumns = [
    {
      title: t('bill.orgLabel'), dataIndex: 'org_name', key: 'org_name', width: 180,
      render: (name: string, r: AllocationResult) =>
        r.org_id === -1 ? <Tag color="red">{t('bill.unassigned')}</Tag> : name,
    },
    { title: t('bill.phoneCount'), dataIndex: 'phone_count', key: 'phone_count', width: 80 },
    {
      title: t('bill.monthlyRent'), dataIndex: 'monthly_rent', key: 'monthly_rent', width: 90,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('bill.callFee'), dataIndex: 'call_fee', key: 'call_fee', width: 90,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('bill.recordingFee'), dataIndex: 'recording_fee', key: 'recording_fee', width: 90,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('bill.crbtFee'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 90,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('bill.flashMsgFee'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 90,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('bill.totalFee'), dataIndex: 'total_fee', key: 'total_fee', width: 100,
      render: (v: number) => <strong>{v != null ? `¥${v.toFixed(2)}` : '-'}</strong>,
    },
    {
      title: t('bill.confirmStatus'), dataIndex: 'confirm_status', key: 'confirm_status', width: 90,
      render: (s: number) => {
        const info = CONFIRM_STATUS_MAP[s] || { label: t('bill.unknown'), color: 'default' };
        return <Tag color={info.color}>{info.label}</Tag>;
      },
    },
    {
      title: t('bill.actions'), key: 'actions', width: 140,
      render: (_unused: unknown, record: AllocationResult) => (
        <Space size="small">
          {record.confirm_status === 0 && (
            <Button size="small" type="primary" icon={<CheckOutlined />}
              onClick={() => handleConfirm(record.batch_id, record.org_id)}>
              {t('bill.confirmBtn')}
            </Button>
          )}
          {record.confirm_status === 1 && (
            <Button size="small" danger icon={<UndoOutlined />}
              onClick={() => setWithdrawModal({ open: true, result: record })}>
              {t('bill.withdrawBtn')}
            </Button>
          )}
        </Space>
      ),
    },
  ];

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
          title={t('bill.allocationResultTitle', { batchNo: selectedBatch.batch_no, month: selectedBatch.billing_month })}
          style={{ marginTop: 16 }}
          extra={
            <Space>
              {results.length > 0 && (
                <>
                  <Button onClick={() => handleConfirmAll(selectedBatch.id)} icon={<CheckOutlined />}>
                    {t('bill.confirmAll')}
                  </Button>
                  {selectedBatch.status >= 1 && (
                    <>
                      <Button icon={<DownloadOutlined />}
                        onClick={() => handleExport(getExportSummaryUrl(selectedBatch.id))}>
                        {t('bill.exportSummaryTooltip')}
                      </Button>
                      <Button icon={<DownloadOutlined />}
                        onClick={() => handleExport(getExportDetailUrl(selectedBatch.id))}>
                        {t('bill.exportDetailTooltip')}
                      </Button>
                    </>
                  )}
                </>
              )}
              <Button onClick={() => fetchResults(selectedBatch.id)}>{t('bill.refresh')}</Button>
            </Space>
          }
        >
          <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
            <Descriptions.Item label={t('bill.statsOrgs')}>{results.length}</Descriptions.Item>
            <Descriptions.Item label={t('bill.statsPhones')}>{totalPhones}</Descriptions.Item>
            <Descriptions.Item label={t('bill.statsTotalFee')}>¥{totalFee.toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label={t('bill.statsConfirmed')}>{confirmedCount}/{results.length}</Descriptions.Item>
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
        title={t('bill.confirmWithdraw')}
        open={withdrawModal.open}
        onOk={handleWithdraw}
        onCancel={() => { setWithdrawModal({ open: false }); setWithdrawReason(''); }}
        okText={t('bill.withdrawOkText')}
        okButtonProps={{ danger: true }}
      >
        <p>{t('bill.withdrawDesc')}</p>
        <Input.TextArea
          rows={3}
          placeholder={t('bill.withdrawReasonPlaceholder')}
          value={withdrawReason}
          onChange={(e) => setWithdrawReason(e.target.value)}
        />
      </Modal>
    </div>
  );
}
