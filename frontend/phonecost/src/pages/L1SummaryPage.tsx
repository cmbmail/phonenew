import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Button, Descriptions, Row, Col, Tabs, message, Empty, Statistic } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BillBatch } from '../types/bill';
import type { L1SummaryRow } from '../types/allocation';
import { getBillBatches, getL1SummaryData, getL1DetailData, getL1SummaryUrl } from '../api/allocation';

const SHEET_TYPES = ['CALL', 'RECORDING', 'CRBT', 'FLASH_MSG'] as const;
type SheetType = typeof SHEET_TYPES[number];

export default function L1SummaryPage() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [rows, setRows] = useState<L1SummaryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [rowsLoading, setRowsLoading] = useState(false);

  // 分摊明细数据
  const [detailData, setDetailData] = useState<Record<SheetType, Record<string, unknown>[]>>({
    CALL: [], RECORDING: [], CRBT: [], FLASH_MSG: [],
  });
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailLoaded, setDetailLoaded] = useState(false);

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
      // 重置明细数据
      setDetailData({ CALL: [], RECORDING: [], CRBT: [], FLASH_MSG: [] });
      setDetailLoaded(false);
    }
  }, [selectedBatchId, t]);

  // 加载全部4种明细数据
  const fetchAllDetails = useCallback(async () => {
    if (!selectedBatchId || detailLoaded) return;
    setDetailLoading(true);
    try {
      const results = await Promise.all(
        SHEET_TYPES.map(st => getL1DetailData(selectedBatchId, st).then(d => [st, d] as const))
      );
      const newData = { CALL: [], RECORDING: [], CRBT: [], FLASH_MSG: [] } as Record<SheetType, Record<string, unknown>[]>;
      for (const [st, d] of results) newData[st] = d;
      setDetailData(newData);
      setDetailLoaded(true);
    } catch {
      message.error(t('l1Summary.fetchFailed'));
    } finally {
      setDetailLoading(false);
    }
  }, [selectedBatchId, detailLoaded, t]);

  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  const money = (v: unknown) => {
    const n = Number(v);
    return n ? `¥${n.toFixed(2)}` : '-';
  };
  const dur = (v: unknown) => {
    const n = Number(v);
    return n ? n.toFixed(1) : '-';
  };

  // ========== 分摊汇总 ==========
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

  const summaryColumns = [
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

  // ========== 分摊明细 - 按号码费用 (CALL) ==========
  const callColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120, fixed: 'left' as const },
    { title: t('l1Detail.orgCol'), dataIndex: 'org_name', key: 'org_name', width: 180 },
    { title: t('l1Detail.platformFeeCol'), dataIndex: 'platform_fee', key: 'platform_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.monthlyRentCodeCol'), dataIndex: 'monthly_rent_code', key: 'monthly_rent_code', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.domesticDurationCol'), dataIndex: 'domestic_duration', key: 'domestic_duration', width: 110, align: 'right' as const, render: dur },
    { title: t('l1Detail.transferDurationCol'), dataIndex: 'transfer_duration', key: 'transfer_duration', width: 110, align: 'right' as const, render: dur },
    { title: t('l1Detail.domesticFeeCol'), dataIndex: 'domestic_fee', key: 'domestic_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.intlDurationCol'), dataIndex: 'international_duration', key: 'international_duration', width: 100, align: 'right' as const, render: dur },
    { title: t('l1Detail.intlFeeCol'), dataIndex: 'international_fee', key: 'international_fee', width: 90, align: 'right' as const, render: money },
    { title: t('l1Detail.totalFeeCol'), dataIndex: 'total_fee', key: 'total_fee', width: 100, align: 'right' as const, render: (v: number) => <strong>{money(v)}</strong> },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  // ========== 分摊明细 - 录音费用 (RECORDING) ==========
  const recordingColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120 },
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.orgCol'), dataIndex: 'org_name', key: 'org_name', width: 200 },
    { title: t('l1Detail.recordingDirCol'), dataIndex: 'recording_dir', key: 'recording_dir', width: 200 },
    { title: t('l1Detail.recordingFeeCol'), dataIndex: 'recording_fee', key: 'recording_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  // ========== 分摊明细 - 彩铃费用 (CRBT) ==========
  const crbtColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120 },
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.orgCol'), dataIndex: 'org_name', key: 'org_name', width: 200 },
    { title: t('l1Detail.crbtFeeCol'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  // ========== 分摊明细 - 闪信费用 (FLASH_MSG) ==========
  const flashColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120 },
    { title: t('l1Detail.orgCol'), dataIndex: 'org_name', key: 'org_name', width: 200 },
    { title: t('l1Detail.flashMonthCol'), dataIndex: 'flash_month', key: 'flash_month', width: 90 },
    { title: t('l1Detail.flashCountCol'), dataIndex: 'flash_count', key: 'flash_count', width: 90, align: 'right' as const, render: dur },
    { title: t('l1Detail.flashFeeCol'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  // 统计卡片
  const detailStats = useMemo(() => {
    const sum = (data: Record<string, unknown>[], field: string) =>
      data.reduce((s, r) => s + (Number(r[field]) || 0), 0);
    return {
      callCount: detailData.CALL.length,
      callTotal: sum(detailData.CALL, 'total_fee'),
      recCount: detailData.RECORDING.length,
      recTotal: sum(detailData.RECORDING, 'recording_fee'),
      crbtCount: detailData.CRBt.length,
      crbtTotal: sum(detailData.CRBt, 'crbt_fee'),
      flashCount: detailData.FLASH_MSG.length,
      flashTotal: sum(detailData.FLASH_MSG, 'flash_msg_fee'),
    };
  }, [detailData]);

  const summaryDataSource = rows.map((r, i) => ({ ...r, key: i }));

  const renderDetailTab = (sheetType: SheetType) => {
    const data = detailData[sheetType];
    let columns;
    let scrollX;
    switch (sheetType) {
      case 'CALL': columns = callColumns; scrollX = 1200; break;
      case 'RECORDING': columns = recordingColumns; scrollX = 800; break;
      case 'CRBT': columns = crbtColumns; scrollX = 600; break;
      case 'FLASH_MSG': columns = flashColumns; scrollX = 700; break;
    }
    return (
      <Table
        columns={columns}
        dataSource={data.map((r, i) => ({ ...r, key: i }))}
        rowKey="key"
        size="small"
        loading={detailLoading}
        pagination={{ pageSize: 100, showSizeChanger: true, showTotal: (total) => `${total} 条` }}
        scroll={{ x: scrollX }}
      />
    );
  };

  const mainTabs = [
    {
      key: 'summary',
      label: t('l1Summary.title'),
      children: (
        <>
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
              columns={summaryColumns}
              dataSource={summaryDataSource}
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
        </>
      ),
    },
    {
      key: 'detail',
      label: t('l1Detail.title'),
      children: (
        <>
          {detailLoaded && (
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={6}><Statistic title={t('l1Detail.callTab')} value={detailStats.callCount} suffix={`¥${detailStats.callTotal.toFixed(2)}`} /></Col>
              <Col span={6}><Statistic title={t('l1Detail.recordingTab')} value={detailStats.recCount} suffix={`¥${detailStats.recTotal.toFixed(2)}`} /></Col>
              <Col span={6}><Statistic title={t('l1Detail.crbtTab')} value={detailStats.crbtCount} suffix={`¥${detailStats.crbtTotal.toFixed(2)}`} /></Col>
              <Col span={6}><Statistic title={t('l1Detail.flashTab')} value={detailStats.flashCount} suffix={`¥${detailStats.flashTotal.toFixed(2)}`} /></Col>
            </Row>
          )}
          <Tabs
            type="card"
            onChange={() => fetchAllDetails()}
            onTabClick={() => { if (!detailLoaded) fetchAllDetails(); }}
            items={[
              { key: 'CALL', label: t('l1Detail.callTab'), children: renderDetailTab('CALL') },
              { key: 'RECORDING', label: t('l1Detail.recordingTab'), children: renderDetailTab('RECORDING') },
              { key: 'CRBT', label: t('l1Detail.crbtTab'), children: renderDetailTab('CRBT') },
              { key: 'FLASH_MSG', label: t('l1Detail.flashTab'), children: renderDetailTab('FLASH_MSG') },
            ]}
          />
        </>
      ),
    },
  ];

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

        {selectedBatchId && (
          <Tabs
            type="card"
            onChange={(key) => { if (key === 'detail') fetchAllDetails(); }}
            items={mainTabs}
          />
        )}
      </Card>
    </div>
  );
}
