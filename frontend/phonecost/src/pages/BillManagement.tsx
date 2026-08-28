import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Button, Space, Modal, message, Select, Dropdown, Row, Col, Progress, Popconfirm, DatePicker, Tabs, Input } from 'antd';
import type { ColumnType } from 'antd/es/table';
import { UploadOutlined, DeleteOutlined, DownloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useAuthStore } from '../store/auth';
import type { BillBatch, BillDetail, SheetTypeCode } from '../types/bill';
import { SHEET_TYPES } from '../types/bill';
import type { ImportProgress } from '../types/import';
import {
  getBillBatches,
  importBill,
  downloadBillTemplate,
  getBillProgress,
  deleteBillBatch,
  getBillMonths,
  getBillDetails,
  updateBillBatchMonth,
} from '../api/import';
import {
  calculateAllocation,
  confirmAllocation,
  confirmAllAllocation,
  withdrawAllocation,
  exportSummary,
  exportDetail,
  getAllocationSnapshot,
  getAllocationResults,
} from '../api/allocation';
import type { AllocationResult, OwnershipBatch, DirectoryBatch } from '../types/allocation';
import { useImportProgress } from '../hooks/useImportProgress';
import { useTranslation } from 'react-i18next';
import { getErrorMessage } from '../types/api';
import dayjs from 'dayjs';

