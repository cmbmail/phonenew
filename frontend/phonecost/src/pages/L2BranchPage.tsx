import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Button, Descriptions, Row, Col, Tabs, message, Empty, Tag, Statistic, Input, Space } from 'antd';
import { DownloadOutlined, SearchOutlined, FileTextOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BillBatch } from '../types/bill';
import type { AllocationResult } from '../types/allocation';
import { CONFIRM_STATUS_MAP } from '../types/allocation';
import { getAllocationResults, getL2DetailData } from '../api/allocation';
import { getBillBatches } from '../api/import';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS } from '../types/organization';
import { exportCSV } from '../lib/export';

const SHEET_TYPES = ['CALL', 'RECORDING', 'CRBT', 'FLASH_MSG'] as const;
type SheetType = typeof SHEET_TYPES[number];

export default function L2BranchPage() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [selectedBranchId, setSelectedBranchId] = useState<number | null>(null);
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [resultsLoading, setResultsLoading] = useState(false);

  // 分摊明细数据
  const [detailData, setDetailData] = useState<Record<SheetType, Record<string, unknown>[]>>({
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

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { /* silent */ }
  }, []);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  // 自动选最近月份
  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  // 一级分行列表 (type=2)
  const branches = useMemo(() =>
    orgList.filter(o => o.type === 2).sort((a, b) => (a.code || '').localeCompare(b.code || '')),
    [orgList]);

  // 自动选第一个分行
  useEffect(() => {
    if (branches.length > 0 && !selectedBranchId) setSelectedBranchId(branches[0].id);
  }, [branches, selectedBranchId]);

  useEffect(() => {
    if (selectedBatchId) {
      setResultsLoading(true);
      getAllocationResults(selectedBatchId)
        .then(setResults)
        .catch(() => message.error(t('l2Branch.fetchFailed')))
        .finally(() => setResultsLoading(false));
    }
  }, [selectedBatchId, t]);

  // 切换分行时重置明细
  useEffect(() => {
    setDetailData({ CALL: [], RECORDING: [], CRBT: [], FLASH_MSG: [] });
    setDetailLoaded(false);
    setDetailSearch('');
  }, [selectedBranchId, selectedBatchId]);

  const orgMap = useMemo(() => {
    const m = new Map<number, Organization>();
    orgList.forEach(o => m.set(o.id, o));
    return m;
  }, [orgList]);

  const selectedBranch = orgMap.get(selectedBranchId || 0);

  // 该一级分行的直属子组织
  const directChildren = useMemo(() => {
    if (!selectedBranchId) return [];
    return orgList
      .filter(o => o.parent_id === selectedBranchId)
      .sort((a, b) => {
        const ta = a.type || 99, tb = b.type || 99;
        return ta !== tb ? ta - tb : a.name.localeCompare(b.name);
      });
  }, [selectedBranchId, orgList]);

  // 汇总数据
  const childSummary = useMemo(() => {
    return directChildren.map(child => {
      const childResults = results.filter(r => r.org_id === child.id);
      const monthlyRent = childResults.reduce((s, r) => s + (r.monthly_rent || 0), 0);
      const callFee = childResults.reduce((s, r) => s + (r.call_fee || 0), 0);
      const recordingFee = childResults.reduce((s, r) => s + (r.recording_fee || 0), 0);
      const crbtFee = childResults.reduce((s, r) => s + (r.crbt_fee || 0), 0);
      const flashFee = childResults.reduce((s, r) => s + (r.flash_msg_fee || 0), 0);
      const totalFee = childResults.reduce((s, r) => s + (r.total_fee || 0), 0);
      const phoneCount = childResults.reduce((s, r) => s + (r.phone_count || 0), 0);
      const confirmStatuses = childResults.map(r => r.confirm_status);
      const confirmStatus = confirmStatuses.length === 0 ? -1
        : confirmStatuses.every(s => s === 1) ? 1
        : confirmStatuses.every(s => s === 0) ? 0
        : confirmStatuses.some(s => s === 1) ? 1
        : 0;
      return { child, monthlyRent, callFee, recordingFee, crbtFee, flashFee, totalFee, phoneCount, confirmStatus };
    });
  }, [directChildren, results]);

  const branchMonthlyRent = childSummary.reduce((s, c) => s + c.monthlyRent, 0);
  const branchCallFee = childSummary.reduce((s, c) => s + c.callFee, 0);
  const branchRecordingFee = childSummary.reduce((s, c) => s + c.recordingFee, 0);
  const branchCrbtFee = childSummary.reduce((s, c) => s + c.crbtFee, 0);
  const branchFlashFee = childSummary.reduce((s, c) => s + c.flashFee, 0);
  const branchTotal = childSummary.reduce((s, c) => s + c.totalFee, 0);
  const branchPhones = childSummary.reduce((s, c) => s + c.phoneCount, 0);
  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  const money = (v: unknown) => {
    const n = Number(v);
    return !isNaN(n) && n !== 0 ? `¥${n.toFixed(2)}` : '-';
  };
  const dur = (v: unknown) => {
    const n = Number(v);
    return !isNaN(n) && n !== 0 ? n.toFixed(1) : '-';
  };
  const orgTypeLabel = (type: number) => ORG_TYPE_LABELS[type] || '-';

  // ========== 加载全部4种明细数据 ==========
  const fetchAllDetails = useCallback(async () => {
    if (!selectedBatchId || !selectedBranchId || detailLoaded) return;
    setDetailLoading(true);
    try {
      const results = await Promise.all(
        SHEET_TYPES.map(st => getL2DetailData(selectedBatchId, selectedBranchId, st).then(d => [st, d] as const))
      );
      const newData = { CALL: [], RECORDING: [], CRBT: [], FLASH_MSG: [] } as Record<SheetType, Record<string, unknown>[]>;
      for (const [st, d] of results) newData[st] = d;
      setDetailData(newData);
      setDetailLoaded(true);
    } catch {
      message.error(t('l2Branch.fetchFailed'));
    } finally {
      setDetailLoading(false);
    }
  }, [selectedBatchId, selectedBranchId, detailLoaded, t]);

  // ========== 分摊汇总 columns ==========
  const columns = [
    { title: t('l2Branch.seqCol'), key: 'seq', width: 50, render: (_: unknown, __: unknown, i: number) => i + 1 },
    { title: t('l2Branch.orgTypeCol'), key: 'orgType', width: 80, render: (_: unknown, r: typeof childSummary[0]) => orgTypeLabel(r.child.type) },
    { title: t('l2Branch.orgNameCol'), key: 'orgName', width: 140, render: (_: unknown, r: typeof childSummary[0]) => r.child.name },
    { title: t('l2Branch.costCenterCol'), key: 'costCenter', width: 90, render: (_: unknown, r: typeof childSummary[0]) => r.child.cost_center || '-' },
    { title: t('l2Branch.monthlyRentCodeCol'), key: 'monthlyRent', width: 100, dataIndex: 'monthlyRent', render: money },
    { title: t('l2Branch.domesticFeeCol'), key: 'callFee', width: 100, dataIndex: 'callFee', render: money },
    { title: t('l2Branch.recordingFeeCol'), key: 'recordingFee', width: 100, dataIndex: 'recordingFee', render: money },
    { title: t('l2Branch.crbtFeeCol'), key: 'crbtFee', width: 90, dataIndex: 'crbtFee', render: money },
    { title: t('l2Branch.flashFeeCol'), key: 'flashFee', width: 90, dataIndex: 'flashFee', render: money },
    { title: t('l2Branch.totalCol'), key: 'totalFee', width: 120, dataIndex: 'totalFee',
      render: (v: number) => <strong>{money(v)}</strong>,
    },
    { title: t('l2Branch.phoneCountCol'), key: 'phoneCount', width: 70, dataIndex: 'phoneCount' },
    { title: t('l2Branch.confirmStatusCol'), key: 'confirmStatus', width: 90,
      render: (_: unknown, r: typeof childSummary[0]) => {
        if (r.confirmStatus < 0) return '-';
        const info = CONFIRM_STATUS_MAP[r.confirmStatus] || { label: '-', color: 'default' };
        return <Tag color={info.color}>{info.label}</Tag>;
      },
    },
  ];

  // ========== 分摊明细 columns (same as L1) ==========
  const callColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120, fixed: 'left' as const },
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.orgCol'), dataIndex: 'org_name', key: 'org_name', width: 180 },
    { title: t('l1Detail.orgCodeCol'), dataIndex: 'cost_center', key: 'cost_center', width: 100 },
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
    { title: t('l1Detail.orgCol'), dataIndex: 'org_name', key: 'org_name', width: 200 },
    { title: t('l1Detail.orgCodeCol'), dataIndex: 'cost_center', key: 'cost_center', width: 100 },
    { title: t('l1Detail.recordingDirCol'), dataIndex: 'recording_dir', key: 'recording_dir', width: 200 },
    { title: t('l1Detail.recordingFeeCol'), dataIndex: 'recording_fee', key: 'recording_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  const crbtColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120 },
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.orgCol'), dataIndex: 'org_name', key: 'org_name', width: 200 },
    { title: t('l1Detail.orgCodeCol'), dataIndex: 'cost_center', key: 'cost_center', width: 100 },
    { title: t('l1Detail.crbtFeeCol'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  const flashColumns = [
    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 120 },
    { title: t('l1Detail.extensionCol'), dataIndex: 'extension', key: 'extension', width: 90 },
    { title: t('l1Detail.orgCol'), dataIndex: 'org_name', key: 'org_name', width: 200 },
    { title: t('l1Detail.orgCodeCol'), dataIndex: 'cost_center', key: 'cost_center', width: 100 },
    { title: t('l1Detail.flashMonthCol'), dataIndex: 'flash_month', key: 'flash_month', width: 90 },
    { title: t('l1Detail.flashCountCol'), dataIndex: 'flash_count', key: 'flash_count', width: 90, align: 'right' as const, render: (v: unknown) => { const n = Number(v); return !isNaN(n) && n !== 0 ? String(Math.round(n)) : '-'; } },
    { title: t('l1Detail.flashFeeCol'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, align: 'right' as const, render: money },
    { title: t('l1Detail.sourceCol'), dataIndex: 'ownership_source', key: 'ownership_source', width: 70 },
  ];

  // 搜索过滤
  const filteredDetailData = useMemo(() => {
    const kw = detailSearch.trim().toLowerCase();
    if (!kw) return detailData;
    const filter = (rows: Record<string, unknown>[]) =>
      rows.filter(r =>
        String(r.phone_number || '').toLowerCase().includes(kw) ||
        String(r.extension || '').toLowerCase().includes(kw) ||
        String(r.org_name || '').toLowerCase().includes(kw) ||
        String(r.cost_center || '').toLowerCase().includes(kw) ||
        String(r.org_code || '').toLowerCase().includes(kw)
      );
    return {
      CALL: filter(detailData.CALL),
      RECORDING: filter(detailData.RECORDING),
      CRBT: filter(detailData["CRBT"]),
      FLASH_MSG: filter(detailData.FLASH_MSG),
    } as Record<SheetType, Record<string, unknown>[]>;
  }, [detailData, detailSearch]);

  // 统计卡片
  const detailStats = useMemo(() => {
    const sum = (data: Record<string, unknown>[], field: string) =>
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
        rowKey="id"
        size="small"
        loading={detailLoading}
        pagination={{ pageSize: detailPageSize, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], onShowSizeChange: (_current, size) => setDetailPageSize(size), showTotal: (total) => t('common.paginationTotal', { total }) }}
        scroll={{ x: scrollX }}
      />
    );
  };

  // ========== 报销单数据 ==========
  const reimbursementData = useMemo(() => {
    return childSummary
      .filter(c => c.child.cost_center && c.child.type !== 2 && c.child.type !== 3)
      .map((c, i) => ({ key: i, cost_center: c.child.cost_center!, fee_subtotal: c.totalFee }))
      .sort((a, b) => a.cost_center.localeCompare(b.cost_center));
  }, [childSummary]);

  const reimbursementTotal = reimbursementData.reduce((s, r) => s + r.fee_subtotal, 0);

  const reimbursementColumns = [
    { title: t('l2Branch.reimbursementCostCenter'), dataIndex: 'cost_center', key: 'cost_center', width: 200 },
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
          {selectedBatchId && selectedBranchId && childSummary.length > 0 && (
            <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
              <Descriptions.Item label={t('l2Branch.descMonth')}>{selectedBatch?.billing_month}</Descriptions.Item>
              <Descriptions.Item label={t('l2Branch.descBranch')}>{selectedBranch?.name}</Descriptions.Item>
              <Descriptions.Item label={t('l2Branch.descChildCount')}>{directChildren.length}</Descriptions.Item>
              <Descriptions.Item label={t('l2Branch.descTotalFee')}>¥{branchTotal.toFixed(2)}</Descriptions.Item>
            </Descriptions>
          )}
          {selectedBatchId && selectedBranchId && childSummary.length > 0 && (
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
              <Button icon={<DownloadOutlined />} onClick={() => {
                const batch = batches.find(b => b.id === selectedBatchId);
                const branch = selectedBranch?.name || '';
                exportCSV(
                  `分摊汇总_${branch}_${batch?.billing_month || ''}`,
                  [
                    { title: t('l2Branch.seqCol'), dataIndex: 'seq', render: (_: unknown, __: unknown, i: number) => i + 1 },
                    { title: t('l2Branch.orgTypeCol'), dataIndex: 'orgType', render: (_: unknown, r: typeof childSummary[0]) => orgTypeLabel(r.child.type) },
                    { title: t('l2Branch.orgNameCol'), dataIndex: 'orgName', render: (_: unknown, r: typeof childSummary[0]) => r.child.name },
                    { title: t('l2Branch.costCenterCol'), dataIndex: 'costCenter', render: (_: unknown, r: typeof childSummary[0]) => r.child.cost_center || '-' },
                    { title: t('l2Branch.monthlyRentCodeCol'), dataIndex: 'monthlyRent', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l2Branch.domesticFeeCol'), dataIndex: 'callFee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l2Branch.recordingFeeCol'), dataIndex: 'recordingFee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l2Branch.crbtFeeCol'), dataIndex: 'crbtFee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l2Branch.flashFeeCol'), dataIndex: 'flashFee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                    { title: t('l2Branch.totalCol'), dataIndex: 'totalFee', render: (v: number) => v != null ? v.toFixed(2) : '' },
                    { title: t('l2Branch.phoneCountCol'), dataIndex: 'phoneCount' },
                    { title: t('l2Branch.confirmStatusCol'), dataIndex: 'confirmStatus', render: (v: number) => v >= 0 ? (CONFIRM_STATUS_MAP[v]?.label || '') : '' },
                  ],
                  childSummary as unknown as Record<string, unknown>[],
                );
              }}>{t('l2Branch.exportSummary')}</Button>
            </div>
          )}
          {selectedBatchId && selectedBranchId && childSummary.length > 0 ? (
            <Table
              columns={columns}
              dataSource={childSummary}
              rowKey={r => r.child.id}
              size="small"
              loading={resultsLoading}
              pagination={false}
              summary={() => (
                <Table.Summary.Row>
                  <Table.Summary.Cell index={0} colSpan={4}><strong>{t('l2Branch.totalRow')}</strong></Table.Summary.Cell>
                  <Table.Summary.Cell index={4} align="right">{money(branchMonthlyRent)}</Table.Summary.Cell>
                  <Table.Summary.Cell index={5} align="right">{money(branchCallFee)}</Table.Summary.Cell>
                  <Table.Summary.Cell index={6} align="right">{money(branchRecordingFee)}</Table.Summary.Cell>
                  <Table.Summary.Cell index={7} align="right">{money(branchCrbtFee)}</Table.Summary.Cell>
                  <Table.Summary.Cell index={8} align="right">{money(branchFlashFee)}</Table.Summary.Cell>
                  <Table.Summary.Cell index={9}><strong>¥{branchTotal.toFixed(2)}</strong></Table.Summary.Cell>
                  <Table.Summary.Cell index={10}><strong>{branchPhones}</strong></Table.Summary.Cell>
                  <Table.Summary.Cell index={11} />
                </Table.Summary.Row>
              )}
            />
          ) : (
            !resultsLoading && <Empty description={t('l2Branch.noData')} />
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
                const branch = selectedBranch?.name || '';
                const allRows: Record<string, unknown>[] = [];
                const sheetLabels: Record<string, string> = { CALL: t('l1Detail.callTab'), RECORDING: t('l1Detail.recordingTab'), CRBT: t('l1Detail.crbtTab'), FLASH_MSG: t('l1Detail.flashTab') };
                for (const st of SHEET_TYPES) {
                  for (const row of detailData[st]) {
                    allRows.push({ ...row, _sheet_type: sheetLabels[st] });
                  }
                }
                exportCSV(
                  `分摊明细_${branch}_${batch?.billing_month || ''}`,
                  [
                    { title: t('l2Branch.detailSheetType'), dataIndex: '_sheet_type' },
                    { title: t('l1Detail.phoneCol'), dataIndex: 'phone_number' },
                    { title: t('l1Detail.extensionCol'), dataIndex: 'extension' },
                    { title: t('l1Detail.orgCol'), dataIndex: 'org_name' },
                    { title: t('l1Detail.orgCodeCol'), dataIndex: 'cost_center' },
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
              const branch = selectedBranch?.name || '';
              const data = [...reimbursementData, { key: reimbursementData.length, cost_center: t('l2Branch.reimbursementTotal'), fee_subtotal: reimbursementTotal }];
              exportCSV(
                `报销单_${branch}_${batch?.billing_month || ''}`,
                [
                  { title: t('l2Branch.reimbursementCostCenter'), dataIndex: 'cost_center' },
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
              <Table.Summary.Cell index={0}><strong>{t('l2Branch.reimbursementTotal')}</strong></Table.Summary.Cell>
              <Table.Summary.Cell index={1} align="right"><strong>¥{reimbursementTotal.toFixed(2)}</strong></Table.Summary.Cell>
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
              <Select style={{ width: 180 }} placeholder={t('l2Branch.selectBranchPlaceholder')} value={selectedBranchId} onChange={setSelectedBranchId}
                options={branches.map(b => ({ label: b.name, value: b.id }))} />
            </Space>
          </Col>
        </Row>

        {selectedBatchId && selectedBranchId && (
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
