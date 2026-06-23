import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Button, Descriptions, Row, Col, message, Empty } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BillBatch } from '../types/bill';
import type { L1SummaryRow } from '../types/allocation';
import { getBillBatches, getL1SummaryData, getL1SummaryUrl } from '../api/allocation';

export default function L1SummaryPage() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [rows, setRows] = useState<L1SummaryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [rowsLoading, setRowsLoading] = useState(false);

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getBillBatches();
      setBatches(data);
    } catch {
      message.error(t('l1Summary.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);

  // 自动选择最近月份
  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  useEffect(() => {
    if (selectedBatchId) {
      setRowsLoading(true);
      getL1SummaryData(selectedBatchId)
        .then(setRows)
        .catch(() => message.error(t('l1Summary.fetchFailed')))
        .finally(() => setRowsLoading(false));
    }
  }, [selectedBatchId, t]);

  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  const money = (v: number) => v ? `¥${v.toFixed(2)}` : '-';
  const dur = (v: number) => v ? v.toFixed(1) : '-';

  // 合计行
  const grandTotal = useMemo(() => {
    if (rows.length === 0) return null;
    const init: L1SummaryRow = {
      branch_name: '', platform_fee: 0, monthly_rent_code: 0,
      domestic_duration: 0, transfer_duration: 0, domestic_fee: 0,
      international_duration: 0, international_fee: 0, call_subtotal: 0,
      recording_fee: 0, crbt_fee: 0, flash_fee: 0, total_fee: 0,
      phone_count: 0, confirmed: 0, pending: 0,
    };
    return rows.reduce((acc, r) => {
      acc.platform_fee += r.platform_fee;
      acc.monthly_rent_code += r.monthly_rent_code;
      acc.domestic_duration += r.domestic_duration;
      acc.transfer_duration += r.transfer_duration;
      acc.domestic_fee += r.domestic_fee;
      acc.international_duration += r.international_duration;
      acc.international_fee += r.international_fee;
      acc.call_subtotal += r.call_subtotal;
      acc.recording_fee += r.recording_fee;
      acc.crbt_fee += r.crbt_fee;
      acc.flash_fee += r.flash_fee;
      acc.total_fee += r.total_fee;
      acc.phone_count += r.phone_count;
      acc.confirmed += r.confirmed;
      acc.pending += r.pending;
      return acc;
    }, init);
  }, [rows]);

  const columns = [
    { title: t('l1Summary.branchCol'), dataIndex: 'branch_name', key: 'branch_name', width: 120, fixed: 'left' as const },
    { title: t('l1Summary.platformFeeCol'), dataIndex: 'platform_fee', key: 'platform_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Summary.monthlyRentCodeCol'), dataIndex: 'monthly_rent_code', key: 'monthly_rent_code', width: 100, align: 'right' as const, render: money },
    { title: t('l1Summary.domesticDurationCol'), dataIndex: 'domestic_duration', key: 'domestic_duration', width: 110, align: 'right' as const, render: dur },
    { title: t('l1Summary.transferDurationCol'), dataIndex: 'transfer_duration', key: 'transfer_duration', width: 110, align: 'right' as const, render: dur },
    { title: t('l1Summary.domesticFeeCol'), dataIndex: 'domestic_fee', key: 'domestic_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Summary.intlDurationCol'), dataIndex: 'international_duration', key: 'international_duration', width: 100, align: 'right' as const, render: dur },
    { title: t('l1Summary.intlFeeCol'), dataIndex: 'international_fee', key: 'international_fee', width: 90, align: 'right' as const, render: money },
    { title: t('l1Summary.callSubtotalCol'), dataIndex: 'call_subtotal', key: 'call_subtotal', width: 100, align: 'right' as const, render: money },
    { title: t('l1Summary.recordingFeeCol'), dataIndex: 'recording_fee', key: 'recording_fee', width: 90, align: 'right' as const, render: money },
    { title: t('l1Summary.crbtFeeCol'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 80, align: 'right' as const, render: money },
    { title: t('l1Summary.flashFeeCol'), dataIndex: 'flash_fee', key: 'flash_fee', width: 80, align: 'right' as const, render: money },
    { title: t('l1Summary.totalCol'), dataIndex: 'total_fee', key: 'total_fee', width: 110, align: 'right' as const,
      render: (v: number) => <strong>{money(v)}</strong>,
    },
    { title: t('l1Summary.phoneCountCol'), dataIndex: 'phone_count', key: 'phone_count', width: 70, align: 'right' as const },
    { title: t('l1Summary.confirmedCol'), dataIndex: 'confirmed', key: 'confirmed', width: 70, align: 'right' as const },
    { title: t('l1Summary.pendingCol'), dataIndex: 'pending', key: 'pending', width: 70, align: 'right' as const },
  ];

  const dataSource = rows.map((r, i) => ({ ...r, key: i }));

  return (
    <div>
      <Card>
        <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <span style={{ marginRight: 8 }}>{t('l1Summary.selectMonth')}</span>
            <Select
              style={{ width: 280 }}
              placeholder="选择月份"
              loading={loading}
              value={selectedBatchId}
              onChange={setSelectedBatchId}
              options={batches
                .sort((a, b) => b.billing_month.localeCompare(a.billing_month))
                .map(b => ({ label: `${b.billing_month} (${b.batch_no})`, value: b.id }))}
            />
          </Col>
          <Col>
            {selectedBatchId && (
              <Button type="primary" icon={<DownloadOutlined />}
                onClick={() => window.open(getL1SummaryUrl(selectedBatchId), '_blank')}>
                {t('l1Summary.exportL1')}
              </Button>
            )}
          </Col>
        </Row>

        {selectedBatchId && rows.length > 0 && grandTotal && (
          <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
            <Descriptions.Item label={t('l1Summary.descMonth')}>{selectedBatch?.billing_month}</Descriptions.Item>
            <Descriptions.Item label={t('l1Summary.descTotalFee')}>¥{grandTotal.total_fee.toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label={t('l1Summary.descTotalPhones')}>{grandTotal.phone_count}</Descriptions.Item>
            <Descriptions.Item label={t('l1Summary.descBranchCount')}>{rows.length}</Descriptions.Item>
          </Descriptions>
        )}

        {selectedBatchId && rows.length > 0 ? (
          <Table
            columns={columns}
            dataSource={dataSource}
            rowKey="key"
            size="small"
            loading={rowsLoading}
            pagination={false}
            scroll={{ x: 1700 }}
            summary={() => grandTotal ? (
              <Table.Summary.Row>
                <Table.Summary.Cell index={0}><strong>{t('l1Summary.grandTotalRow')}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={1} align="right">{money(grandTotal.platform_fee)}</Table.Summary.Cell>
                <Table.Summary.Cell index={2} align="right">{money(grandTotal.monthly_rent_code)}</Table.Summary.Cell>
                <Table.Summary.Cell index={3} align="right">{dur(grandTotal.domestic_duration)}</Table.Summary.Cell>
                <Table.Summary.Cell index={4} align="right">{dur(grandTotal.transfer_duration)}</Table.Summary.Cell>
                <Table.Summary.Cell index={5} align="right">{money(grandTotal.domestic_fee)}</Table.Summary.Cell>
                <Table.Summary.Cell index={6} align="right">{dur(grandTotal.international_duration)}</Table.Summary.Cell>
                <Table.Summary.Cell index={7} align="right">{money(grandTotal.international_fee)}</Table.Summary.Cell>
                <Table.Summary.Cell index={8} align="right">{money(grandTotal.call_subtotal)}</Table.Summary.Cell>
                <Table.Summary.Cell index={9} align="right">{money(grandTotal.recording_fee)}</Table.Summary.Cell>
                <Table.Summary.Cell index={10} align="right">{money(grandTotal.crbt_fee)}</Table.Summary.Cell>
                <Table.Summary.Cell index={11} align="right">{money(grandTotal.flash_fee)}</Table.Summary.Cell>
                <Table.Summary.Cell index={12} align="right"><strong>¥{grandTotal.total_fee.toFixed(2)}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={13} align="right"><strong>{grandTotal.phone_count}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={14} align="right">{grandTotal.confirmed}</Table.Summary.Cell>
                <Table.Summary.Cell index={15} align="right">{grandTotal.pending}</Table.Summary.Cell>
              </Table.Summary.Row>
            ) : null}
          />
        ) : (
          !rowsLoading && <Empty description={t('l1Summary.noData')} />
        )}
      </Card>
    </div>
  );
}