export default function BillManagement() {
  const { t } = useTranslation();
  const isAdmin = useAuthStore((s) => s.role === 1);

  // ==================== Batch list state ====================
  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBatch, setSelectedBatch] = useState<BillBatch | null>(null);
  const [batchPageSize, setBatchPageSize] = useState(6);

  // Month filter
  const [availableMonths, setAvailableMonths] = useState<string[]>([]);
  const [selectedMonth, setSelectedMonth] = useState<string | undefined>(undefined);

  // Import
  const [uploading, setUploading] = useState(false);
  const [importMonthModal, setImportMonthModal] = useState<{ open: boolean; file: File | null }>({ open: false, file: null });
  const [importBillingMonth, setImportBillingMonth] = useState<string>(dayjs().format('YYYY-MM'));

  // Async import progress
  const { progress: importProgress, polling: importPolling, startPolling, percent: importPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(t('bill.importComplete', { count: p.total }));
      fetchBatches();
      fetchMonths();
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(t('bill.importFailedMsg', { error: p.message || t('common.unknown') }));
      setUploading(false);
    },
  });

  // Edit month
  const [editingMonthId, setEditingMonthId] = useState<number | null>(null);
  const [editingMonthValue, setEditingMonthValue] = useState<string>('');

  // ==================== Bill details state ====================
  const [activeSheetType, setActiveSheetType] = useState<SheetTypeCode>('CALL');
  const [details, setDetails] = useState<BillDetail[]>([]);
  const [detailsTotal, setDetailsTotal] = useState(0);
  const [detailsPage, setDetailsPage] = useState(0);
  const [detailsPageSize, setDetailsPageSize] = useState(20);
  const [detailsLoading, setDetailsLoading] = useState(false);

  // ==================== Allocation state (preserved for calculate/confirm/withdraw) ====================
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [calculatingId, setCalculatingId] = useState<number | null>(null);
  const [withdrawModal, setWithdrawModal] = useState<{ open: boolean; result?: AllocationResult }>({ open: false });
  const [withdrawReason, setWithdrawReason] = useState('');



  // Bill detail search
  const [detailSearch, setDetailSearch] = useState('');

  // Calculate snapshot modal
  const [calcModal, setCalcModal] = useState<{ open: boolean; batchId: number | null }>({ open: false, batchId: null });
  const [ownershipBatches, setOwnershipBatches] = useState<OwnershipBatch[]>([]);
  const [directoryBatches, setDirectoryBatches] = useState<DirectoryBatch[]>([]);
  const [selectedOwnershipBatchId, setSelectedOwnershipBatchId] = useState<number | null>(null);
  const [selectedDirectoryBatchId, setSelectedDirectoryBatchId] = useState<number | null>(null);
  const [snapshotLoading, setSnapshotLoading] = useState(false);

  // ==================== Data fetching ====================

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getBillBatches(selectedMonth);
      setBatches(data);
      // Auto-select the most recent batch if none selected
      if (!selectedBatch && data.length > 0) {
        setSelectedBatch(data[data.length - 1]);
      }
    } catch {
      message.error(t('bill.fetchBatchesFailed'));
    } finally {
      setLoading(false);
    }
  }, [t, selectedMonth]);

  const fetchMonths = useCallback(async () => {
    try {
      const months = await getBillMonths();
      setAvailableMonths(months);
    } catch { /* silent */ }
  }, []);

  useEffect(() => { fetchBatches(); }, [fetchBatches]);
  useEffect(() => { fetchMonths(); }, [fetchMonths]);

  // Fetch bill details for the selected batch + sheet type
  const fetchDetails = useCallback(async (batchId: number, sheetType: SheetTypeCode, page = 0, size = 20, keyword?: string) => {
    setDetailsLoading(true);
    try {
      const data = await getBillDetails(batchId, sheetType, page, size, keyword);
      setDetails(data.entries);
      setDetailsTotal(data.total);
      setDetailsPage(data.page);
      setDetailsPageSize(data.size);
    } catch {
      message.error(t('bill.fetchDetailsFailed'));
    } finally {
      setDetailsLoading(false);
    }
  }, [t]);

  // Fetch allocation results
  const fetchResults = useCallback(async (batchId: number) => {
    try {
      const data = await getAllocationResults(batchId);
      setResults(data.content);
    } catch {
      message.error(t('bill.fetchResultsFailed'));
    }
  }, [t]);

  // When a batch is selected, load bill details & allocation results
  useEffect(() => {
    if (selectedBatch) {
      fetchDetails(selectedBatch.id, activeSheetType);
      fetchResults(selectedBatch.id);
    } else {
      setDetails([]);
      setDetailsTotal(0);
      setResults([]);
    }
  }, [selectedBatch, activeSheetType, fetchDetails, fetchResults]);

  // Debounced server-side search: when detailSearch changes, re-fetch from backend
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (!selectedBatch) return;
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    searchTimerRef.current = setTimeout(() => {
      fetchDetails(selectedBatch.id, activeSheetType, 0, detailsPageSize, detailSearch.trim() || undefined);
    }, 400);
    return () => { if (searchTimerRef.current) clearTimeout(searchTimerRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detailSearch]);

  // ==================== Handlers ====================

  const handleBillFileSelected = (file: File) => {
    setImportMonthModal({ open: true, file });
  };

  const handleConfirmImport = async () => {
    if (!importMonthModal.file || !importBillingMonth) {
      message.warning(t('bill.monthSelectRequired'));
      return;
    }
    const file = importMonthModal.file;
    const month = importBillingMonth;
    setImportMonthModal({ open: false, file: null });
    setUploading(true);
    try {
      const result = await importBill(file, month);
      startPolling(result.batch_id, getBillProgress);
    } catch (err) {
      message.error(t('bill.importFailedMsg', { error: err instanceof Error ? err.message : t('common.unknown') }));
      setUploading(false);
    }
  };

  const handleDeleteBatch = async (batchId: number) => {
    try {
      await deleteBillBatch(batchId);
      message.success(t('bill.deleteSuccess'));
      if (selectedBatch?.id === batchId) {
        setSelectedBatch(null);
        setDetails([]);
        setResults([]);
      }
      fetchBatches();
      fetchMonths();
    } catch (err) {
      message.error(t('bill.deleteFailed', { error: err instanceof Error ? err.message : '' }));
    }
  };

  const handleUpdateMonth = async (batchId: number, newMonth: string) => {
    try {
      await updateBillBatchMonth(batchId, newMonth);
      message.success(t('bill.monthUpdateSuccess'));
      setEditingMonthId(null);
      fetchBatches();
      fetchMonths();
    } catch (err) {
      message.error(getErrorMessage(err, t('bill.monthUpdateFailed')));
    }
  };

  const handleCalculate = async (batchId: number) => {
    setCalcModal({ open: true, batchId });
    setSnapshotLoading(true);
    setSelectedOwnershipBatchId(null);
    setSelectedDirectoryBatchId(null);
    try {
      const snap = await getAllocationSnapshot(batchId);
      setOwnershipBatches(snap.ownership_batches || []);
      setDirectoryBatches(snap.directory_batches || []);

      // Find the bill batch's billing month for auto-matching
      const billBatch = batches.find(b => b.id === batchId);
      const billMonth = billBatch?.billing_month;

      // Auto-select ownership batch: prefer same month, fallback to snapshot, then latest
      if (snap.ownership_batch_id) setSelectedOwnershipBatchId(snap.ownership_batch_id);
      else if (snap.ownership_batches?.length > 0) {
        const monthMatch = billMonth
          ? snap.ownership_batches.find((b: OwnershipBatch) => b.billing_month === billMonth)
          : null;
        const best = monthMatch || [...snap.ownership_batches].sort((a, b) => b.id - a.id)[0];
        setSelectedOwnershipBatchId(best.id);
      }

      // Auto-select directory batch: prefer same month, fallback to snapshot, then latest
      if (snap.directory_batch_id) setSelectedDirectoryBatchId(snap.directory_batch_id);
      else if (snap.directory_batches?.length > 0) {
        const monthMatch = billMonth
          ? snap.directory_batches.find((b: DirectoryBatch) => b.billing_month === billMonth)
          : null;
        const fallback = monthMatch
          ? monthMatch
          : (() => {
              const withMonth = snap.directory_batches.filter((b: DirectoryBatch) => b.billing_month);
              const source = withMonth.length > 0 ? withMonth : snap.directory_batches;
              return [...source].sort((a, b) => b.id - a.id)[0];
            })();
        setSelectedDirectoryBatchId(fallback.id);
      }
    } catch {
      message.error(t('bill.snapshotFetchFailed'));
    } finally {
      setSnapshotLoading(false);
    }
  };

  const handleConfirmCalc = async () => {
    if (!calcModal.batchId) return;
    setCalculatingId(calcModal.batchId);
    try {
      const res = await calculateAllocation(calcModal.batchId, selectedOwnershipBatchId, selectedDirectoryBatchId);
      message.success(t('bill.calcResultMsg', { orgCount: res.org_count, matchedCount: res.matched_count }));
      const updatedBatches = await getBillBatches(selectedMonth);
      setBatches(updatedBatches);
      const updated = updatedBatches.find(b => b.id === calcModal.batchId);
      if (updated) setSelectedBatch(updated);
      fetchResults(calcModal.batchId);
      setCalcModal({ open: false, batchId: null });
    } catch (err) {
      message.error(getErrorMessage(err, t('bill.calcFailedMsg')));
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

  const handleExportSummary = () => {
    if (!selectedBatch) return;
    exportSummary(selectedBatch.id).catch(() => message.error(t('bill.exportFailed')));
  };
  const handleExportDetail = () => {
    if (!selectedBatch) return;
    exportDetail(selectedBatch.id).catch(() => message.error(t('bill.exportFailed')));
  };

  // ==================== Batch list columns (4 columns only) ====================

  const batchColumns = [
    {
      title: t('bill.month'), dataIndex: 'billing_month', key: 'billing_month', width: 140,
      render: (month: string, record: BillBatch) => {
        if (editingMonthId === record.id) {
          return (
            <Space size={4}>
              <DatePicker
                picker="month"
                size="small"
                value={dayjs(editingMonthValue, 'YYYY-MM')}
                onChange={(_, dateString) => setEditingMonthValue(dateString as string)}
                onPressEnter={() => editingMonthValue && handleUpdateMonth(record.id, editingMonthValue)}
                allowClear={false}
                style={{ width: 110 }}
              />
              <Button size="small" type="link" onClick={() => editingMonthValue && handleUpdateMonth(record.id, editingMonthValue)}>{t('common.confirm')}</Button>
              <Button size="small" type="link" onClick={() => setEditingMonthId(null)}>{t('common.cancel')}</Button>
            </Space>
          );
        }
        return (
          <span style={{ fontWeight: selectedBatch?.id === record.id ? 600 : 400 }}>
            {month === 'unknown' ? t('bill.monthNotSet') : month}
          </span>
        );
      },
    },
    {
      title: t('bill.totalAmountCol'), dataIndex: 'total_amount', key: 'total_amount', width: 130,
      render: (v: unknown, record: BillBatch) => (
        <span style={{ fontWeight: 500 }}>
          {v != null ? `¥${Number(v).toFixed(2)}` : '-'}
          <span style={{ color: COLORS.textMuted, fontSize: 12, marginLeft: 6 }}>
            ({record.total_count}{t('bill.countUnit')})
          </span>
        </span>
      ),
    },
    {
      title: t('bill.importTime'), dataIndex: 'created_at', key: 'created_at', width: 130,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
    },
    {
      title: t('common.delete'), key: 'delete', width: 70,
      render: (_unused: unknown, record: BillBatch) => isAdmin ? (
        <Popconfirm
          title={t('bill.deleteConfirm')}
          description={t('bill.deleteConfirmDesc', { month: record.billing_month })}
          onConfirm={(e) => { e?.stopPropagation(); handleDeleteBatch(record.id); }}
          onCancel={(e) => e?.stopPropagation()}
          okText={t('common.confirm')}
          cancelText={t('common.cancel')}
          okButtonProps={{ danger: true }}
        >
          <Button size="small" danger type="text" icon={<DeleteOutlined />}
            onClick={(e) => e.stopPropagation()} />
        </Popconfirm>
      ) : null,
    },
  ];

  // ==================== Detail table columns per sheet type (raw/original bill data) ====================

  /** Parse rawData JSON from BillDetail, fallback to empty object.
   *  Also normalizes col_N keys to proper field names for old data
   *  where template was missing duration/remark column mappings. */
  const parseRaw = (raw: string | null): Record<string, unknown> => {
    if (!raw) return {};
    let obj: Record<string, unknown>;
    try { obj = JSON.parse(raw); } catch { return {}; }
    // Compatibility: old data stored duration fields as col_3/col_4/col_6 and remark as col_9
    const fallbacks: Record<string, string> = {
      domesticDuration: 'col_3',
      transferDuration: 'col_4',
      internationalDuration: 'col_6',
      remark: 'col_9',
      recordingDir: 'col_2',
    };
    for (const [field, colKey] of Object.entries(fallbacks)) {
      if (obj[field] == null && obj[colKey] != null) {
        obj[field] = obj[colKey];
      }
    }
    return obj;
  };

  /** Format a fee value from rawData */
  const fmtFee = (v: unknown) => {
    if (v == null || v === '') return '-';
    const n = Number(v);
    if (isNaN(n)) return '-';
    // Show '-' when numeric value is 0 (including string "0"/"0.00")
    return n === 0 ? '-' : `¥${n.toFixed(2)}`;
  };

  /** Format a duration value from rawData (domestic/transfer/international) */
  const fmtDur = (v: unknown) => {
    if (v == null || v === '') return '-';
    const n = Number(v);
    return isNaN(n) ? '-' : n.toString();
  };

  /** Filter Excel empty-date artifacts (e.g. 1904/1/1, 1900/1/0) rendered as blank */
  const isInvalidExcelDate = (s: string) => /^1(?:900|904)\/\d{1,2}\/\d{1,2}$/.test(s.trim());

  const callColumns = [
    { title: t('bill.raw.phoneNumber'), key: 'phoneNumber', width: 130, render: (_: unknown, r: BillDetail) => parseRaw(r.raw_data).phoneNumber ?? r.phone_number },
    { title: t('bill.raw.platformFee'), key: 'platformFee', width: 100, render: (_: unknown, r: BillDetail) => fmtFee(parseRaw(r.raw_data).platformFee) },
    { title: t('bill.raw.monthlyRentCode'), key: 'monthlyRentCode', width: 110, render: (_: unknown, r: BillDetail) => fmtFee(parseRaw(r.raw_data).monthlyRentCode) },
    { title: t('bill.raw.domesticDuration'), key: 'domesticDuration', width: 120, render: (_: unknown, r: BillDetail) => fmtDur(parseRaw(r.raw_data).domesticDuration) },
    { title: t('bill.raw.transferDuration'), key: 'transferDuration', width: 120, render: (_: unknown, r: BillDetail) => fmtDur(parseRaw(r.raw_data).transferDuration) },
    { title: t('bill.raw.domesticFee'), key: 'domesticFee', width: 100, render: (_: unknown, r: BillDetail) => fmtFee(parseRaw(r.raw_data).domesticFee) },
    { title: t('bill.raw.internationalDuration'), key: 'internationalDuration', width: 100, render: (_: unknown, r: BillDetail) => fmtDur(parseRaw(r.raw_data).internationalDuration) },
    { title: t('bill.raw.internationalFee'), key: 'internationalFee', width: 100, render: (_: unknown, r: BillDetail) => fmtFee(parseRaw(r.raw_data).internationalFee) },
    { title: t('bill.raw.totalFee'), key: 'totalFee', width: 110, render: (_: unknown, r: BillDetail) => <strong>{fmtFee(parseRaw(r.raw_data).totalFee)}</strong> },
    { title: t('bill.raw.remark'), key: 'remark', width: 120, render: (_: unknown, r: BillDetail) => (parseRaw(r.raw_data).remark as string) || '-' },
  ];

  const recordingColumns = [
    { title: t('bill.raw.extension'), key: 'extension', width: 100, render: (_: unknown, r: BillDetail) => parseRaw(r.raw_data).extension ?? r.extension },
    { title: t('bill.raw.phoneNumber'), key: 'phoneNumber', width: 130, render: (_: unknown, r: BillDetail) => parseRaw(r.raw_data).phoneNumber ?? r.phone_number },
    { title: t('bill.raw.recordingDir'), key: 'recordingDir', width: 120, render: (_: unknown, r: BillDetail) => {
      const v = parseRaw(r.raw_data).recordingDir as string;
      return (v && !isInvalidExcelDate(v)) ? v : '-';
    } },
    { title: t('bill.raw.recordingFee'), key: 'recordingFee', width: 110, render: (_: unknown, r: BillDetail) => <strong>{fmtFee(parseRaw(r.raw_data).recordingFee)}</strong> },
  ];

  const crbtColumns = [
    { title: t('bill.raw.extension'), key: 'extension', width: 100, render: (_: unknown, r: BillDetail) => parseRaw(r.raw_data).extension ?? r.extension },
    { title: t('bill.raw.phoneNumber'), key: 'phoneNumber', width: 130, render: (_: unknown, r: BillDetail) => parseRaw(r.raw_data).phoneNumber ?? r.phone_number },
    { title: t('bill.raw.crbtFee'), key: 'crbtFee', width: 110, render: (_: unknown, r: BillDetail) => <strong>{fmtFee(parseRaw(r.raw_data).crbtFee)}</strong> },
  ];

  const flashColumns = [
    { title: t('bill.raw.phoneNumber'), key: 'phoneNumber', width: 130, render: (_: unknown, r: BillDetail) => parseRaw(r.raw_data).phoneNumber ?? r.phone_number },
    { title: t('bill.raw.flashMonth'), key: 'flashMonth', width: 100, render: (_: unknown, r: BillDetail) => parseRaw(r.raw_data).flashMonth ?? r.flash_month },
    { title: t('bill.raw.flashCount'), key: 'flashCount', width: 100, render: (_: unknown, r: BillDetail) => fmtDur(parseRaw(r.raw_data).flashCount) },
    { title: t('bill.raw.flashMsgFee'), key: 'flashMsgFee', width: 110, render: (_: unknown, r: BillDetail) => <strong>{fmtFee(parseRaw(r.raw_data).flashMsgFee)}</strong> },
  ];

  const detailColumnMap: Record<SheetTypeCode, ReturnType<typeof Object>> = {
    CALL: callColumns,
    RECORDING: recordingColumns,
    CRBT: crbtColumns,
    FLASH_MSG: flashColumns,
  };

  // ==================== Render ====================

  return (
    <div>
      {/* Top toolbar: month filter + import button */}
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Select
            style={{ width: 150 }}
            placeholder={t('bill.filterByMonth')}
            allowClear
            value={selectedMonth}
            onChange={(v) => setSelectedMonth(v)}
            options={availableMonths.map(m => ({ label: m, value: m }))}
          />
        </Col>
        <Col flex="auto" />
        <Col>
          <Dropdown menu={{ items: [
            { key: 'import', icon: <UploadOutlined />, label: t('import.billTab'), disabled: uploading },
            { key: 'template', icon: <DownloadOutlined />, label: t('import.downloadTemplate') },
          ], onClick: ({ key }) => {
            if (key === 'import') document.getElementById('bill-upload-input')?.click();
            if (key === 'template') downloadBillTemplate();
          } }}>
            <Button type="primary" icon={<UploadOutlined />} loading={uploading && !importPolling}>
              {t('import.billTab')}
            </Button>
          </Dropdown>
          <input type="file" accept=".xlsx,.xls" id="bill-upload-input" style={{ display: 'none' }}
            onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleBillFileSelected(f); e.target.value = ''; } }} />
          {importPolling && importProgress && (
            <Progress
              percent={importPercent}
              size="small"
              style={{ width: 200, marginLeft: 12, display: 'inline-block', verticalAlign: 'middle' }}
              format={() => importProgress.sheet_info
                ? `${importProgress.sheet_info} ${importProgress.processed}/${importProgress.total}`
                : `${importProgress.processed}/${importProgress.total}`}
            />
          )}
        </Col>
      </Row>

      {/* Batch list card — 4 columns */}
      <Card>
        <Table
          columns={batchColumns}
          dataSource={batches}
          rowKey="id"
          size="small"
          loading={loading}
          pagination={{
            pageSize: batchPageSize,
            showSizeChanger: true,
            pageSizeOptions: ['6', '10', '20'],
            showTotal: (total) => t('common.paginationTotal', { total }),
            onChange: (_p, s) => setBatchPageSize(s),
          }}
          onRow={(record) => ({
            onClick: () => {
              setSelectedBatch(record);
              setActiveSheetType('CALL');
            },
            style: {
              cursor: 'pointer',
              background: selectedBatch?.id === record.id ? 'rgba(139, 157, 158, 0.08)' : undefined,
            },
          })}
        />
      </Card>

      {/* Bill details + Allocation below selected batch */}
      {selectedBatch && (
        <Card
          style={{ marginTop: 16 }}
          title={selectedBatch.billing_month === 'unknown'
            ? t('bill.un_MONTH_results')
            : t('bill.monthResults', { month: selectedBatch.billing_month })}
          extra={
            <Space>
              <Button size="small" icon={undefined}
                onClick={() => handleCalculate(selectedBatch.id)}
                loading={calculatingId === selectedBatch.id}>
                {selectedBatch.status === 0 ? t('bill.calcAllocation') : t('bill.recalculate')}
              </Button>
              {results.length > 0 && (
                <>
                  <Button size="small" onClick={() => handleConfirmAll(selectedBatch.id)}>
                    {t('bill.confirmAll')}
                  </Button>
                  <Button size="small" icon={<DownloadOutlined />} onClick={handleExportSummary}>
                    {t('bill.exportSummaryTooltip')}
                  </Button>
                  <Button size="small" icon={<DownloadOutlined />} onClick={handleExportDetail}>
                    {t('bill.exportDetailTooltip')}
                  </Button>
                </>
              )}
            </Space>
          }
        >
          <Tabs
            tabBarExtraContent={
              <Input
                prefix={<SearchOutlined />}
                placeholder={t('bill.searchDetailPlaceholder')}
                allowClear
                size="small"
                style={{ width: 200 }}
                value={detailSearch}
                onChange={(e) => setDetailSearch(e.target.value)}
              />
            }
            activeKey={activeSheetType}
            onChange={(key) => setActiveSheetType(key as SheetTypeCode)}
            items={[
              ...SHEET_TYPES.map(st => ({
                key: st,
                label: t(`bill.sheetType_${st}`),
                children: (
                  <Table
                    columns={detailColumnMap[st] as ColumnType<unknown>[]}
                    dataSource={details}
                    rowKey="id"
                    size="small"
                    loading={detailsLoading}
                    pagination={{
                      current: detailsPage + 1,
                      pageSize: detailsPageSize,
                      total: detailsTotal,
                      showSizeChanger: true,
                      pageSizeOptions: ['20', '50', '100'],
                      showTotal: (total) => t('common.paginationTotal', { total }),
                      onChange: (p, s) => {
                        fetchDetails(selectedBatch.id, st, p - 1, s, detailSearch.trim() || undefined);
                      },
                    }}
                  />
                ),
              })),
            ]}
          />
        </Card>
      )}

      {/* Import month picker modal */}
      <Modal
        title={t('bill.selectBillingMonth')}
        open={importMonthModal.open}
        onOk={handleConfirmImport}
        onCancel={() => setImportMonthModal({ open: false, file: null })}
        okText={t('bill.confirmImport')}
        okButtonProps={{ disabled: !importBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('bill.selectMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          value={dayjs(importBillingMonth, 'YYYY-MM')}
          onChange={(_, dateString) => setImportBillingMonth(dateString as string)}
          allowClear={false}
        />
        {importMonthModal.file && (
          <p style={{ marginTop: 8, color: COLORS.textMuted, fontSize: 12 }}>
            {t('bill.importFile')}：{importMonthModal.file.name}
          </p>
        )}
      </Modal>

      {/* Withdraw modal */}
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

      {/* Calculate snapshot modal */}
      <Modal
        title={t('bill.selectSnapshotTitle')}
        open={calcModal.open}
        onOk={handleConfirmCalc}
        onCancel={() => setCalcModal({ open: false, batchId: null })}
        okText={t('bill.startCalc')}
        confirmLoading={calculatingId !== null}
        width={520}
      >
        <div style={{ marginBottom: 16 }}>
          <p style={{ color: COLORS.textMuted, marginBottom: 12 }}>{t('bill.snapshotHint')}</p>
          <div style={{ marginBottom: 12 }}>
            <div style={{ marginBottom: 4, fontWeight: 500 }}>{t('bill.ownershipBatchLabel')}</div>
            <Select
              style={{ width: '100%' }}
              placeholder={t('bill.ownershipBatchPlaceholder')}
              loading={snapshotLoading}
              value={selectedOwnershipBatchId}
              onChange={setSelectedOwnershipBatchId}
              options={ownershipBatches.sort((a, b) => b.id - a.id).map(b => ({
                label: `${b.batch_no} (${b.total_count}${t('import.recordCountSuffix', { count: '' })}${b.exception_count ? `, ${t('import.exceptionCount')}${b.exception_count}` : ''})`,
                value: b.id,
              }))}
            />
          </div>
          <div>
            <div style={{ marginBottom: 4, fontWeight: 500 }}>{t('bill.directoryBatchLabel')}</div>
            <Select
              style={{ width: '100%' }}
              placeholder={t('bill.directoryBatchPlaceholder')}
              loading={snapshotLoading}
              value={selectedDirectoryBatchId}
              onChange={setSelectedDirectoryBatchId}
              options={directoryBatches.sort((a, b) => b.id - a.id).map(b => ({
                label: `${b.batch_no}${b.billing_month ? ` [${b.billing_month}]` : ''} (${b.total_count}${t('import.recordCountSuffix', { count: '' })}${b.seconded_count ? `, ${t('import.secondedCount')}${b.seconded_count}` : ''})`,
                value: b.id,
              }))}
            />
          </div>
        </div>
      </Modal>
    </div>
  );
}
