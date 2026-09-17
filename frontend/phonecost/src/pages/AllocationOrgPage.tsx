import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Row, message, Input, Button, Space, Select, Modal, Progress, DatePicker, Tabs, Form, Tag, Popconfirm, Dropdown } from 'antd';
import { UploadOutlined, DownloadOutlined, ExportOutlined, ReloadOutlined, EditOutlined, DeleteOutlined, DownOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';

import {
  getAllocOrgEntriesByBatch,
  getAllocOrgBatches,
  getAllocOrgMonths,
  exportAllocOrg,
  importAllocOrg,
  getAllocOrgProgress,
  downloadAllocOrgTemplate,
  updateAllocOrgEntry,
  deleteAllocOrgEntry,
  deleteAllocOrgBatch,
  getDirectoryMonths,
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';
import type { ImportProgress } from '../types/import';

type SourceTab = 'import' | 'exception' | 'exceptionDiff';

/**
 * 号码分摊机构页面 — 三 Tab（统一「月份 → 批次列表 → 批次明细」三层架构）：
 * 1) 号码分摊机构（import）：导入 ALLOC-ORG-/推送 COMP-/BRN- 批次；分机号/部门全路径实时匹配同月通讯录，分摊三列优先例外清单匹配、对照表兜底
 * 2) 例外号码清单（exception）：仅导入批次（EXC-IMP-，本 Tab 上传产生），明细为全部条目+上月对比差异标记
 * 3) 差异数据（exceptionDiff）：仅推送批次（PUSH-EXC-，数据对比页推送产生），明细仅与上月通讯录有差异的条目，附上月值对比
 */
const AllocationOrgPage: React.FC = () => {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);
  const canDeleteBatch = useAuthStore((s) => s.role === 1);

  // ==================== Tab state ====================
  const [activeTab, setActiveTab] = useState<SourceTab>('import');

  // ==================== Data state ====================
  // 例外清单/差异数据 Tab：月份 + 批次列表 + 批次明细（与号码分摊机构 Tab 相同架构）
  const [excMonth, setExcMonth] = useState<string | undefined>(undefined);
  const [excBatches, setExcBatches] = useState<Array<Record<string, any>>>([]);
  const [excBatchesLoading, setExcBatchesLoading] = useState(false);
  const [excSelectedBatchId, setExcSelectedBatchId] = useState<number | null>(null);
  const [excBatchEntries, setExcBatchEntries] = useState<Record<string, unknown>[]>([]);
  const [excBatchTotal, setExcBatchTotal] = useState(0);
  const [excBatchPage, setExcBatchPage] = useState(0);
  const [excBatchPageSize, setExcBatchPageSize] = useState(50);
  const [excBatchSearch, setExcBatchSearch] = useState('');
  const [excBatchLoading, setExcBatchLoading] = useState(false);
  const [excPrevMonth, setExcPrevMonth] = useState('');
  // 差异数据 Tab：对比月份（默认=指定月+1，通过对比弹窗确认）
  const [excCompareMonth, setExcCompareMonth] = useState<string | undefined>(undefined);
  const [directoryMonths, setDirectoryMonths] = useState<string[]>([]);
  // 差异数据 Tab：对比弹窗
  const [diffModalOpen, setDiffModalOpen] = useState(false);
  const [diffModalMonth, setDiffModalMonth] = useState<string | undefined>(undefined);
  // 差异数据是否已生成（点击对比确认后置 true，切换批次/月份时重置）
  const [diffGenerated, setDiffGenerated] = useState(false);

  // import Tab：月份 + 批次列表 + 批次明细
  const [importMonth, setImportMonth] = useState<string | undefined>(undefined);
  const [batches, setBatches] = useState<Array<Record<string, any>>>([]);
  const [batchesLoading, setBatchesLoading] = useState(false);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [batchEntries, setBatchEntries] = useState<Record<string, unknown>[]>([]);
  const [batchTotal, setBatchTotal] = useState(0);
  const [batchPage, setBatchPage] = useState(0);
  const [batchPageSize, setBatchPageSize] = useState(50);
  const [batchSearch, setBatchSearch] = useState('');
  const [batchLoading, setBatchLoading] = useState(false);
  const importMonthRef = useRef<string | undefined>(undefined);
  importMonthRef.current = importMonth;

  // Month filter
  const [availableMonths, setAvailableMonths] = useState<string[]>([]);

  // Import (import Tab)
  const [uploading, setUploading] = useState(false);
  const [importMonthModal, setImportMonthModal] = useState(false);
  const [importBillingMonth, setImportBillingMonth] = useState<string>(dayjs().format('YYYY-MM'));
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Import (exception Tab)
  const [excUploading, setExcUploading] = useState(false);
  const [excImportMonthModal, setExcImportMonthModal] = useState(false);
  const [excBillingMonth, setExcBillingMonth] = useState<string>(dayjs().format('YYYY-MM'));
  const excFileInputRef = useRef<HTMLInputElement>(null);

  // Edit
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingEntry, setEditingEntry] = useState<Record<string, unknown> | null>(null);
  const [editSaving, setEditSaving] = useState(false);
  const [editForm] = Form.useForm();

  // Async import progress
  const { progress: importProgress, polling: importPolling, startPolling, percent: importPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(t('allocationOrg.importSuccess', { total: p.total }));
      // 刷新月份 + 批次列表
      fetchMonths('import');
      if (importBillingMonth) {
        setImportMonth(importBillingMonth);
        fetchBatches(importBillingMonth, 'import');
      }
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(t('allocationOrg.importFailed', { error: p.message || t('common.unknown') }));
      setUploading(false);
    },
  });

  // Async import progress (exception Tab)
  const { progress: excImportProgress, polling: excImportPolling, startPolling: startExcPolling, percent: excImportPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(t('allocationOrg.exceptionImportSuccess', { total: p.total }));
      fetchMonths('exception');
      if (excBillingMonth) {
        setExcMonth(excBillingMonth);
        fetchExcBatches(excBillingMonth);
      }
      setExcUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(t('allocationOrg.importFailed', { error: p.message || t('common.unknown') }));
      setExcUploading(false);
    },
  });

  // ==================== Fetch months ====================
  const fetchMonths = useCallback(async (source?: SourceTab) => {
    try {
      // 例外清单 Tab / 差异数据 Tab 均为导入批次（EXC-IMP-）；差异 Tab 额外仅差异条目
      const months = await getAllocOrgMonths(
        source === 'import' ? 'import' : 'exception',
      );
      setAvailableMonths(months);
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => { fetchMonths(activeTab); }, [activeTab, fetchMonths]);

  // ==================== Fetch batches (import Tab) ====================
  const fetchBatches = useCallback(async (month?: string, source?: SourceTab) => {
    setBatchesLoading(true);
    try {
      const data = await getAllocOrgBatches(month || undefined, source || 'import');
      setBatches(data || []);
      // 若当前选中的批次不在新列表中，清空选中
      setSelectedBatchId((prev) => {
        const stillExists = (data || []).some((b) => b.id === prev);
        if (!stillExists) {
          setBatchEntries([]);
          setBatchTotal(0);
          return null;
        }
        return prev;
      });
    } catch {
      message.error(t('allocationOrg.fetchBatchesFailed'));
    } finally {
      setBatchesLoading(false);
    }
  }, [t]);

  // import Tab：月份变化 → 重新加载批次列表
  useEffect(() => {
    if (activeTab === 'import') {
      setSelectedBatchId(null);
      setBatchEntries([]);
      setBatchTotal(0);
      setBatchPage(0);
      setBatchSearch('');
      fetchBatches(importMonth, 'import');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [importMonth, activeTab]);

  // ==================== Fetch batch entries (import Tab 明细) ====================
  const fetchBatchEntries = useCallback(async (searchVal?: string, p?: number, s?: number, forceBatchId?: number) => {
    const id = forceBatchId ?? selectedBatchId;
    if (id == null) return;
    setBatchLoading(true);
    try {
      const res = await getAllocOrgEntriesByBatch(id, searchVal ?? batchSearch, p ?? batchPage, s ?? batchPageSize);
      setBatchEntries(res.entries || []);
      setBatchTotal(res.total || 0);
    } catch {
      message.error(t('allocationOrg.fetchBatchEntriesFailed'));
    } finally {
      setBatchLoading(false);
    }
  }, [selectedBatchId, batchSearch, batchPage, batchPageSize, t]);

  const selectBatch = useCallback((id: number) => {
    setSelectedBatchId(id);
    setBatchPage(0);
    setBatchSearch('');
    fetchBatchEntries('', 0, batchPageSize, id);
  }, [fetchBatchEntries, batchPageSize]);

  // ==================== Fetch batches (例外清单 Tab / 差异数据 Tab: 均为导入批次 EXC-IMP-，差异 Tab 明细仅差异条目) ====================
  const fetchExcBatches = useCallback(async (month?: string) => {
    setExcBatchesLoading(true);
    try {
      const data = await getAllocOrgBatches(month || undefined, 'exception');
      setExcBatches(data || []);
      // 若当前选中的批次不在新列表中，清空选中
      setExcSelectedBatchId((prev) => {
        const stillExists = (data || []).some((b) => b.id === prev);
        if (!stillExists) {
          setExcBatchEntries([]);
          setExcBatchTotal(0);
          return null;
        }
        return prev;
      });
    } catch {
      message.error(t('allocationOrg.fetchBatchesFailed'));
    } finally {
      setExcBatchesLoading(false);
    }
  }, [activeTab, t]);

  // 通讯录月份列表（差异 Tab 对比月份下拉选项）+ 默认对比月 = 指定月 + 1
  useEffect(() => {
    getDirectoryMonths().then((m) => setDirectoryMonths(m || [])).catch(() => {});
  }, []);

  // 例外/差异 Tab：月份变化 → 重置批次 + 默认对比月份 = 指定月 + 1
  useEffect(() => {
    if (activeTab === 'exception' || activeTab === 'exceptionDiff') {
      setExcSelectedBatchId(null);
      setExcBatchEntries([]);
      setExcBatchTotal(0);
      setExcBatchPage(0);
      setExcBatchSearch('');
      // 差异数据 Tab：月份变化时重置对比状态
      if (activeTab === 'exceptionDiff') {
        setDiffGenerated(false);
        setExcCompareMonth(undefined);
      }
      fetchExcBatches(excMonth);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [excMonth, activeTab]);

  // ==================== Fetch exception batch entries (例外/差异 Tab 批次明细；差异 Tab 仅差异条目) ====================
  const fetchExcBatchEntries = useCallback(async (searchVal?: string, p?: number, s?: number, forceBatchId?: number, forceCompareMonth?: string) => {
    const id = forceBatchId ?? excSelectedBatchId;
    if (id == null) return;
    setExcBatchLoading(true);
    try {
      const res = await getAllocOrgEntriesByBatch(
        id, searchVal ?? excBatchSearch, p ?? excBatchPage, s ?? excBatchPageSize,
        'exception',
        activeTab === 'exceptionDiff' ? (forceCompareMonth ?? excCompareMonth) : undefined,
        activeTab === 'exceptionDiff',
      );
      setExcBatchEntries(res.entries || []);
      setExcBatchTotal(res.total || 0);
      setExcPrevMonth((res as Record<string, unknown>).compare_month as string || '');
    } catch {
      message.error(t('allocationOrg.fetchBatchEntriesFailed'));
    } finally {
      setExcBatchLoading(false);
    }
  }, [excSelectedBatchId, excBatchSearch, excBatchPage, excBatchPageSize, activeTab, excCompareMonth, t]);

  const selectExcBatch = useCallback((id: number) => {
    setExcSelectedBatchId(id);
    setExcBatchPage(0);
    setExcBatchSearch('');
    // 差异数据 Tab：选中批次后不自动加载明细，需点「对比」按钮
    if (activeTab === 'exceptionDiff') {
      setDiffGenerated(false);
      setExcBatchEntries([]);
      setExcBatchTotal(0);
      return;
    }
    fetchExcBatchEntries('', 0, excBatchPageSize, id);
  }, [fetchExcBatchEntries, excBatchPageSize, activeTab]);

  // 差异数据 Tab：点击「对比」按钮 → 打开弹窗（默认对比月=批次月+1）
  const openDiffCompare = () => {
    if (excSelectedBatchId == null) {
      message.warning(t('allocationOrg.diffSelectBatchFirst'));
      return;
    }
    // 计算默认对比月 = 选中批次月份 + 1
    const selBatch = excBatches.find((b) => b.id === excSelectedBatchId);
    const batchMonth = selBatch?.billing_month as string | undefined;
    const defaultCmp = batchMonth ? dayjs(batchMonth).add(1, 'month').format('YYYY-MM') : undefined;
    setDiffModalMonth(defaultCmp);
    setDiffModalOpen(true);
  };

  // 差异数据 Tab：确认对比 → 加载差异明细（直接传弹窗选中的月份，避免闭包陷阱）
  const confirmDiffCompare = () => {
    setExcCompareMonth(diffModalMonth);
    setDiffModalOpen(false);
    setDiffGenerated(true);
    // 立即加载差异明细（forceCompareMonth 直接传入选中月份，不依赖 state 更新时序）
    fetchExcBatchEntries('', 0, excBatchPageSize, undefined, diffModalMonth);
  };

  // ==================== Tab change ====================
  const handleTabChange = (key: string) => {
    const newTab = key as SourceTab;
    setActiveTab(newTab);
    setImportMonth(undefined);
    setExcMonth(undefined);
    setExcSelectedBatchId(null);
    setExcBatchEntries([]);
    setExcBatchTotal(0);
    setExcBatchPage(0);
    setExcBatchSearch('');
    setExcPrevMonth('');
    setDiffGenerated(false);
    setExcCompareMonth(undefined);
    setSelectedBatchId(null);
    setBatchEntries([]);
    setBatchTotal(0);
  };

  // ==================== Import handlers ====================
  const handleImportClick = () => {
    setImportMonthModal(true);
  };

  const handleConfirmMonth = () => {
    if (!importBillingMonth) {
      message.warning(t('allocationOrg.selectMonthFirst'));
      return;
    }
    setImportMonthModal(false);
    setTimeout(() => {
      fileInputRef.current?.click();
    }, 100);
  };

  const handleFileSelected = async (file: File) => {
    const month = importBillingMonth;
    setUploading(true);
    try {
      const result = await importAllocOrg(file, month);
      startPolling(result.batch_id, getAllocOrgProgress);
    } catch (err) {
      message.error(t('allocationOrg.importFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
      setUploading(false);
    }
  };

  // ==================== Exception import handlers ====================
  const handleExcImportClick = () => {
    setExcImportMonthModal(true);
  };

  const handleExcConfirmMonth = () => {
    if (!excBillingMonth) {
      message.warning(t('allocationOrg.selectMonthFirst'));
      return;
    }
    setExcImportMonthModal(false);
    setTimeout(() => {
      excFileInputRef.current?.click();
    }, 100);
  };

  const handleExcFileSelected = async (file: File) => {
    const month = excBillingMonth;
    setExcUploading(true);
    try {
      const result = await importAllocOrg(file, month, 'exception');
      startExcPolling(result.batch_id, getAllocOrgProgress);
    } catch (err) {
      message.error(t('allocationOrg.importFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
      setExcUploading(false);
    }
  };

  // ==================== Export（仅导出选中批次，未选中提示） ====================
  const handleExport = () => {
    if (activeTab === 'import') {
      if (selectedBatchId == null) {
        message.warning(t('allocationOrg.exportSelectBatchFirst'));
        return;
      }
      exportAllocOrg(importMonth, 'import', selectedBatchId);
    } else {
      if (excSelectedBatchId == null) {
        message.warning(t('allocationOrg.exportSelectBatchFirst'));
        return;
      }
      exportAllocOrg(
        excMonth,
        'exception',
        excSelectedBatchId,
        activeTab === 'exceptionDiff' ? excCompareMonth : undefined,
        activeTab === 'exceptionDiff',
      );
    }
  };

  // ==================== Edit handlers ====================
  const handleEdit = (record: Record<string, unknown>) => {
    setEditingEntry(record);
    if (activeTab === 'import') {
      // 号码分摊机构：分机号/部门全路径实时匹配通讯录，不可编辑
      editForm.setFieldsValue({
        phone_number: record.phone_number,
        l1_branch: record.l1_branch,
        alloc_dept: record.alloc_dept,
        org_code: record.org_code,
        cost_center: record.cost_center,
        remark: record.remark,
      });
    } else {
      // 例外批次：用户名称/分机号/部门全路径为存储值，可编辑
      editForm.setFieldsValue({
        phone_number: record.phone_number,
        username: record.username,
        extension: record.extension,
        dept_path: record.dept_path,
        l1_branch: record.l1_branch,
        alloc_dept: record.alloc_dept,
        org_code: record.org_code,
        cost_center: record.cost_center,
        remark: record.remark,
      });
    }
    setEditModalOpen(true);
  };

  const handleEditSave = async () => {
    if (!editingEntry) return;
    try {
      const values = await editForm.validateFields();
      setEditSaving(true);
      await updateAllocOrgEntry(editingEntry.id as number, values);
      message.success(t('allocationOrg.editSuccess'));
      setEditModalOpen(false);
      if (activeTab === 'import') {
        fetchBatchEntries(batchSearch, batchPage, batchPageSize);
      } else {
        fetchExcBatchEntries(excBatchSearch, excBatchPage, excBatchPageSize);
      }
    } catch (err) {
      if (err instanceof Error) {
        message.error(t('allocationOrg.editFailed', { error: err.message }));
      }
    } finally {
      setEditSaving(false);
    }
  };

  // ==================== Delete handlers ====================
  const handleDelete = async (record: Record<string, unknown>) => {
    try {
      await deleteAllocOrgEntry(record.id as number);
      message.success(t('allocationOrg.deleteSuccess'));
      if (activeTab === 'import') {
        fetchBatchEntries(batchSearch, batchPage, batchPageSize);
      } else {
        fetchExcBatchEntries(excBatchSearch, excBatchPage, excBatchPageSize);
      }
    } catch (err) {
      message.error(t('allocationOrg.deleteFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    }
  };

  // ==================== Delete batch ====================
  const handleDeleteBatch = async (batch: Record<string, any>) => {
    try {
      await deleteAllocOrgBatch(batch.id as number);
      message.success(t('allocationOrg.deleteBatchSuccess'));
      if (activeTab === 'import') {
        if (selectedBatchId === batch.id) {
          setSelectedBatchId(null);
          setBatchEntries([]);
          setBatchTotal(0);
        }
        fetchBatches(importMonth, 'import');
      } else {
        if (excSelectedBatchId === batch.id) {
          setExcSelectedBatchId(null);
          setExcBatchEntries([]);
          setExcBatchTotal(0);
        }
        fetchExcBatches(excMonth);
      }
    } catch (err) {
      message.error(t('allocationOrg.deleteBatchFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    }
  };

  // ==================== Table columns ====================
  // 例外批次条目通用列（号码/用户名称/分机号/部门全路径/一级分行/分摊三列/备注/操作）
  const excBaseColumns = [
    {
      title: t('allocationOrg.colPhoneNumber'), dataIndex: 'phone_number', key: 'phone_number', width: 140,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('dataComparison.usernameCol'), dataIndex: 'username', key: 'username', width: 110,
      render: (v: string) => v || '-',
    },
    {
      title: t('dataComparison.extensionCol'), dataIndex: 'extension', key: 'extension', width: 100, align: 'center' as const,
      render: (v: string) => v || '-',
    },
    {
      title: t('dataComparison.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 240,
      ellipsis: true,
    },
    {
      title: t('allocationOrg.colL1Branch'), dataIndex: 'l1_branch', key: 'l1_branch', width: 100, align: 'center' as const,
    },
    {
      title: t('allocationOrg.colAllocDept'), dataIndex: 'alloc_dept', key: 'alloc_dept', width: 160,
      ellipsis: true,
    },
    {
      title: t('allocationOrg.colOrgCode'), dataIndex: 'org_code', key: 'org_code', width: 85, align: 'center' as const,
      render: (v: string) => v ? <span style={{ fontFamily: 'monospace' }}>{v}</span> : '-',
    },
    {
      title: t('allocationOrg.colCostCenter'), dataIndex: 'cost_center', key: 'cost_center', width: 85, align: 'center' as const,
      render: (v: string) => v ? <span style={{ fontFamily: 'monospace' }}>{v}</span> : '-',
    },
    {
      title: t('allocationOrg.colRemark'), dataIndex: 'remark', key: 'remark', width: 160,
      ellipsis: true,
    },
  ];

  // 操作列（例外批次条目：编辑/删除）
  const excActionColumn = canEdit
    ? [{
      title: t('allocationOrg.colAction'),
      key: 'action',
      width: 160,
      fixed: 'right' as const,
      render: (_: unknown, record: Record<string, unknown>) => (
        <Space size={0}>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleEdit(record)}
          >
            {t('common.edit')}
          </Button>
          <Popconfirm
            title={t('allocationOrg.deleteConfirmTitle')}
            description={t('allocationOrg.deleteConfirmContent')}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
            onConfirm={() => handleDelete(record)}
          >
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
            >
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    }]
    : [];

  // 例外号码清单批次明细列（全部原始条目 + 上月对比差异标记）
  const excBatchColumns = [
    ...excBaseColumns.slice(0, 8),  // 号码~成本中心
    {
      title: t('dataComparison.changedColumnsCol'), dataIndex: 'changed_columns', key: 'changed_columns', width: 150, align: 'center' as const,
      render: (cols: string[]) => {
        if (!Array.isArray(cols) || cols.length === 0) return '-';
        return <span>{cols.map((c: string) => <Tag key={c} color="orange" style={{ marginBottom: 2 }}>{c}</Tag>)}</span>;
      },
    },
    ...excBaseColumns.slice(8),     // 备注
    ...excActionColumn,
  ];

  // 差异数据批次明细列（仅差异条目，附上月对比）
  const excDiffBatchColumns = [
    ...excBaseColumns.slice(0, 8),  // 号码~成本中心
    {
      title: t('allocationOrg.diffPrevMonth', { month: excPrevMonth }), key: 'prev', width: 320, align: 'center' as const,
      render: (_: unknown, record: Record<string, unknown>) => {
        const items = [
          { label: t('dataComparison.usernameCol'), cur: record.username, prev: record.compare_username },
          { label: t('dataComparison.extensionCol'), cur: record.extension, prev: record.compare_extension },
          { label: t('dataComparison.deptPathCol'), cur: record.dept_path, prev: record.compare_dept_path },
        ];
        return (
          <div style={{ textAlign: 'left' }}>
            {items.map((it) => (
              <div key={it.label} style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                <span style={{ color: '#999', width: 64, flexShrink: 0 }}>{it.label}</span>
                <span>{(it.cur as string) || '-'}</span>
                <span style={{ color: '#999' }}>→</span>
                <span style={{ color: '#d46b08' }}>{(it.prev as string) || '-'}</span>
              </div>
            ))}
          </div>
        );
      },
    },
    {
      title: t('dataComparison.changedColumnsCol'), dataIndex: 'changed_columns', key: 'changed_columns', width: 150, align: 'center' as const,
      render: (cols: string[]) => {
        if (!Array.isArray(cols) || cols.length === 0) return '-';
        return <span>{cols.map((c: string) => <Tag key={c} color="orange" style={{ marginBottom: 2 }}>{c}</Tag>)}</span>;
      },
    },
    ...excBaseColumns.slice(8),     // 备注
    ...excActionColumn,
  ];

  const columns = [
    {
      title: t('allocationOrg.colPhoneNumber'),
      dataIndex: 'phone_number',
      key: 'phone_number',
      width: 140,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOrg.colExtension'),
      dataIndex: 'extension',
      key: 'extension',
      width: 120,
      align: 'center' as const,
      render: (v: string) => v || '-',
    },
    {
      title: t('allocationOrg.colDeptPath'),
      dataIndex: 'dept_path',
      key: 'dept_path',
      width: 280,
      ellipsis: true,
    },
    {
      title: t('allocationOrg.colL1Branch'),
      dataIndex: 'l1_branch',
      key: 'l1_branch',
      width: 120,
      align: 'center' as const,
    },
    {
      title: t('allocationOrg.colAllocDept'),
      dataIndex: 'alloc_dept',
      key: 'alloc_dept',
      width: 180,
    },
    {
      title: t('allocationOrg.colOrgCode'),
      dataIndex: 'org_code',
      key: 'org_code',
      width: 80,
      align: 'center' as const,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOrg.colCostCenter'),
      dataIndex: 'cost_center',
      key: 'cost_center',
      width: 80,
      align: 'center' as const,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOrg.colRemark'),
      dataIndex: 'remark',
      key: 'remark',
      width: 200,
      ellipsis: true,
    },
    ...(canEdit && activeTab === 'import'
      ? [{
        title: t('allocationOrg.colAction'),
        key: 'action',
        width: 160,
        fixed: 'right' as const,
        render: (_: unknown, record: Record<string, unknown>) => (
          <Space size={0}>
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => handleEdit(record)}
            >
              {t('common.edit')}
            </Button>
            <Popconfirm
              title={t('allocationOrg.deleteConfirmTitle')}
              description={t('allocationOrg.deleteConfirmContent')}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
              okButtonProps={{ danger: true }}
              onConfirm={() => handleDelete(record)}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
              >
                {t('common.delete')}
              </Button>
            </Popconfirm>
          </Space>
        ),
      }]
      : []),
  ];

  // 批次列表列（import Tab）
  const batchColumns = [
    { title: t('allocationOrg.batchCol'), dataIndex: 'batch_no', key: 'batch_no', width: 200 },
    { title: t('allocationOrg.monthCol'), dataIndex: 'billing_month', key: 'billing_month', width: 120, render: (v: string) => v || '-' },
    { title: t('allocationOrg.totalCountCol'), dataIndex: 'total_count', key: 'total_count', width: 100, align: 'center' as const },
    { title: t('allocationOrg.importTimeCol'), dataIndex: 'created_at', key: 'created_at', width: 170, render: (v: string) => (v ? v.substring(0, 19) : '-') },
    ...(canDeleteBatch
      ? [{
        title: t('allocationOrg.colAction'),
        key: 'action',
        width: 90,
        align: 'center' as const,
        render: (_: unknown, record: Record<string, any>) => (
          <Popconfirm
            title={t('allocationOrg.deleteBatchConfirm')}
            description={t('allocationOrg.deleteBatchDesc', { batchNo: record.batch_no, count: record.total_count })}
            onConfirm={(e) => { e?.stopPropagation(); handleDeleteBatch(record); }}
            onCancel={(e) => e?.stopPropagation()}
            okText={t('allocationOrg.deleteBatch')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
          >
            <Button type="link" danger size="small" icon={<DeleteOutlined />} onClick={(e) => e.stopPropagation()} />
          </Popconfirm>
        ),
      }]
      : []),
  ];

  // ==================== Render: import Tab (月份 + 批次列表 + 批次明细) ====================
  const renderImportTab = () => (
    <>
      {/* 顶部工具栏：月份选择 + 刷新 | 导入下拉 + 导出 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row justify="space-between" align="middle" wrap>
          <Space wrap>
            <Select
              value={importMonth}
              onChange={(v) => setImportMonth(v)}
              placeholder={t('allocationOrg.allMonths')}
              allowClear
              style={{ width: 160 }}
              options={availableMonths.map((m: string) => ({ value: m, label: m }))}
            />
            <Button size="small" icon={<ReloadOutlined />} onClick={() => fetchBatches(importMonth, 'import')}>
              {t('common.refresh')}
            </Button>
          </Space>
          <Space wrap>
            {canEdit && (
              <Dropdown
                menu={{
                  items: [
                    { key: 'import', icon: <UploadOutlined />, label: t('allocationOrg.importLabel'), disabled: uploading },
                    { key: 'download', icon: <DownloadOutlined />, label: t('allocationOrg.downloadTemplate') },
                  ],
                  onClick: ({ key }) => {
                    if (key === 'import') handleImportClick();
                    if (key === 'download') downloadAllocOrgTemplate();
                  },
                }}
              >
                <Button icon={<UploadOutlined />} loading={uploading && !importPolling} disabled={uploading}>
                  {t('allocationOrg.importLabel')}<DownOutlined />
                </Button>
              </Dropdown>
            )}
            <input
              ref={fileInputRef}
              type="file"
              accept=".xlsx,.xls"
              style={{ display: 'none' }}
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) {
                  handleFileSelected(file);
                  e.target.value = '';
                }
              }}
            />
            {importPolling && importProgress && (
              <Progress
                percent={importPercent}
                size="small"
                style={{ width: 160, display: 'inline-block', verticalAlign: 'middle' }}
                format={() => `${importProgress.processed}/${importProgress.total}`}
              />
            )}
            <Button icon={<ExportOutlined />} onClick={handleExport}>
              {t('allocationOrg.export')}
            </Button>
          </Space>
        </Row>
      </Card>

      {/* 批次列表 */}
      <Card size="small" title={t('allocationOrg.batchListTitle')} style={{ marginBottom: 16 }}>
        <Table
          dataSource={batches}
          columns={batchColumns}
          rowKey="id"
          size="small"
          loading={batchesLoading}
          rowClassName={(r: Record<string, any>) => r.id === selectedBatchId ? 'row-selected' : ''}
          onRow={(r: Record<string, any>) => ({ onClick: () => selectBatch(r.id) })}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 700 }}
        />
      </Card>

      {/* 批次明细 */}
      {selectedBatchId != null ? (
        <Card size="small" title={t('allocationOrg.batchDetailTitle')}>
          <Space wrap style={{ marginBottom: 12 }}>
            <Input.Search
              placeholder={t('allocationOrg.searchPlaceholder')}
              style={{ width: 300 }}
              allowClear
              onSearch={(val) => { setBatchSearch(val); setBatchPage(0); fetchBatchEntries(val, 0, batchPageSize); }}
            />
            <span style={{ color: '#999', fontSize: 12 }}>{t('common.paginationTotal', { total: batchTotal })}</span>
          </Space>
          <Table
            dataSource={batchEntries}
            columns={columns}
            rowKey={(record: Record<string, unknown>) => `batch-${record.id}`}
            size="small"
            loading={batchLoading}
            pagination={{
              current: batchPage + 1,
              pageSize: batchPageSize,
              total: batchTotal,
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100'],
              showTotal: (total: number) => t('common.paginationTotal', { total }),
              onChange: (p: number, s: number) => { setBatchPage(p - 1); setBatchPageSize(s); fetchBatchEntries(batchSearch, p - 1, s); },
            }}
            scroll={{ x: 800 }}
          />
        </Card>
      ) : (
        <Card size="small"><div style={{ color: '#999', textAlign: 'center', padding: 24 }}>{t('allocationOrg.noBatchSelected')}</div></Card>
      )}

      {/* 导入进度（跟随批次列表，导入完成后自动刷新批次） */}
      {importPolling && importProgress && importPercent >= 100 && (
        <Card size="small" style={{ marginTop: 16 }}>
          <div style={{ marginBottom: 4 }}>{t('allocationOrg.importProgress')}</div>
          <Progress percent={importPercent} size="small" format={() => `${importProgress.processed}/${importProgress.total}`} status="success" />
          <Button size="small" style={{ marginTop: 8 }} onClick={() => fetchBatches(importMonthRef.current, 'import')}>
            {t('allocationOrg.refreshAfterImport')}
          </Button>
        </Card>
      )}
    </>
  );

  // ==================== Render: 例外清单/差异数据 Tab (月份 + 批次列表 + 批次明细，与号码分摊机构 Tab 架构一致) ====================
  const renderExceptionTab = () => {
    const isDiff = activeTab === 'exceptionDiff';
    return (
      <>
        {/* 顶部工具栏：月份选择 + 刷新 | 导入下拉 + 导出 */}
        <Card size="small" style={{ marginBottom: 16 }}>
          <Row justify="space-between" align="middle" wrap>
            <Space wrap>
              <Select
                value={excMonth}
                onChange={(v) => setExcMonth(v)}
                placeholder={t('allocationOrg.allMonths')}
                allowClear
                style={{ width: 160 }}
                options={availableMonths.map((m: string) => ({ value: m, label: m }))}
              />
              {isDiff && (
                <Button
                  size="small"
                  type="primary"
                  disabled={excSelectedBatchId == null}
                  onClick={openDiffCompare}
                >
                  {t('allocationOrg.diffCompareBtn')}
                </Button>
              )}
              <Button size="small" icon={<ReloadOutlined />} onClick={() => fetchExcBatches(excMonth)}>
                {t('common.refresh')}
              </Button>
            </Space>
            <Space wrap>
              {!isDiff && canEdit && (
                <Dropdown
                  menu={{
                    items: [
                      { key: 'import', icon: <UploadOutlined />, label: t('allocationOrg.importLabel'), disabled: excUploading },
                      { key: 'download', icon: <DownloadOutlined />, label: t('allocationOrg.downloadTemplate') },
                    ],
                    onClick: ({ key }) => {
                      if (key === 'import') handleExcImportClick();
                      if (key === 'download') downloadAllocOrgTemplate('exception');
                    },
                  }}
                >
                  <Button icon={<UploadOutlined />} loading={excUploading && !excImportPolling} disabled={excUploading}>
                    {t('allocationOrg.importLabel')}<DownOutlined />
                  </Button>
                </Dropdown>
              )}
              {excImportPolling && excImportProgress && (
                <Progress
                  percent={excImportPercent}
                  size="small"
                  style={{ width: 160, display: 'inline-block', verticalAlign: 'middle' }}
                  format={() => `${excImportProgress.processed}/${excImportProgress.total}`}
                />
              )}
              <Button icon={<ExportOutlined />} onClick={handleExport}>
                {t('allocationOrg.export')}
              </Button>
            </Space>
          </Row>
        </Card>

        {/* 批次列表（EXC-IMP- 导入批次） */}
        <Card size="small" title={t('allocationOrg.batchListTitle')} style={{ marginBottom: 16 }}>
          <Table
            dataSource={excBatches}
            columns={batchColumns}
            rowKey="id"
            size="small"
            loading={excBatchesLoading}
            rowClassName={(r: Record<string, any>) => r.id === excSelectedBatchId ? 'row-selected' : ''}
            onRow={(r: Record<string, any>) => ({ onClick: () => selectExcBatch(r.id) })}
            pagination={{ pageSize: 10, showSizeChanger: false }}
            scroll={{ x: 700 }}
          />
        </Card>

        {/* 批次明细：全部原始条目（例外清单）/ 差异条目（差异数据，需点对比后生成） */}
        {isDiff ? (
          diffGenerated && excSelectedBatchId != null ? (
            <Card size="small" title={`${t('allocationOrg.batchDetailTitle')}（${t('allocationOrg.diffPrevMonth', { month: excPrevMonth })}})`}>
              <Space wrap style={{ marginBottom: 12 }}>
                <Input.Search
                  placeholder={t('allocationOrg.exceptionSearchPlaceholder')}
                  style={{ width: 300 }}
                  allowClear
                  onSearch={(val) => { setExcBatchSearch(val); setExcBatchPage(0); fetchExcBatchEntries(val, 0, excBatchPageSize); }}
                />
                <span style={{ color: '#999', fontSize: 12 }}>{t('common.paginationTotal', { total: excBatchTotal })}</span>
              </Space>
              <Table
                dataSource={excBatchEntries}
                columns={excDiffBatchColumns}
                rowKey="id"
                size="small"
                loading={excBatchLoading}
                pagination={{
                  current: excBatchPage + 1,
                  pageSize: excBatchPageSize,
                  total: excBatchTotal,
                  showSizeChanger: true,
                  pageSizeOptions: ['20', '50', '100'],
                  showTotal: (total: number) => t('common.paginationTotal', { total }),
                  onChange: (p: number, s: number) => { setExcBatchPage(p - 1); setExcBatchPageSize(s); fetchExcBatchEntries(excBatchSearch, p - 1, s); },
                }}
                scroll={{ x: 1700 }}
              />
            </Card>
          ) : (
            <Card size="small"><div style={{ color: '#999', textAlign: 'center', padding: 24 }}>{excSelectedBatchId != null ? t('allocationOrg.diffSelectBatchFirst') : t('allocationOrg.noBatchSelected')}</div></Card>
          )
        ) : excSelectedBatchId != null ? (
          <Card size="small" title={t('allocationOrg.batchDetailTitle')}>
            <Space wrap style={{ marginBottom: 12 }}>
              <Input.Search
                placeholder={t('allocationOrg.exceptionSearchPlaceholder')}
                style={{ width: 300 }}
                allowClear
                onSearch={(val) => { setExcBatchSearch(val); setExcBatchPage(0); fetchExcBatchEntries(val, 0, excBatchPageSize); }}
              />
              <span style={{ color: '#999', fontSize: 12 }}>{t('common.paginationTotal', { total: excBatchTotal })}</span>
            </Space>
            <Table
              dataSource={excBatchEntries}
              columns={excBatchColumns}
              rowKey="id"
              size="small"
              loading={excBatchLoading}
              pagination={{
                current: excBatchPage + 1,
                pageSize: excBatchPageSize,
                total: excBatchTotal,
                showSizeChanger: true,
                pageSizeOptions: ['20', '50', '100'],
                showTotal: (total: number) => t('common.paginationTotal', { total }),
                onChange: (p: number, s: number) => { setExcBatchPage(p - 1); setExcBatchPageSize(s); fetchExcBatchEntries(excBatchSearch, p - 1, s); },
              }}
              scroll={{ x: 1400 }}
            />
          </Card>
        ) : (
          <Card size="small"><div style={{ color: '#999', textAlign: 'center', padding: 24 }}>{t('allocationOrg.noBatchSelected')}</div></Card>
        )}

        {/* 导入进度（导入完成后自动刷新批次列表） */}
        {excImportPolling && excImportProgress && excImportPercent >= 100 && (
          <Card size="small" style={{ marginTop: 16 }}>
            <div style={{ marginBottom: 4 }}>{t('allocationOrg.importProgress')}</div>
            <Progress percent={excImportPercent} size="small" format={() => `${excImportProgress.processed}/${excImportProgress.total}`} status="success" />
            <Button size="small" style={{ marginTop: 8 }} onClick={() => fetchExcBatches(excMonth)}>
              {t('allocationOrg.refreshAfterImport')}
            </Button>
          </Card>
        )}
      </>
    );
  };

  // ==================== Render ====================
  return (
    <div style={{ padding: 24 }}>
      <Card
        title={t('allocationOrg.title')}
        styles={{ header: { background: COLORS.sageLight } }}
      >
        <Tabs
          activeKey={activeTab}
          onChange={handleTabChange}
          items={[
            {
              key: 'import',
              label: t('allocationOrg.importDataTab'),
            },
            {
              key: 'exception',
              label: t('allocationOrg.exceptionListTab'),
            },
            {
              key: 'exceptionDiff',
              label: t('allocationOrg.exceptionDiffTab'),
            },
          ]}
          style={{ marginBottom: 16 }}
        />

        {activeTab === 'import' ? renderImportTab() : renderExceptionTab()}
      </Card>

      {/* Import month picker modal */}
      <Modal
        title={t('allocationOrg.importMonthTitle')}
        open={importMonthModal}
        onOk={handleConfirmMonth}
        onCancel={() => setImportMonthModal(false)}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        okButtonProps={{ disabled: !importBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('allocationOrg.importMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          format="YYYY-MM"
          value={importBillingMonth ? dayjs(importBillingMonth, 'YYYY-MM') : null}
          onChange={(_, dateString) => {
            const val = typeof dateString === 'string' ? dateString : dateString.format('YYYY-MM');
            setImportBillingMonth(val);
          }}
          allowClear={false}
        />
      </Modal>

      {/* Exception import month picker modal */}
      <input
        ref={excFileInputRef}
        type="file"
        accept=".xlsx,.xls"
        style={{ display: 'none' }}
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) {
            handleExcFileSelected(file);
            e.target.value = '';
          }
        }}
      />
      <Modal
        title={t('allocationOrg.exceptionImportMonthTitle')}
        open={excImportMonthModal}
        onOk={handleExcConfirmMonth}
        onCancel={() => setExcImportMonthModal(false)}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        okButtonProps={{ disabled: !excBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('allocationOrg.exceptionImportMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          format="YYYY-MM"
          value={excBillingMonth ? dayjs(excBillingMonth, 'YYYY-MM') : null}
          onChange={(_, dateString) => {
            const val = typeof dateString === 'string' ? dateString : dateString.format('YYYY-MM');
            setExcBillingMonth(val);
          }}
          allowClear={false}
        />
      </Modal>

      {/* 差异数据 Tab：对比月份选择弹窗 */}
      <Modal
        title={t('allocationOrg.diffCompareTitle')}
        open={diffModalOpen}
        onOk={confirmDiffCompare}
        onCancel={() => setDiffModalOpen(false)}
        okText={t('allocationOrg.diffCompareConfirm')}
        cancelText={t('common.cancel')}
        okButtonProps={{ disabled: !diffModalMonth }}
      >
        <p style={{ marginBottom: 12, color: '#666' }}>{t('allocationOrg.diffCompareHint')}</p>
        <div style={{ marginBottom: 8, fontWeight: 500 }}>{t('allocationOrg.diffCompareMonthLabel')}</div>
        <Select
          value={diffModalMonth}
          onChange={(v) => setDiffModalMonth(v)}
          placeholder={t('allocationOrg.compareMonthPlaceholder')}
          style={{ width: '100%' }}
          options={directoryMonths.map((m: string) => ({ value: m, label: m }))}
        />
      </Modal>

      {/* Edit entry modal */}
      <Modal
        title={t('allocationOrg.editTitle')}
        open={editModalOpen}
        onOk={handleEditSave}
        onCancel={() => setEditModalOpen(false)}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={editSaving}
      >
        <Form form={editForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="phone_number" label={t('allocationOrg.colPhoneNumber')}>
            <Input />
          </Form.Item>
          {activeTab === 'import' ? (
            <>
              <Form.Item label={t('allocationOrg.colExtension')}>
                <Input value={editingEntry?.extension as string || ''} disabled />
              </Form.Item>
              <Form.Item label={t('allocationOrg.colDeptPath')}>
                <Input value={editingEntry?.dept_path as string || ''} disabled />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item name="username" label={t('dataComparison.usernameCol')}>
                <Input />
              </Form.Item>
              <Form.Item name="extension" label={t('allocationOrg.colExtension')}>
                <Input />
              </Form.Item>
              <Form.Item name="dept_path" label={t('allocationOrg.colDeptPath')}>
                <Input />
              </Form.Item>
            </>
          )}
          <Form.Item name="l1_branch" label={t('allocationOrg.colL1Branch')}>
            <Input />
          </Form.Item>
          <Form.Item name="alloc_dept" label={t('allocationOrg.colAllocDept')}>
            <Input />
          </Form.Item>
          <Form.Item name="org_code" label={t('allocationOrg.colOrgCode')}>
            <Input />
          </Form.Item>
          <Form.Item name="cost_center" label={t('allocationOrg.colCostCenter')}>
            <Input />
          </Form.Item>
          <Form.Item name="remark" label={t('allocationOrg.colRemark')}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AllocationOrgPage;
