import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Button, Descriptions, Row, Col, Tabs, message, Empty, Statistic, Input, Space } from 'antd';
import { DownloadOutlined, SearchOutlined, FileTextOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BillBatch } from '../types/bill';
import type { L1SummaryRow, AllocationDetailRow } from '../types/allocation';
import { getL1SummaryData, getL2DetailData } from '../api/allocation';
import { getBillBatches } from '../api/import';
import { useAbortableEffect } from '../hooks/useAbortableEffect';
import { exportCSV } from '../lib/export';

/** 安全将 typed 数组转换为 Record<string,unknown>[] 供 exportCSV 使用 */
const toPlainRecords = (data: object[]): Record<string, unknown>[] => data as Record<string, unknown>[];

const SHEET_TYPES = ['CALL', 'RECORDING', 'CRBT', 'FLASH_MSG'] as const;
type SheetType = typeof SHEET_TYPES[number];

export default function L2BranchPage() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [l1Branches, setL1Branches] = useState<string[]>([]);
  const [selectedL1Branch, setSelectedL1Branch] = useState<string | null>(null);
  const [summaryRows, setSummaryRows] = useState<L1SummaryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);

  // 分摊明细数据
  const [detailData, setDetailData] = useState<Record<SheetType, AllocationDetailRow[]>>({
    CALL: [], RECORDING: [], CRBT: [], FLASH_MSG: [],
  });
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailLoaded, setDetailLoaded] = useState(false);
  const [detailSearch, setDetailSearch] = useState('');
  const [detailPageSize, setDetailPageSize] = useState(25);

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try { setBatches(await getBillBatches()); } catch { message.error(t('l2Branch.fetchFailed')); } finally { setLoading(false); }
  }, [t]);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);

  // 自动选最近月份
  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  // 批次变化时加载一级分行列表
  useAbortableEffect((signal) => {
    if (selectedBatchId) {
      setL1Branches([]);
      setSelectedL1Branch(null);
      setSummaryRows([]);
      getL1SummaryData(selectedBatchId)
        .then(data => {
          if (signal?.aborted) return;
          const branches = data.map(r => r.l1_branch).filter(Boolean);
          setL1Branches(branches);
          if (branches.length > 0) setSelectedL1Branch(branches[0]);
        })
        .catch(() => { if (!signal?.aborted) message.error(t('l2Branch.fetchFailed')); });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedBatchId]);

  // 月份/一级分行变化时加载分摊汇总（数据源与方法与「分摊汇总」页面一致：getL1SummaryData 按一级分行聚合）
  useEffect(() => {
    if (selectedBatchId && selectedL1Branch) {
      const wasDetailLoaded = detailLoaded;
      setSummaryRows([]);
      setSummaryLoading(true);
      getL1SummaryData(selectedBatchId)
        .then(data => setSummaryRows(data.filter(r => r.l1_branch === selectedL1Branch)))
        .catch(() => message.error(t('l2Branch.fetchFailed')))
        .finally(() => setSummaryLoading(false));
      // 重置明细
      setDetailData({ CALL: [], RECORDING: [], CRBT: [], FLASH_MSG: [] });
      setDetailLoaded(false);
      setDetailSearch('');
      if (wasDetailLoaded) fetchAllDetails();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedBatchId, selectedL1Branch]);

  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  const money = (v: unknown) => {
    const n = Number(v);
    return !isNaN(n) && n !== 0 ? `¥${n.toFixed(2)}` : '-';
  };
  const dur = (v: unknown) => {
    const n = Number(v);
    return !isNaN(n) && n !== 0 ? n.toFixed(1) : '-';
  };

  // ========== 加载全部4种明细数据 ==========
  const fetchAllDetails = useCallback(async () => {
    if (!selectedBatchId || !selectedL1Branch) return;
    setDetailLoading(true);
    try {
      const results = await Promise.all(
        SHEET_TYPES.map(st => getL2DetailData(selectedBatchId, selectedL1Branch, st).then(d => [st, d] as const))
      );
      const newData = { CALL: [], RECORDING: [], CRBT: [], FLASH_MSG: [] } as Record<SheetType, AllocationDetailRow[]>;
      for (const [st, d] of results) newData[st] = d;
      setDetailData(newData);
      setDetailLoaded(true);
    } catch {
      message.error(t('l2Branch.fetchFailed'));
    } finally {
      setDetailLoading(false);
    }
  }, [selectedBatchId, selectedL1Branch, t]);

  // ========== 分摊汇总 ==========
  const grandTotal = useMemo(() => {
    if (summaryRows.length === 0) return null;
    const init: L1SummaryRow = {
      l1_branch: '', platform_fee: 0, monthly_rent_code: 0,
      domestic_duration: 0, transfer_duration: 0, domestic_fee: 0,
      international_duration: 0, international_fee: 0, call_subtotal: 0,
      recording_fee: 0, crbt_fee: 0, flash_fee: 0, total_fee: 0,
      phone_count: 0,
    };
    return summaryRows.reduce((acc, r) => {
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
      return acc;
    }, init);
  }, [summaryRows]);

  const summaryColumns = [
    { title: t('l1Summary.branchCol'), dataIndex: 'l1_branch', key: 'l1_branch', width: 140, fixed: 'left' as const },
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
  ];

  // ========== 分摊明细 columns (same as L1) ==========
  const callColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120, fixed: 'left' as const },
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.orgCol'), dataIndex: 'full_path', key: 'full_path', width: 180 },
    { title: t('l1Detail.orgCodeCol'), dataIndex: 'org_code', key: 'org_code', width: 100 },
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

  const recordingColumns = [
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120 },
    { title: t('l1Detail.orgCol'), dataIndex: 'full_path', key: 'full_path', width: 200 },
    { title: t('l1Detail.orgCodeCol'), dataIndex: 'org_code', key: 'org_code', width: 100 },
    { title: t('l1Detail.recordingDirCol'), dataIndex: 'recording_dir', key: 'recording_dir', width: 200 },
    { title: t('l1Detail.recordingFeeCol'), dataIndex: 'recording_fee', key: 'recording_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  const crbtColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120 },
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.orgCol'), dataIndex: 'full_path', key: 'full_path', width: 200 },
    { title: t('l1Detail.orgCodeCol'), dataIndex: 'org_code', key: 'org_code', width: 100 },
    { title: t('l1Detail.crbtFeeCol'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  const flashColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120 },
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.orgCol'), dataIndex: 'full_path', key: 'full_path', width: 200 },
    { title: t('l1Detail.orgCodeCol'), dataIndex: 'org_code', key: 'org_code', width: 100 },
    { title: t('l1Detail.flashMonthCol'), dataIndex: 'flash_month', key: 'flash_month', width: 90 },
    { title: t('l1Detail.flashCountCol'), dataIndex: 'flash_count', key: 'flash_count', width: 90, align: 'right' as const, render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? String(Math.round(n)) : '-'; } },
    { title: t('l1Detail.flashFeeCol'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  // 搜索过滤
  const filteredDetailData = useMemo(() => {
    const kw = detailSearch.trim().toLowerCase();
    if (!kw) return detailData;
    const filter = (rows: AllocationDetailRow[]) =>
      rows.filter(r =>
        String(r.phone_number || '').toLowerCase().includes(kw) ||
        String(r.extension || '').toLowerCase().includes(kw) ||
        String(r.full_path || '').toLowerCase().includes(kw) ||
        String(r.org_code || '').toLowerCase().includes(kw) ||
        String(r.l2_branch || '').toLowerCase().includes(kw)
      );
    return {
      CALL: filter(detailData.CALL),
      RECORDING: filter(detailData.RECORDING),
      CRBT: filter(detailData["CRBT"]),
      FLASH_MSG: filter(detailData.FLASH_MSG),
    } as Record<SheetType, AllocationDetailRow[]>;
  }, [detailData, detailSearch]);

  // 统计卡片
  const detailStats = useMemo(() => {
    const sum = (data: AllocationDetailRow[], field: keyof AllocationDetailRow) =>
      data.reduce((s, r) => s + (Number(r[field]) || 0), 0);
    return {
      callCount: filteredDetailData.CALL.length,
      callTotal: sum(filteredDetailData.CALL, 'total_fee'),
      recCount: filteredDetailData.RECORDING.length,
      recTotal: sum(filteredDetailData.RECORDING, 'recording_fee'),
      crbtCount: filteredDetailData["CRBT"].length,
      crbtTotal: sum(filteredDetailData["CRBT"], 'crbt_fee'),
      flashCount: filteredDetailData.FLASH_MSG.length,
      flashTotal: sum(filteredDetailData.FLASH_MSG, 'flash_msg_fee'),
    };
  }, [filteredDetailData]);

  const summaryDataSource = summaryRows.map((r, i) => ({ ...r, key: i }));

  const renderDetailTab = (sheetType: SheetType) => {
    const data = filteredDetailData[sheetType];
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
        dataSource={data}
        rowKey={(_, idx) => `${sheetType}-${idx}`}
        size="small"
        loading={detailLoading}
        pagination={{ pageSize: detailPageSize, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], onChange: (_p, s) => setDetailPageSize(s), showTotal: (total) => t('common.paginationTotal', { total }) }}
        scroll={{ x: scrollX }}
      />
    );
  };

  // ========== 报销单数据（按分摊部门汇总，数据源为 4 类分摊明细） ==========
  const reimbursementData = useMemo(() => {
    const feeOf = (row: AllocationDetailRow) =>
      (Number(row.total_fee) || 0) + (Number(row.recording_fee) || 0) + (Number(row.crbt_fee) || 0) + (Number(row.flash_msg_fee) || 0);
    const map = new Map<string, { l1_branch: string; alloc_dept: string; cost_center: string; fee_subtotal: number }>();
    for (const st of SHEET_TYPES) {
      for (const row of detailData[st]) {
        const dept = (row.alloc_dept || '').trim();
        if (!dept) continue;
        const key = dept;
        const prev = map.get(key);
        const costCenter = (row.cost_center || '').trim();
        if (prev) {
          prev.fee_subtotal += feeOf(row);
          if (!prev.cost_center && costCenter) prev.cost_center = costCenter;
        } else {
          map.set(key, { l1_branch: selectedL1Branch || '', alloc_dept: dept, cost_center: costCenter, fee_subtotal: feeOf(row) });
        }
      }
    }
    return Array.from(map.values())
      .map((r, i) => ({ key: i, ...r }))
      .sort((a, b) => a.alloc_dept.localeCompare(b.alloc_dept));
  }, [detailData, selectedL1Branch]);

  const reimbursementTotal = reimbursementData.reduce((s, r) => s + r.fee_subtotal, 0);

  const reimbursementColumns = [
    { title: t('l1Summary.branchCol'), dataIndex: 'l1_branch', key: 'l1_branch', width: 180 },
    { title: t('l1Detail.orgCol'), dataIndex: 'alloc_dept', key: 'alloc_dept', width: 200 },
    { title: t('l1Summary.costCenterCol'), dataIndex: 'cost_center', key: 'cost_center', width: 150 },
    {
      title: t('l2Branch.reimbursementFeeSubtotal'), dataIndex: 'fee_subtotal', key: 'fee_subtotal', width: 150, align: 'right' as const,
      render: (v: number) => <strong>¥{v.toFixed(2)}</strong>,
    },
  ];

  const mainTabs = [
    {
      key: 'summary',
      label: t('l2Branch.summaryTab'),
      children: (
        <>
          {selectedBatchId && selectedL1Branch && summaryRows.length > 0 && grandTotal && (
            <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
              <Descriptions.Item label={t('l2Branch.descMonth')}>{selectedBatch?.billing_month}</Descriptions.Item>
              <Descriptions.Item label={t('l2Branch.descBranch')}>{selectedL1Branch}</Descriptions.Item>
              <Descriptions.Item label={t('l1Summary.descBranchCount')}>{summaryRows.length}</Descriptions.Item>
              <Descriptions.Item label={t('l2Branch.descTotalFee')}>¥{grandTotal.total_fee.toFixed(2)}</Descriptions.Item>
            </Descriptions>
          )}
          {selectedBatchId && selectedL1Branch && summaryRows.length > 0 && (
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
              <Button icon={<DownloadOutlined />} onClick={() => {
                const batch = batches.find(b => b.id === selectedBatchId);
                exportCSV(
                  `分摊汇总_${batch?.billing_month || ''}`,
                  [
                    { title: t('l1Summary.branchCol'), dataIndex: 'l1_branch' },
                    { title: t('l1Summary.platformFeeCol'), dataIndex: 'platform_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l1Summary.monthlyRentCodeCol'), dataIndex: 'monthly_rent_code', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l1Summary.domesticDurationCol'), dataIndex: 'domestic_duration', render: (v: number) => v != null && v !== 0 ? v.toFixed(1) : '' },
                    { title: t('l1Summary.transferDurationCol'), dataIndex: 'transfer_duration', render: (v: number) => v != null && v !== 0 ? v.toFixed(1) : '' },
                    { title: t('l1Summary.domesticFeeCol'), dataIndex: 'domestic_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l1Summary.intlDurationCol'), dataIndex: 'international_duration', render: (v: number) => v != null && v !== 0 ? v.toFixed(1) : '' },
                    { title: t('l1Summary.intlFeeCol'), dataIndex: 'international_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l1Summary.callSubtotalCol'), dataIndex: 'call_subtotal', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l1Summary.recordingFeeCol'), dataIndex: 'recording_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l1Summary.crbtFeeCol'), dataIndex: 'crbt_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l1Summary.flashFeeCol'), dataIndex: 'flash_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l1Summary.totalCol'), dataIndex: 'total_fee', render: (v: number) => v != null ? v.toFixed(2) : '' },
                    { title: t('l1Summary.phoneCountCol'), dataIndex: 'phone_count' },
                  ],
                  toPlainRecords(summaryRows),
                );
              }}>{t('l2Branch.exportSummary')}</Button>
            </div>
          )}
          {selectedBatchId && selectedL1Branch && summaryRows.length > 0 ? (
            <Table
              columns={summaryColumns}
              dataSource={summaryDataSource}
              rowKey="key"
              size="small"
              loading={summaryLoading}
              pagination={false}
              scroll={{ x: 1700 }}
              summary={() => grandTotal ? (
                <Table.Summary.Row>
                  <Table.Summary.Cell index={0}><strong>{t('l2Branch.totalRow')}</strong></Table.Summary.Cell>
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
                </Table.Summary.Row>
              ) : null}
            />
          ) : (
            !summaryLoading && selectedL1Branch && <Empty description={t('l2Branch.noData')} />
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
              <Col xs={12} sm={12} md={6}><Statistic title={t('l1Detail.callTab')} value={detailStats.callCount} suffix={`¥${detailStats.callTotal.toFixed(2)}`} /></Col>
              <Col xs={12} sm={12} md={6}><Statistic title={t('l1Detail.recordingTab')} value={detailStats.recCount} suffix={`¥${detailStats.recTotal.toFixed(2)}`} /></Col>
              <Col xs={12} sm={12} md={6}><Statistic title={t('l1Detail.crbtTab')} value={detailStats.crbtCount} suffix={`¥${detailStats.crbtTotal.toFixed(2)}`} /></Col>
              <Col xs={12} sm={12} md={6}><Statistic title={t('l1Detail.flashTab')} value={detailStats.flashCount} suffix={`¥${detailStats.flashTotal.toFixed(2)}`} /></Col>
            </Row>
          )}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <Input
              prefix={<SearchOutlined />}
              placeholder={t('l1Detail.searchPlaceholder')}
              allowClear
              value={detailSearch}
              onChange={e => setDetailSearch(e.target.value)}
              style={{ width: 320 }}
            />
            {detailLoaded && detailData.CALL.length + detailData.RECORDING.length + detailData.CRBT.length + detailData.FLASH_MSG.length > 0 && (
              <Button icon={<DownloadOutlined />} onClick={() => {
                const batch = batches.find(b => b.id === selectedBatchId);
                const allRows: Record<string, unknown>[] = [];
                const sheetLabels: Record<string, string> = { CALL: t('l1Detail.callTab'), RECORDING: t('l1Detail.recordingTab'), CRBT: t('l1Detail.crbtTab'), FLASH_MSG: t('l1Detail.flashTab') };
                for (const st of SHEET_TYPES) {
                  for (const row of detailData[st]) {
                    allRows.push({ ...row, _sheet_type: sheetLabels[st] });
                  }
                }
                exportCSV(
                  `分摊明细_${selectedL1Branch || ''}_${batch?.billing_month || ''}`,
                  [
                    { title: t('l2Branch.detailSheetType'), dataIndex: '_sheet_type' },
                    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number' },
                    { title: t('l1Detail.extensionCol'), dataIndex: 'extension' },
                    { title: t('l1Detail.orgCol'), dataIndex: 'full_path' },
                    { title: t('l1Detail.orgCodeCol'), dataIndex: 'org_code' },
                    { title: t('l1Detail.platformFeeCol'), dataIndex: 'platform_fee', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(2) : ''; } },
                    { title: t('l1Detail.monthlyRentCodeCol'), dataIndex: 'monthly_rent_code', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(2) : ''; } },
                    { title: t('l1Detail.domesticDurationCol'), dataIndex: 'domestic_duration', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(1) : ''; } },
                    { title: t('l1Detail.transferDurationCol'), dataIndex: 'transfer_duration', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(1) : ''; } },
                    { title: t('l1Detail.domesticFeeCol'), dataIndex: 'domestic_fee', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(2) : ''; } },
                    { title: t('l1Detail.intlDurationCol'), dataIndex: 'international_duration', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(1) : ''; } },
                    { title: t('l1Detail.intlFeeCol'), dataIndex: 'international_fee', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(2) : ''; } },
                    { title: t('l1Detail.recordingDirCol'), dataIndex: 'recording_dir' },
                    { title: t('l1Detail.recordingFeeCol'), dataIndex: 'recording_fee', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(2) : ''; } },
                    { title: t('l1Detail.crbtFeeCol'), dataIndex: 'crbt_fee', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(2) : ''; } },
                    { title: t('l1Detail.flashMonthCol'), dataIndex: 'flash_month' },
                    { title: t('l1Detail.flashCountCol'), dataIndex: 'flash_count', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? String(Math.round(n)) : ''; } },
                    { title: t('l1Detail.flashFeeCol'), dataIndex: 'flash_msg_fee', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(2) : ''; } },
                    { title: t('l1Detail.totalFeeCol'), dataIndex: 'total_fee', render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? n.toFixed(2) : ''; } },
                    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source' },
                  ],
                  allRows,
                );
              }}>{t('l2Branch.exportDetail')}</Button>
            )}
          </div>
          <Tabs
            type="card"
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
    {
      key: 'reimbursement',
      label: <span><FileTextOutlined /> {t('l2Branch.reimbursementTab')}</span>,
      children: (
        <>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
            <Button icon={<DownloadOutlined />} onClick={() => {
              const batch = batches.find(b => b.id === selectedBatchId);
              const data = [...reimbursementData, { key: reimbursementData.length, l1_branch: '', alloc_dept: t('l2Branch.reimbursementTotal'), cost_center: '', fee_subtotal: reimbursementTotal }];
              exportCSV(
                `报销单_${selectedL1Branch || ''}_${batch?.billing_month || ''}`,
                [
                  { title: t('l1Summary.branchCol'), dataIndex: 'l1_branch' },
                  { title: t('l1Detail.orgCol'), dataIndex: 'alloc_dept' },
                  { title: t('l1Summary.costCenterCol'), dataIndex: 'cost_center' },
                  { title: t('l2Branch.reimbursementFeeSubtotal'), dataIndex: 'fee_subtotal', render: (v: number) => v.toFixed(2) },
                ],
                data,
              );
            }}>{t('l2Branch.exportReimbursement')}</Button>
          </div>
          <Table
          columns={reimbursementColumns}
          dataSource={reimbursementData}
          rowKey="key"
          size="small"
          pagination={false}
          summary={() => (
            <Table.Summary.Row>
              <Table.Summary.Cell index={0} />
              <Table.Summary.Cell index={1}><strong>{t('l2Branch.reimbursementTotal')}</strong></Table.Summary.Cell>
              <Table.Summary.Cell index={2} />
              <Table.Summary.Cell index={3} align="right"><strong>¥{reimbursementTotal.toFixed(2)}</strong></Table.Summary.Cell>
            </Table.Summary.Row>
          )}
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
            <Space>
              <span>{t('l2Branch.selectMonth')}</span>
              <Select style={{ width: 220 }} placeholder={t('l2Branch.selectMonthPlaceholder')} loading={loading} value={selectedBatchId} onChange={setSelectedBatchId}
                options={[...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month)).map(b => ({ label: `${b.billing_month}`, value: b.id }))} />
              <span>{t('l2Branch.selectBranch')}</span>
              <Select style={{ width: 180 }} placeholder={t('l2Branch.selectBranchPlaceholder')} value={selectedL1Branch} onChange={setSelectedL1Branch}
                options={l1Branches.map(b => ({ label: b, value: b }))} />
            </Space>
          </Col>
        </Row>

        {selectedBatchId && selectedL1Branch && (
          <Tabs
            type="card"
            onChange={(key) => { if (key === 'detail' && !detailLoaded) fetchAllDetails(); }}
            items={mainTabs}
          />
        )}
      </Card>
    </div>
  );
}
