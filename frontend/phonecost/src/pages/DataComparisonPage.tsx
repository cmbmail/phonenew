import React, { useState, useCallback, useRef, useEffect } from 'react';
import {   Tabs, Select, Button, Table, Card, Row, Col, Statistic, Tag, Space, Input, message, Modal, Checkbox, Progress, DatePicker, Dropdown, Popconfirm } from 'antd';
import {
  DownloadOutlined, DiffOutlined,
  UploadOutlined, HistoryOutlined, DownOutlined, DeleteOutlined, SendOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import {
  getDirectoryMonths,
  getExceptionMonths,
  compareDirectory,
  compareExceptionEntries,
  exportDirectoryByMonth,
  exportDirectoryComparison,
  exportExceptionCompare,
  exportExceptionByMonth,
  importExceptionEntries,
  importDirectory,
  downloadDirectoryTemplate,
  downloadDirectoryExceptionTemplate,
  getDirectoryProgress,
  getDirectoryBatches,
  getDirectoryEntriesByBatch,
  deleteDirectoryBatch,
  deleteDirectoryBatchesByMonth,
  pushComparisonToAllocationOrg,
  getLatestComparisonArchive,
} from '../api/import';
import { useQuery } from '@tanstack/react-query';
import { useImportProgress } from '../hooks/useImportProgress';

const TYPE_COLORS: Record<string, string> = {
  added: 'green',
  removed: 'red',
  changed: 'orange',
};

const EXC_PREFIX = 'EXC-';

const DataComparisonPage: React.FC = () => {
  const { t } = useTranslation();

  // ==================== Shared months ====================
  const { data: months = [] } = useQuery({
    queryKey: ['directoryMonths'],
    queryFn: () => getDirectoryMonths(),
  });

  // Exception months (EXC- batches only) — used by compare modal exception dropdown
  const { data: excMonths = [] } = useQuery({
    queryKey: ['exceptionMonths'],
    queryFn: () => getExceptionMonths(),
  });

  // ==================== Current Data Tab: month + batch ====================
  const [curBatchMonth, setCurBatchMonth] = useState('');
  const [curBatches, setCurBatches] = useState<Array<Record<string, any>>>([]);
  const [curBatchesLoading, setCurBatchesLoading] = useState(false);
  const [curSelectedBatchId, setCurSelectedBatchId] = useState<number | null>(null);
  const [curBatchEntries, setCurBatchEntries] = useState<Array<Record<string, any>>>([]);
  const [curBatchTotal, setCurBatchTotal] = useState(0);
  const [curBatchPage, setCurBatchPage] = useState(0);
  const [curBatchPageSize, setCurBatchPageSize] = useState(50);
  const [curBatchSearch, setCurBatchSearch] = useState('');
  const [curBatchLoading, setCurBatchLoading] = useState(false);
  const curBatchMonthRef = useRef('');
  curBatchMonthRef.current = curBatchMonth;

  const fetchCurBatches = useCallback(async (month?: string) => {
    const m = month ?? curBatchMonthRef.current;
    setCurBatchesLoading(true);
    try {
      const data = await getDirectoryBatches(m || undefined);
      setCurBatches((data || []).filter((b) => !(b.batch_no || '').startsWith(EXC_PREFIX)));
    } catch {
      message.error(t('dataComparison.fetchBatchesFailed'));
    } finally {
      setCurBatchesLoading(false);
    }
  }, [t]);

  const fetchCurBatchEntries = useCallback(async (search?: string, page?: number, size?: number, forceBatchId?: number) => {
    const id = forceBatchId ?? curSelectedBatchId;
    if (id == null) return;
    setCurBatchLoading(true);
    try {
      const res = await getDirectoryEntriesByBatch(id, search ?? curBatchSearch, page ?? curBatchPage, size ?? curBatchPageSize);
      setCurBatchEntries(res.entries || []);
      setCurBatchTotal(res.total || 0);
    } catch {
      message.error(t('dataComparison.fetchCurrentFailed'));
    } finally {
      setCurBatchLoading(false);
    }
  }, [curSelectedBatchId, curBatchSearch, curBatchPage, curBatchPageSize, t]);

  const selectCurBatch = useCallback((id: number) => {
    setCurSelectedBatchId(id);
    setCurBatchPage(0);
    setCurBatchSearch('');
    fetchCurBatchEntries('', 0, curBatchPageSize, id);
  }, [fetchCurBatchEntries, curBatchPageSize]);

  // ==================== Month Comparison (results shown in Diff Tab) ====================
  const [activeKey, setActiveKey] = useState('current');
  const [compareModalOpen, setCompareModalOpen] = useState(false);
  const [compareCheckMonth, setCompareCheckMonth] = useState('');
  const [compareRefMonth, setCompareRefMonth] = useState('');
  const [compareExcMonth, setCompareExcMonth] = useState('');
  const [compareLoading, setCompareLoading] = useState(false);
  const [excDiffVisible, setExcDiffVisible] = useState(false);

  // ==================== Diff Tab state (declared early to avoid TDZ) ====================
  const [archiveResult, setArchiveResult] = useState<any>(null);
  const [archiveLoading, setArchiveLoading] = useState(false);
  const [archivePage, setArchivePage] = useState(0);
  const [archivePageSize, setArchivePageSize] = useState(50);
  const [archiveSearch, setArchiveSearch] = useState('');
  const [archiveType, setArchiveType] = useState<string>('');
  const [excDiffSearch, setExcDiffSearch] = useState('');

  const handleMonthCompare = useCallback(async () => {
    if (!compareCheckMonth || !compareRefMonth) {
      message.warning(t('dataComparison.selectCompareMonths'));
      return;
    }
    if (compareCheckMonth === compareRefMonth) {
      message.warning(t('dataComparison.sameMonthWarning'));
      return;
    }
    setCompareLoading(true);
    setArchiveLoading(true);
    try {
      // month1=参考数据(reference), month2=待核对数据(to check)
      // added = in month2 not in month1 = 待核对新增
      // removed = in month1 not in month2 = 待核对缺失
      const data = await compareDirectory(compareRefMonth, compareCheckMonth, 0, archivePageSize, archiveSearch || undefined, archiveType || undefined);
      setArchiveResult({ ...data, type: 'month', month1: compareRefMonth, month2: compareCheckMonth, excMonth: compareExcMonth || undefined });
      setArchivePage(0);
      setCompareModalOpen(false);

      // Exception diff: only if exception month is selected
      if (compareExcMonth) {
        setExcDiffVisible(true);
        setExcDiffLoading(true);
        try {
          const excData = await compareExceptionEntries(0, 9999, true, compareExcMonth, excDiffSearch || undefined);
          setExcDiff(excData);
        } catch {
          message.error(t('dataComparison.exceptionDiffFailed'));
        } finally {
          setExcDiffLoading(false);
        }
      } else {
        setExcDiffVisible(false);
        setExcDiff(null);
      }

      setActiveKey('archive');
    } catch {
      message.error(t('dataComparison.startCompareFailed'));
    } finally {
      setCompareLoading(false);
      setArchiveLoading(false);
    }
  }, [compareCheckMonth, compareRefMonth, compareExcMonth, t, archivePageSize, archiveSearch, archiveType, excDiffSearch]);

  // 分页加载通讯录差异
  const fetchArchivePage = useCallback(async (page: number, size: number, overrideType?: string) => {
    if (!archiveResult?.month1 || !archiveResult?.month2) return;
    setArchiveLoading(true);
    try {
      const typeParam = overrideType !== undefined ? (overrideType || undefined) : (archiveType || undefined);
      const data = await compareDirectory(archiveResult.month1, archiveResult.month2, page, size, archiveSearch || undefined, typeParam);
      setArchiveResult((prev: any) => ({ ...prev, diffs: data.diffs, total: data.total, page: data.page, size: data.size, total_pages: data.total_pages }));
      setArchivePage(page);
      setArchivePageSize(size);
    } catch {
      message.error(t('dataComparison.startCompareFailed'));
    } finally {
      setArchiveLoading(false);
    }
  }, [archiveResult, t, archiveSearch, archiveType]);

  // 例外数据差异：当前例外数据 vs 最新批次（compareExceptionEntries）
  const [excDiff, setExcDiff] = useState<{
    entries: Array<Record<string, any>>;
    total: number;
    total_all: number;
    changed: number;
    unchanged: number;
    billing_month?: string;
  } | null>(null);
  const [excDiffLoading, setExcDiffLoading] = useState(false);

  const fetchExcDiff = useCallback(async () => {
    setExcDiffLoading(true);
    try {
      const excMonth = archiveResult?.excMonth;
      const data = await compareExceptionEntries(0, 9999, true, excMonth || undefined, excDiffSearch || undefined);
      setExcDiff(data);
    } catch {
      message.error(t('dataComparison.exceptionDiffFailed'));
    } finally {
      setExcDiffLoading(false);
    }
  }, [archiveResult, t, excDiffSearch]);

  // ==================== Exception Tab: month + batch ====================
  const [excBatchMonth, setExcBatchMonth] = useState('');
  const [excBatches, setExcBatches] = useState<Array<Record<string, any>>>([]);
  const [excBatchesLoading, setExcBatchesLoading] = useState(false);
  const [excSelectedBatchId, setExcSelectedBatchId] = useState<number | null>(null);
  const [excBatchEntries, setExcBatchEntries] = useState<Array<Record<string, any>>>([]);
  const [excBatchTotal, setExcBatchTotal] = useState(0);
  const [excBatchPage, setExcBatchPage] = useState(0);
  const [excBatchPageSize, setExcBatchPageSize] = useState(50);
  const [excBatchSearch, setExcBatchSearch] = useState('');
  const [excBatchLoading, setExcBatchLoading] = useState(false);
  const excBatchMonthRef = useRef('');
  excBatchMonthRef.current = excBatchMonth;

  const fetchExcBatches = useCallback(async (month?: string) => {
    const m = month ?? excBatchMonthRef.current;
    setExcBatchesLoading(true);
    try {
      const data = await getDirectoryBatches(m || undefined);
      setExcBatches((data || []).filter((b) => (b.batch_no || '').startsWith(EXC_PREFIX)));
    } catch {
      message.error(t('dataComparison.fetchExceptionBatchesFailed'));
    } finally {
      setExcBatchesLoading(false);
    }
  }, [t]);

  const fetchExcBatchEntries = useCallback(async (search?: string, page?: number, size?: number, forceBatchId?: number) => {
    const id = forceBatchId ?? excSelectedBatchId;
    if (id == null) return;
    setExcBatchLoading(true);
    try {
      const res = await getDirectoryEntriesByBatch(id, search ?? excBatchSearch, page ?? excBatchPage, size ?? excBatchPageSize);
      setExcBatchEntries(res.entries || []);
      setExcBatchTotal(res.total || 0);
    } catch {
      message.error(t('dataComparison.fetchExceptionFailed'));
    } finally {
      setExcBatchLoading(false);
    }
  }, [excSelectedBatchId, excBatchSearch, excBatchPage, excBatchPageSize, t]);

  const selectExcBatch = useCallback((id: number) => {
    setExcSelectedBatchId(id);
    setExcBatchPage(0);
    setExcBatchSearch('');
    fetchExcBatchEntries('', 0, excBatchPageSize, id);
  }, [fetchExcBatchEntries, excBatchPageSize]);

  // ==================== Current Data Export: month selection ====================
  const [exportMonthModalOpen, setExportMonthModalOpen] = useState(false);
  const [exportSelectedMonth, setExportSelectedMonth] = useState('');
  const [exportDownloading, setExportDownloading] = useState(false);

  const handleExportClick = useCallback(() => {
    setExportSelectedMonth('');
    setExportMonthModalOpen(true);
  }, []);

  const handleExportConfirm = useCallback(async () => {
    if (!exportSelectedMonth) {
      message.warning(t('dataComparison.selectExportMonth'));
      return;
    }
    setExportDownloading(true);
    try {
      await exportDirectoryByMonth(exportSelectedMonth);
      message.success(t('dataComparison.exportSuccess'));
      setExportMonthModalOpen(false);
    } catch {
      message.error(t('dataComparison.exportFailed'));
    } finally {
      setExportDownloading(false);
    }
  }, [exportSelectedMonth, t]);

  // ==================== Exception Data Export: month selection ====================
  const [excExportMonthModalOpen, setExcExportMonthModalOpen] = useState(false);
  const [excExportSelectedMonth, setExcExportSelectedMonth] = useState('');
  const [excExportDownloading, setExcExportDownloading] = useState(false);

  const handleExcExportClick = useCallback(() => {
    setExcExportSelectedMonth('');
    setExcExportMonthModalOpen(true);
  }, []);

  const handleExcExportConfirm = useCallback(async () => {
    if (!excExportSelectedMonth) {
      message.warning(t('dataComparison.selectExportMonth'));
      return;
    }
    setExcExportDownloading(true);
    try {
      await exportExceptionByMonth(excExportSelectedMonth);
      message.success(t('dataComparison.exportSuccess'));
      setExcExportMonthModalOpen(false);
    } catch {
      message.error(t('dataComparison.exportFailed'));
    } finally {
      setExcExportDownloading(false);
    }
  }, [excExportSelectedMonth, t]);

  // ==================== Current Data Import ====================
  const [curImportModalOpen, setCurImportModalOpen] = useState(false);
  const [curImportPendingFile, setCurImportPendingFile] = useState<File | null>(null);
  const [curImportMonth, setCurImportMonth] = useState(dayjs().format('YYYY-MM'));
  const [curImportLoading, setCurImportLoading] = useState(false);

  const { progress: curImportProgress, polling: curImportPolling, startPolling: curStartPolling, percent: curImportPercent } = useImportProgress({
    onComplete: () => { fetchCurBatches(curBatchMonthRef.current); setCurSelectedBatchId(null); setCurBatchEntries([]); },
  });

  const handleCurImportFile = async (file: File) => {
    setCurImportPendingFile(file);
    setCurImportModalOpen(true);
  };

  const handleCurImportConfirmMonth = async () => {
    if (!curImportMonth) {
      message.warning(t('dataComparison.selectMonthFirst'));
      return;
    }
    const file = curImportPendingFile;
    setCurImportModalOpen(false);
    setCurImportPendingFile(null);
    if (!file) return;
    // 检查同月是否已有数据
    const existingMonth = months.includes(curImportMonth);
    if (existingMonth) {
      Modal.confirm({
        title: t('dataComparison.monthDataExists', { month: curImportMonth }),
        content: t('dataComparison.overwriteConfirm'),
        okText: t('dataComparison.overwriteBtn'),
        okButtonProps: { danger: true },
        cancelText: t('common.cancel'),
        onOk: async () => {
          setCurImportLoading(true);
          try {
            await deleteDirectoryBatchesByMonth(curImportMonth);
            const result = await importDirectory(file, curImportMonth);
            curStartPolling(result.batch_id, getDirectoryProgress);
            message.success(t('dataComparison.importStarted'));
          } catch {
            message.error(t('dataComparison.importFailed'));
          } finally {
            setCurImportLoading(false);
          }
        },
      });
      return;
    }
    setCurImportLoading(true);
    try {
      const result = await importDirectory(file, curImportMonth);
      curStartPolling(result.batch_id, getDirectoryProgress);
      message.success(t('dataComparison.importStarted'));
    } catch {
      message.error(t('dataComparison.importFailed'));
    } finally {
      setCurImportLoading(false);
    }
  };

  // ==================== Exception Import ====================
  const [excImportModalOpen, setExcImportModalOpen] = useState(false);
  const [excImportPendingFile, setExcImportPendingFile] = useState<File | null>(null);
  const [excImportMonth, setExcImportMonth] = useState(dayjs().format('YYYY-MM'));
  const [excImportLoading, setExcImportLoading] = useState(false);

  const handleExcImportFile = async (file: File) => {
    setExcImportPendingFile(file);
    setExcImportModalOpen(true);
  };

  const handleExcImportConfirmMonth = async () => {
    if (!excImportMonth) {
      message.warning(t('dataComparison.selectMonthFirst'));
      return;
    }
    const file = excImportPendingFile;
    setExcImportModalOpen(false);
    setExcImportPendingFile(null);
    if (!file) return;
    setExcImportLoading(true);
    try {
      const res = await importExceptionEntries(file, excImportMonth);
      message.success(t('dataComparison.excImportSuccess', { imported: res.imported, skipped: res.skipped }));
      fetchExcBatches(excBatchMonthRef.current);
    } catch {
      message.error(t('dataComparison.importFailed'));
    } finally {
      setExcImportLoading(false);
    }
  };

  // ==================== Initial load on mount ====================
  useEffect(() => {
    fetchCurBatches('');
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // ==================== Tab switch ====================
  const handleTabChange = useCallback((key: string) => {
    setActiveKey(key);
    if (key === 'current' && curBatches.length === 0) fetchCurBatches('');
    if (key === 'exception' && excBatches.length === 0) fetchExcBatches('');
    // 差异数据Tab：自动加载最后一次对比结果
    if (key === 'archive' && !archiveResult) {
      loadLatestArchive();
    }
  }, [curBatches.length, excBatches.length, fetchCurBatches, fetchExcBatches, archiveResult]);

  // 从归档加载最后一次对比结果
  const loadLatestArchive = useCallback(async () => {
    setArchiveLoading(true);
    setArchiveSearch('');
    setArchiveType('');
    setExcDiffSearch('');
    try {
      const data = await getLatestComparisonArchive();
      if (!data) { setArchiveLoading(false); return; }

      // 恢复通讯录差异
      const monthArchive = data.month_archive;
      if (monthArchive && monthArchive.result_json) {
        const snapshot = JSON.parse(monthArchive.result_json);
        setArchiveResult({
          ...snapshot,
          type: 'month',
          month1: monthArchive.month1,
          month2: monthArchive.month2,
          excMonth: '',
        });
      }

      // 恢复例外数据差异
      const excArchive = data.exception_archive;
      if (excArchive && excArchive.result_json) {
        const snapshot = JSON.parse(excArchive.result_json);
        setExcDiff({
          entries: snapshot.entries || [],
          total: snapshot.total || 0,
          total_all: snapshot.total_all || 0,
          changed: snapshot.changed || 0,
          unchanged: snapshot.unchanged || 0,
          billing_month: snapshot.billing_month,
        });
        setExcDiffVisible(true);
        // 同时设置 archiveResult 的 excMonth 用于推送
        if (excArchive.latest_month) {
          setArchiveResult((prev: any) => prev ? { ...prev, excMonth: excArchive.latest_month } : prev);
        }
      }
    } catch {
      // 无归档或解析失败，不影响页面
    } finally {
      setArchiveLoading(false);
    }
  }, []);

  // 通讯录差异搜索
  const handleArchiveSearch = useCallback(() => {
    if (!archiveResult?.month1 || !archiveResult?.month2) return;
    fetchArchivePage(0, archivePageSize);
  }, [archiveResult, fetchArchivePage, archivePageSize]);

  // 类型筛选
  const handleArchiveTypeChange = useCallback((v: string | undefined) => {
    setArchiveType(v || '');
    if (archiveResult?.month1 && archiveResult?.month2) {
      fetchArchivePage(0, archivePageSize, v || '');
    }
  }, [archiveResult, fetchArchivePage, archivePageSize]);

  // 例外差异搜索
  const handleExcDiffSearch = useCallback(() => {
    fetchExcDiff();
  }, [fetchExcDiff]);

  // ==================== Columns ====================
  const isChangedCol = (record: any, col: string) =>
    record.type === 'changed' && Array.isArray(record.changed_columns) && (
      (col === 'username' && (record.changed_columns.includes('用户名称') || record.changed_columns.includes('username'))) ||
      (col === 'phone_number' && (record.changed_columns.includes('号码') || record.changed_columns.includes('phone_number'))) ||
      (col === 'dept_path' && (record.changed_columns.includes('部门全路径') || record.changed_columns.includes('dept_path')))
    );

  const getBatchColumns = (isException = false) => [
    { title: t('phoneOwnership.batchNoCol'), dataIndex: 'batch_no', key: 'batch_no', width: 200 },
    { title: t('phoneOwnership.monthCol'), dataIndex: 'billing_month', key: 'billing_month', width: 120, render: (v: string) => v || t('phoneOwnership.monthNotSet') },
    { title: t('phoneOwnership.recordCountCol'), dataIndex: 'total_count', key: 'total_count', width: 100, align: 'center' as const },
    { title: t('phoneOwnership.importTimeCol'), dataIndex: 'created_at', key: 'created_at', width: 170, render: (v: string) => (v ? v.substring(0, 19) : '-') },
    {
      title: t('dataComparison.deleteBatch'), key: 'action', width: 80, align: 'center' as const,
      render: (_: any, record: any) => (
        <Popconfirm
          title={t('dataComparison.deleteBatchConfirm')}
          description={t('dataComparison.deleteBatchDesc', { batchNo: record.batch_no, count: record.total_count })}
          onConfirm={(e) => { e?.stopPropagation(); handleDeleteBatch(record.id, isException); }}
          onCancel={(e) => e?.stopPropagation()}
          okText={t('dataComparison.deleteBatch')}
          cancelText={t('common.cancel')}
          okButtonProps={{ danger: true }}
        >
          <Button type="link" danger size="small" icon={<DeleteOutlined />} onClick={(e) => e.stopPropagation()} />
        </Popconfirm>
      ),
    },
  ];

  const handleDeleteBatch = useCallback(async (id: number, isException = false) => {
    try {
      await deleteDirectoryBatch(id);
      message.success(t('dataComparison.deleteBatchSuccess'));
      if (isException) {
        fetchExcBatches(excBatchMonthRef.current);
        if (excSelectedBatchId === id) {
          setExcSelectedBatchId(null);
          setExcBatchEntries([]);
        }
      } else {
        fetchCurBatches(curBatchMonthRef.current);
        if (curSelectedBatchId === id) {
          setCurSelectedBatchId(null);
          setCurBatchEntries([]);
        }
      }
    } catch {
      message.error(t('dataComparison.deleteBatchFailed'));
    }
  }, [t, fetchCurBatches, fetchExcBatches, curSelectedBatchId, excSelectedBatchId]);

  const historyColumns = [
    {
      title: t('dataComparison.typeCol'), dataIndex: 'type', key: 'type', width: 80, align: 'center' as const,
      render: (type: string) => {
        const labels: Record<string, string> = { added: t('dataComparison.typeAdded'), removed: t('dataComparison.typeRemoved'), changed: t('dataComparison.typeChanged') };
        return <Tag color={TYPE_COLORS[type]}>{labels[type] || type}</Tag>;
      },
    },
    {
      title: t('dataComparison.usernameCol'), dataIndex: 'username', key: 'username', width: 120,
      render: (text: string, record: any) => {
        if (record.type === 'removed') return <span style={{ color: '#ff4d4f' }}>{record.month1_username || '-'}</span>;
        if (isChangedCol(record, 'username') && record.month1_username !== text)
          return <span><span style={{ textDecoration: 'line-through', color: '#999' }}>{record.month1_username}</span><br /><span style={{ color: '#1890ff' }}>{text}</span></span>;
        return text || '-';
      },
    },
    {
      title: t('dataComparison.extensionCol'), dataIndex: 'extension', key: 'extension', width: 120, align: 'center' as const,
      render: (text: string) => text || '-',
    },
    {
      title: t('dataComparison.phoneNumberCol'), dataIndex: 'phone_number', key: 'phone_number', width: 140,
      render: (text: string, record: any) => {
        if (record.type === 'removed') return <span style={{ color: '#ff4d4f' }}>{record.month1_phone_number || '-'}</span>;
        if (isChangedCol(record, 'phone_number') && record.month1_phone_number !== text)
          return <span><span style={{ textDecoration: 'line-through', color: '#999' }}>{record.month1_phone_number}</span><br /><span style={{ color: '#1890ff' }}>{text}</span></span>;
        return text || '-';
      },
    },
    {
      title: t('dataComparison.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 280,
      render: (text: string, record: any) => {
        if (record.type === 'removed') return <span style={{ color: '#ff4d4f' }}>{record.month1_dept_path || '-'}</span>;
        if (isChangedCol(record, 'dept_path') && record.month1_dept_path !== text)
          return <span><span style={{ textDecoration: 'line-through', color: '#999' }}>{record.month1_dept_path}</span><br /><span style={{ color: '#1890ff' }}>{text}</span></span>;
        return text || '-';
      },
    },
    {
      title: t('dataComparison.changedColumnsCol'), dataIndex: 'changed_columns', key: 'changed_columns', width: 140, align: 'center' as const,
      render: (cols: string[], record: any) => {
        if (record.type !== 'changed' || !Array.isArray(cols) || cols.length === 0) return '-';
        const colLabels: Record<string, string> = { '用户名称': t('dataComparison.usernameCol'), '号码': t('dataComparison.phoneNumberCol'), '部门全路径': t('dataComparison.deptPathCol'), username: t('dataComparison.usernameCol'), phone_number: t('dataComparison.phoneNumberCol'), dept_path: t('dataComparison.deptPathCol'), not_found: t('dataComparison.notFoundCol'), '最新通讯录未找到': t('dataComparison.notFoundCol') };
        return <span>{cols.map(c => <Tag key={c} color="orange" style={{ marginBottom: 2 }}>{colLabels[c] || c}</Tag>)}</span>;
      },
    },
  ];

  const excCompareColumns = [
    {
      title: t('dataComparison.usernameCol'), key: 'username', width: 120,
      render: (_: any, r: any) => r.changed_columns?.includes('用户名称')
        ? <span><span style={{ textDecoration: 'line-through', color: '#999' }}>{r.username}</span><br /><span style={{ color: '#1890ff' }}>{r.latest_username}</span></span>
        : <span style={{ color: '#ccc' }}>{r.username}</span>,
    },
    {
      title: t('dataComparison.extensionCol'), key: 'extension', width: 120, align: 'center' as const,
      render: (_: any, r: any) => <span style={{ color: '#ccc' }}>{r.extension}</span>,
    },
    {
      title: t('dataComparison.phoneNumberCol'), key: 'phone_number', width: 140,
      render: (_: any, r: any) => r.changed_columns?.includes('号码')
        ? <span><span style={{ textDecoration: 'line-through', color: '#999' }}>{r.phone_number}</span><br /><span style={{ color: '#1890ff' }}>{r.latest_phone_number}</span></span>
        : <span style={{ color: '#ccc' }}>{r.phone_number}</span>,
    },
    {
      title: t('dataComparison.deptPathCol'), key: 'dept_path', width: 280,
      render: (_: any, r: any) => r.changed_columns?.includes('部门全路径')
        ? <span><span style={{ textDecoration: 'line-through', color: '#999' }}>{r.dept_path}</span><br /><span style={{ color: '#1890ff' }}>{r.latest_dept_path}</span></span>
        : <span style={{ color: '#ccc' }}>{r.dept_path}</span>,
    },
    { title: t('dataComparison.secondedKeywordCol'), dataIndex: 'seconded_keyword', key: 'seconded_keyword', width: 180, ellipsis: true },
    {
      title: t('dataComparison.changedColumnsCol'), dataIndex: 'changed_columns', key: 'changed_columns', width: 140, align: 'center' as const,
      render: (cols: string[]) => {
        if (!Array.isArray(cols) || cols.length === 0) return <Tag color="default">{t('dataComparison.exceptionUnchanged')}</Tag>;
        const colLabels: Record<string, string> = { '用户名称': t('dataComparison.usernameCol'), '号码': t('dataComparison.phoneNumberCol'), '部门全路径': t('dataComparison.deptPathCol'), '最新通讯录未找到': t('dataComparison.notFoundCol') };
        return <span>{cols.map(c => <Tag key={c} color="orange" style={{ marginBottom: 2 }}>{colLabels[c] || c}</Tag>)}</span>;
      },
    },
  ];

  const currentColumns = [
    { title: t('dataComparison.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 300, ellipsis: true },
    { title: t('dataComparison.usernameCol'), dataIndex: 'username', key: 'username', width: 120 },
    { title: t('dataComparison.extensionCol'), dataIndex: 'extension', key: 'extension', width: 100, align: 'center' as const },
    { title: t('dataComparison.phoneNumberCol'), dataIndex: 'phone_number', key: 'phone_number', width: 150 },
  ];

  const exceptionColumns = [
    { title: t('dataComparison.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 250, ellipsis: true },
    { title: t('dataComparison.usernameCol'), dataIndex: 'username', key: 'username', width: 120 },
    { title: t('dataComparison.extensionCol'), dataIndex: 'extension', key: 'extension', width: 100, align: 'center' as const },
    { title: t('dataComparison.phoneNumberCol'), dataIndex: 'phone_number', key: 'phone_number', width: 150 },
    { title: t('dataComparison.secondedKeywordCol'), dataIndex: 'seconded_keyword', key: 'seconded_keyword', width: 200, ellipsis: true },
  ];

  // (差异数据Tab已改为：通讯录差异 + 例外数据差异 两个分区卡片，归档记录列表已移除)

  // ==================== Render: Current Data Tab ====================
  const renderCurrentTab = () => (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row justify="space-between" align="middle" wrap>
          <Space wrap>
            <Select
              value={curBatchMonth || undefined}
              onChange={(v) => { setCurBatchMonth(v || ''); setCurSelectedBatchId(null); setCurBatchEntries([]); fetchCurBatches(v || ''); }}
              placeholder={t('phoneOwnership.monthCol')}
              style={{ width: 160 }}
              allowClear
              options={months.map((m: string) => ({ value: m, label: m }))}
            />
            <Button size="small" onClick={() => fetchCurBatches(curBatchMonth)}>{t('dataComparison.refreshBtn')}</Button>
          </Space>
          <Space wrap>
            <Dropdown menu={{ items: [
              { key: 'import', icon: <UploadOutlined />, label: t('dataComparison.importBtn'), disabled: curImportLoading },
              { key: 'download', icon: <DownloadOutlined />, label: t('dataComparison.downloadTemplate') },
            ], onClick: ({ key }) => {
              if (key === 'import') document.getElementById('cur-directory-upload-input')?.click();
              if (key === 'download') downloadDirectoryTemplate();
            } }}>
              <Button icon={<UploadOutlined />} loading={curImportLoading && !curImportPolling}>{t('dataComparison.importBtn')}<DownOutlined /></Button>
            </Dropdown>
            <Button icon={<DownloadOutlined />} onClick={handleExportClick}>{t('dataComparison.exportBtn')}</Button>
            <input type="file" accept=".xlsx,.xls" id="cur-directory-upload-input" style={{ display: 'none' }}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleCurImportFile(f); e.target.value = ''; } }} />
            <Button type="primary" icon={<DiffOutlined />} loading={compareLoading} onClick={() => { setCompareCheckMonth(''); setCompareRefMonth(''); setCompareExcMonth(''); setCompareModalOpen(true); }}>
              {t('dataComparison.startCompareBtn')}
            </Button>
          </Space>
        </Row>
      </Card>

      <Card size="small" title={t('dataComparison.batchListTitle')} style={{ marginBottom: 16 }}>
        <Table
          dataSource={curBatches} columns={getBatchColumns(false)}
          rowKey="id" size="small" loading={curBatchesLoading}
          rowClassName={(r: any) => r.id === curSelectedBatchId ? 'row-selected' : ''}
          onRow={(r: any) => ({ onClick: () => selectCurBatch(r.id) })}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 700 }}
        />
      </Card>

      {curSelectedBatchId != null ? (
        <Card size="small" title={t('dataComparison.currentDetailTitle')}>
          <Space wrap style={{ marginBottom: 12 }}>
            <Input.Search
              placeholder={t('dataComparison.searchPlaceholder')}
              style={{ width: 300 }} allowClear
              onSearch={(val) => { setCurBatchSearch(val); setCurBatchPage(0); fetchCurBatchEntries(val, 0, curBatchPageSize); }}
            />
            <span style={{ color: '#999', fontSize: 12 }}>{t('common.paginationTotal', { total: curBatchTotal })}</span>
          </Space>
          <Table
            dataSource={curBatchEntries} columns={currentColumns}
            rowKey={(r: any) => `cur-batch-${r.id}`}
            size="small" loading={curBatchLoading}
            pagination={{
              current: curBatchPage + 1, pageSize: curBatchPageSize, total: curBatchTotal,
              showSizeChanger: true, pageSizeOptions: ['20', '50', '100'],
              showTotal: (total) => t('common.paginationTotal', { total }),
              onChange: (p, s) => { setCurBatchPage(p - 1); setCurBatchPageSize(s); fetchCurBatchEntries(curBatchSearch, p - 1, s); },
            }}
            scroll={{ x: 700 }}
          />
        </Card>
      ) : (
        <Card size="small"><div style={{ color: '#999', textAlign: 'center', padding: 24 }}>{t('dataComparison.noBatchSelected')}</div></Card>
      )}

      {/* Import Progress */}
      {curImportPolling && curImportProgress && (
        <Card size="small" style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 4 }}>{t('dataComparison.importProgress')}</div>
          <Progress percent={curImportPercent}
            format={() => `${curImportProgress.processed}/${curImportProgress.total}`}
            status={curImportPercent >= 100 ? 'success' : 'active'}
          />
          {curImportPercent >= 100 && (
            <Button size="small" style={{ marginTop: 8 }} onClick={() => fetchCurBatches(curBatchMonthRef.current)}>
              {t('dataComparison.refreshAfterImport')}
            </Button>
          )}
        </Card>
      )}

    </>
  );

  // ==================== Render: Diff Tab (上下两个分区卡片) ====================
  const renderArchiveTab = () => (
    <>
      {/* 通讯录差异：由数据录入 Tab「数据对比」按钮驱动 */}
      <Card size="small" title={t('dataComparison.directoryDiffTitle')} style={{ marginBottom: 16 }}
        extra={
          <Space size="small">
            <Popconfirm
              title={t('dataComparison.pushToAllocOrgConfirm')}
              onConfirm={() => {
                if (!archiveResult?.month1 || !archiveResult?.month2) return;
                pushComparisonToAllocationOrg({
                  push_type: 'directory',
                  month1: archiveResult.month1,
                  month2: archiveResult.month2,
                  types: ['added', 'changed'],
                }).then((res: any) => {
                  message.success(res?.message || t('dataComparison.pushSuccess'));
                }).catch(() => {
                  message.error(t('dataComparison.pushFailed'));
                });
              }}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
            >
              <Button
                size="small" icon={<SendOutlined />}
                disabled={!archiveResult || archiveResult.type === 'exception' || !archiveResult.month1 || !archiveResult.month2}
              >
                {t('dataComparison.pushToAllocOrg')}
              </Button>
            </Popconfirm>
            <Button
              size="small" icon={<DownloadOutlined />}
              disabled={!archiveResult || archiveResult.type === 'exception' || !archiveResult.month1 || !archiveResult.month2}
              onClick={() => exportDirectoryComparison(archiveResult!.month1, archiveResult!.month2)}
            >
              {t('dataComparison.exportBtn')}
            </Button>
          </Space>
        }>
        {archiveResult && archiveResult.type !== 'exception' ? (
          <>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={4}><Card size="small"><Statistic title={t('dataComparison.addedCount')} value={archiveResult.added} valueStyle={{ color: '#52c41a' }} /></Card></Col>
              <Col span={4}><Card size="small"><Statistic title={t('dataComparison.removedCount')} value={archiveResult.removed} valueStyle={{ color: '#ff4d4f' }} /></Card></Col>
              <Col span={4}><Card size="small"><Statistic title={t('dataComparison.changedCount')} value={archiveResult.changed} valueStyle={{ color: '#faad14' }} /></Card></Col>
              <Col span={4}><Card size="small"><Statistic title={t('dataComparison.unchangedCount')} value={archiveResult.unchanged} valueStyle={{ color: '#999' }} /></Card></Col>
            </Row>
            <div style={{ marginBottom: 16, display: 'flex', gap: 8 }}>
              <Input.Search
                placeholder={t('dataComparison.searchPlaceholder')}
                value={archiveSearch}
                onChange={(e) => setArchiveSearch(e.target.value)}
                onSearch={handleArchiveSearch}
                style={{ width: 300 }}
                allowClear
              />
              <Select
                value={archiveType || undefined}
                onChange={handleArchiveTypeChange}
                placeholder={t('dataComparison.typeCol')}
                style={{ width: 120 }}
                allowClear
                options={[
                  { value: 'added', label: t('dataComparison.typeAdded') },
                  { value: 'removed', label: t('dataComparison.typeRemoved') },
                  { value: 'changed', label: t('dataComparison.typeChanged') },
                ]}
              />
            </div>
            <Table
              dataSource={archiveResult.diffs || []} columns={historyColumns}
              rowKey={(_r: any, i: number) => `arch-detail-${i}`}
              size="small" loading={archiveLoading}
              pagination={{
                current: archivePage + 1,
                pageSize: archivePageSize,
                total: archiveResult.total || 0,
                showSizeChanger: true,
                pageSizeOptions: ['20', '50', '100'],
                showTotal: (total: number) => t('common.paginationTotal', { total }),
                onChange: (p: number, s: number) => fetchArchivePage(p - 1, s),
              }}
              scroll={{ x: 700 }}
            />
          </>
        ) : (
          <div style={{ color: '#999', textAlign: 'center', padding: 24 }}>{t('dataComparison.directoryDiffEmpty')}</div>
        )}
      </Card>

      {/* 例外数据差异：仅在数据对比时选择了例外月份才显示 */}
      {excDiffVisible && (
      <Card size="small" title={t('dataComparison.exceptionDiffTitle')}
        extra={
          <Space size="small">
            <Popconfirm
              title={t('dataComparison.pushToAllocOrgConfirm')}
              onConfirm={() => {
                pushComparisonToAllocationOrg({
                  push_type: 'exception',
                  month: archiveResult?.excMonth || undefined,
                }).then((res: any) => {
                  message.success(res?.message || t('dataComparison.pushSuccess'));
                }).catch(() => {
                  message.error(t('dataComparison.pushFailed'));
                });
              }}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
            >
              <Button size="small" icon={<SendOutlined />} disabled={!excDiff}>{t('dataComparison.pushToAllocOrg')}</Button>
            </Popconfirm>
            <Button size="small" icon={<HistoryOutlined />} onClick={fetchExcDiff}>{t('dataComparison.refreshBtn')}</Button>
            <Button size="small" icon={<DownloadOutlined />} disabled={!excDiff} onClick={() => exportExceptionCompare(archiveResult?.excMonth || undefined, true)}>{t('dataComparison.exportBtn')}</Button>
          </Space>
        }>
        {excDiff ? (
          <>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={8}><Card size="small"><Statistic title={t('dataComparison.totalCol')} value={excDiff.total_all ?? excDiff.total} /></Card></Col>
              <Col span={8}><Card size="small"><Statistic title={t('dataComparison.exceptionChanged')} value={excDiff.changed} valueStyle={{ color: '#faad14' }} /></Card></Col>
              <Col span={8}><Card size="small"><Statistic title={t('dataComparison.exceptionUnchanged')} value={excDiff.unchanged} valueStyle={{ color: '#999' }} /></Card></Col>
            </Row>
            <div style={{ marginBottom: 16 }}>
              <Input.Search
                placeholder={t('dataComparison.searchPlaceholder')}
                value={excDiffSearch}
                onChange={(e) => setExcDiffSearch(e.target.value)}
                onSearch={handleExcDiffSearch}
                style={{ width: 300 }}
                allowClear
              />
            </div>
            <Table
              dataSource={excDiff.entries || []} columns={excCompareColumns}
              rowKey={(r, i) => `exc-diff-${r.id ?? i}`}
              size="small" loading={excDiffLoading}
              pagination={{ pageSize: 50 }}
              scroll={{ x: 900 }}
            />
          </>
        ) : (
          <div style={{ color: '#999', textAlign: 'center', padding: 24 }}>{t('dataComparison.exceptionDiffEmpty')}</div>
        )}
      </Card>
      )}
    </>
  );

  // ==================== Render: Exception Tab ====================
  const renderExceptionTab = () => (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row justify="space-between" align="middle" wrap>
          <Space wrap>
            <Select
              value={excBatchMonth || undefined}
              onChange={(v) => { setExcBatchMonth(v || ''); setExcSelectedBatchId(null); setExcBatchEntries([]); fetchExcBatches(v || ''); }}
              placeholder={t('phoneOwnership.monthCol')}
              style={{ width: 160 }}
              allowClear
              options={excMonths.map((m: string) => ({ value: m, label: m }))}
            />
            <Button size="small" onClick={() => fetchExcBatches(excBatchMonth)}>{t('dataComparison.refreshBtn')}</Button>
          </Space>
          <Space wrap>
            <Dropdown menu={{ items: [
              { key: 'import', icon: <UploadOutlined />, label: t('dataComparison.importBtn'), disabled: excImportLoading },
              { key: 'download', icon: <DownloadOutlined />, label: t('dataComparison.downloadTemplate') },
            ], onClick: ({ key }) => {
              if (key === 'import') document.getElementById('exc-directory-upload-input')?.click();
              if (key === 'download') downloadDirectoryExceptionTemplate();
            } }}>
              <Button icon={<UploadOutlined />} loading={excImportLoading}>{t('dataComparison.importBtn')}<DownOutlined /></Button>
            </Dropdown>
            <Button icon={<DownloadOutlined />} onClick={handleExcExportClick}>{t('dataComparison.exportBtn')}</Button>
            <input type="file" accept=".xlsx,.xls" id="exc-directory-upload-input" style={{ display: 'none' }}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleExcImportFile(f); e.target.value = ''; } }} />
          </Space>
        </Row>
      </Card>

      <Card size="small" title={t('dataComparison.batchListTitle')} style={{ marginBottom: 16 }}>
        <Table
          dataSource={excBatches} columns={getBatchColumns(true)}
          rowKey="id" size="small" loading={excBatchesLoading}
          rowClassName={(r: any) => r.id === excSelectedBatchId ? 'row-selected' : ''}
          onRow={(r: any) => ({ onClick: () => selectExcBatch(r.id) })}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 700 }}
        />
      </Card>

      {excSelectedBatchId != null ? (
        <Card size="small" title={t('dataComparison.exceptionDetailTitle')}>
          <Space wrap style={{ marginBottom: 12 }}>
            <Input.Search
              placeholder={t('dataComparison.searchPlaceholder')}
              style={{ width: 300 }} allowClear
              onSearch={(val) => { setExcBatchSearch(val); setExcBatchPage(0); fetchExcBatchEntries(val, 0, excBatchPageSize); }}
            />
            <span style={{ color: '#999', fontSize: 12 }}>{t('common.paginationTotal', { total: excBatchTotal })}</span>
          </Space>
          <Table
            dataSource={excBatchEntries} columns={exceptionColumns}
            rowKey={(r: any) => `exc-batch-${r.id}`}
            size="small" loading={excBatchLoading}
            pagination={{
              current: excBatchPage + 1, pageSize: excBatchPageSize, total: excBatchTotal,
              showSizeChanger: true, pageSizeOptions: ['20', '50', '100'],
              showTotal: (total) => t('common.paginationTotal', { total }),
              onChange: (p, s) => { setExcBatchPage(p - 1); setExcBatchPageSize(s); fetchExcBatchEntries(excBatchSearch, p - 1, s); },
            }}
            scroll={{ x: 800 }}
          />
        </Card>
      ) : (
        <Card size="small"><div style={{ color: '#999', textAlign: 'center', padding: 24 }}>{t('dataComparison.noBatchSelected')}</div></Card>
      )}
    </>
  );

  // ==================== Tab items ====================
  const tabItems = [
    { key: 'current', label: t('dataComparison.currentDataTab'), children: renderCurrentTab() },
    { key: 'archive', label: t('dataComparison.historyDataTab'), children: renderArchiveTab() },
    { key: 'exception', label: t('dataComparison.exceptionDataTab'), children: renderExceptionTab() },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Tabs activeKey={activeKey} items={tabItems} onChange={handleTabChange} />

      {/* Current data import month picker modal */}
      <Modal
        title={t('dataComparison.importTitle')}
        open={curImportModalOpen}
        onOk={handleCurImportConfirmMonth}
        onCancel={() => { setCurImportModalOpen(false); setCurImportPendingFile(null); }}
        okText={t('common.confirm')}
        okButtonProps={{ disabled: !curImportMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('dataComparison.selectMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          format="YYYY年MM月"
          value={curImportMonth ? dayjs(curImportMonth, 'YYYY-MM') : null}
          onChange={(date) => setCurImportMonth(date ? date.format('YYYY-MM') : '')}
          allowClear={false}
        />
        {curImportPendingFile && (
          <p style={{ marginTop: 8, color: '#999', fontSize: 12 }}>{curImportPendingFile.name}</p>
        )}
      </Modal>

      {/* Export month selection Modal */}
      <Modal
        title={t('dataComparison.exportMonthTitle')}
        open={exportMonthModalOpen}
        onCancel={() => setExportMonthModalOpen(false)}
        okText={t('dataComparison.exportConfirmBtn')}
        confirmLoading={exportDownloading}
        onOk={handleExportConfirm}
        okButtonProps={{ disabled: !exportSelectedMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('dataComparison.selectExportMonthHint')}</p>
        <Select
          style={{ width: '100%' }}
          placeholder={t('dataComparison.selectExportMonth')}
          value={exportSelectedMonth || undefined}
          onChange={setExportSelectedMonth}
          options={months.map((m: string) => ({ value: m, label: m }))}
        />
      </Modal>

      {/* Exception import month picker modal */}
      <Modal
        title={t('dataComparison.excImportTitle')}
        open={excImportModalOpen}
        onOk={handleExcImportConfirmMonth}
        onCancel={() => { setExcImportModalOpen(false); setExcImportPendingFile(null); }}
        okText={t('common.confirm')}
        okButtonProps={{ disabled: !excImportMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('dataComparison.excSelectMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          format="YYYY年MM月"
          value={excImportMonth ? dayjs(excImportMonth, 'YYYY-MM') : null}
          onChange={(date) => setExcImportMonth(date ? date.format('YYYY-MM') : '')}
          allowClear={false}
        />
        {excImportPendingFile && (
          <p style={{ marginTop: 8, color: '#999', fontSize: 12 }}>{excImportPendingFile.name}</p>
        )}
      </Modal>

      {/* Exception export month selection Modal */}
      <Modal
        title={t('dataComparison.excExportMonthTitle')}
        open={excExportMonthModalOpen}
        onCancel={() => setExcExportMonthModalOpen(false)}
        okText={t('dataComparison.exportConfirmBtn')}
        confirmLoading={excExportDownloading}
        onOk={handleExcExportConfirm}
        okButtonProps={{ disabled: !excExportSelectedMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('dataComparison.excSelectExportMonthHint')}</p>
        <Select
          style={{ width: '100%' }}
          placeholder={t('dataComparison.selectExportMonth')}
          value={excExportSelectedMonth || undefined}
          onChange={setExcExportSelectedMonth}
          options={excMonths.map((m: string) => ({ value: m, label: m }))}
        />
      </Modal>

      {/* Data Comparison Modal: select 3 months */}
      <Modal
        title={t('dataComparison.compareModalTitle')}
        open={compareModalOpen}
        onCancel={() => setCompareModalOpen(false)}
        onOk={handleMonthCompare}
        confirmLoading={compareLoading}
        okText={t('dataComparison.startCompareBtn')}
        okButtonProps={{ disabled: !compareCheckMonth || !compareRefMonth }}
      >
        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>{t('dataComparison.checkMonthLabel')}</label>
          <Select
            style={{ width: '100%' }}
            placeholder={t('dataComparison.selectMonthPlaceholder')}
            value={compareCheckMonth || undefined}
            onChange={setCompareCheckMonth}
            options={months.map((m: string) => ({ value: m, label: m }))}
          />
        </div>
        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>{t('dataComparison.refMonthLabel')}</label>
          <Select
            style={{ width: '100%' }}
            placeholder={t('dataComparison.selectMonthPlaceholder')}
            value={compareRefMonth || undefined}
            onChange={setCompareRefMonth}
            options={months.map((m: string) => ({ value: m, label: m }))}
          />
        </div>
        <div>
          <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
            {t('dataComparison.excMonthLabel')}
            <span style={{ color: '#999', fontWeight: 400, fontSize: 12 }}> ({t('dataComparison.excMonthOptional')})</span>
          </label>
          <Select
            style={{ width: '100%' }}
            placeholder={t('dataComparison.selectMonthPlaceholder')}
            value={compareExcMonth || undefined}
            onChange={setCompareExcMonth}
            allowClear
            options={excMonths.map((m: string) => ({ value: m, label: m }))}
          />
        </div>
      </Modal>
    </div>
  );
};

export default DataComparisonPage;
