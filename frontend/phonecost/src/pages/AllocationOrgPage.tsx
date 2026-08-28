import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Row, Col, message, Input, Button, Space, Select, Modal, Progress, DatePicker, Tabs, Form, Tag, Popconfirm, Dropdown } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, ExportOutlined, ReloadOutlined, EditOutlined, DeleteOutlined, CheckOutlined, DownOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';

import {
  getAllocOrgEntriesByMonth,
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
  verifyAllocOrgEntry,
  verifyEditAllocOrgEntry,
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import type { ImportProgress } from '../types/import';

type SourceTab = 'import' | 'push';

/**
 * 号码分摊机构页面 — 双 Tab：号码分摊机构（import）/ 待核对号码（push）
 * import Tab：月份 + 批次列表 + 批次明细（与数据录入页结构一致）
 * push Tab：按月份直接查明细（保持现状）
 * 列：号码、一级分行、分摊部门、机构代码、成本中心、备注
 */
const AllocationOrgPage: React.FC = () => {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);
  const canDeleteBatch = useAuthStore((s) => s.role === 1);

  // ==================== Tab state ====================
  const [activeTab, setActiveTab] = useState<SourceTab>('import');

  // ==================== Data state ====================
  // push Tab：按月份直接查明细
  const [entries, setEntries] = useState<Record<string, unknown>[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [appliedChangeType, setAppliedChangeType] = useState('');

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
  const [selectedMonth, setSelectedMonth] = useState<string | undefined>(undefined);

  // Import
  const [uploading, setUploading] = useState(false);
  const [importMonthModal, setImportMonthModal] = useState(false);
  const [importBillingMonth, setImportBillingMonth] = useState<string>(dayjs().format('YYYY-MM'));
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Edit
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingEntry, setEditingEntry] = useState<Record<string, unknown> | null>(null);
  const [editSaving, setEditSaving] = useState(false);
  const [editForm] = Form.useForm();

  // Verify edit (修改分摊部门)
  const [verifyEditOpen, setVerifyEditOpen] = useState(false);
  const [verifyEditingEntry, setVerifyEditingEntry] = useState<Record<string, unknown> | null>(null);
  const [verifyEditSaving, setVerifyEditSaving] = useState(false);
  const [verifyEditForm] = Form.useForm();
  // 修改分摊部门：两级选择（一级分行 → 分摊部门）
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [verifyBranchId, setVerifyBranchId] = useState<number | undefined>(undefined);
  const [verifyDeptOptions, setVerifyDeptOptions] = useState<{ value: string; label: string }[]>([]);

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

  // ==================== Fetch months ====================
  const fetchMonths = useCallback(async (source?: SourceTab) => {
    try {
      const months = await getAllocOrgMonths(source || undefined);
      setAvailableMonths(months);
      if (months.length > 0) {
        if (source === 'import') {
          setImportMonth((prev) => prev || months[0]);
        } else {
          setSelectedMonth((prev) => prev || months[0]);
        }
      }
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => { fetchMonths(activeTab); }, [activeTab, fetchMonths]);

  // 加载组织树（修改分摊部门弹窗使用；后端已按用户数据范围过滤）
  useEffect(() => {
    getOrgTree().then(setOrgList).catch(() => { /* silent */ });
  }, []);

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

  // ==================== Fetch entries (push Tab 按月份) ====================
  const fetchData = useCallback(async (month: string | undefined, source?: SourceTab, keyword = '', p = 0, size = 50, changeType = '') => {
    if (!month) return;
    setLoading(true);
    try {
      const data = await getAllocOrgEntriesByMonth(month, keyword || undefined, p, size, source || undefined, changeType || undefined);
      setEntries(data.entries || []);
      setTotal(data.total);
      setPage(data.page);
      setPageSize(data.size);
    } catch {
      message.error(t('allocationOrg.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (activeTab === 'push' && selectedMonth) {
      fetchData(selectedMonth, 'push', appliedSearch, 0, pageSize, appliedChangeType);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedMonth, activeTab, appliedSearch, appliedChangeType]);

  // ==================== Tab change ====================
  const handleTabChange = (key: string) => {
    const newTab = key as SourceTab;
    setActiveTab(newTab);
    setSearch('');
    setAppliedSearch('');
    setAppliedChangeType('');
    setSelectedMonth(undefined);
    setImportMonth(undefined);
    setEntries([]);
    setTotal(0);
    setPage(0);
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

  // ==================== Export ====================
  const handleExport = () => {
    exportAllocOrg(selectedMonth, activeTab);
  };

  // ==================== Search (push Tab) ====================
  const handleSearch = () => {
    setAppliedSearch(search);
    fetchData(selectedMonth, activeTab, search, 0, pageSize, appliedChangeType);
  };

  // ==================== Edit handlers ====================
  const handleEdit = (record: Record<string, unknown>) => {
    setEditingEntry(record);
    editForm.setFieldsValue({
      phone_number: record.phone_number,
      l1_branch: record.l1_branch,
      alloc_dept: record.alloc_dept,
      org_code: record.org_code,
      cost_center: record.cost_center,
      remark: record.remark,
    });
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
        fetchData(selectedMonth, activeTab, appliedSearch, page, pageSize, appliedChangeType);
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
        fetchData(selectedMonth, activeTab, appliedSearch, page, pageSize, appliedChangeType);
      }
    } catch (err) {
      message.error(t('allocationOrg.deleteFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    }
  };

  // ==================== Delete batch (import Tab) ====================
  const handleDeleteBatch = async (batch: Record<string, any>) => {
    try {
      await deleteAllocOrgBatch(batch.id as number);
      message.success(t('allocationOrg.deleteBatchSuccess'));
      if (selectedBatchId === batch.id) {
        setSelectedBatchId(null);
        setBatchEntries([]);
        setBatchTotal(0);
      }
      fetchBatches(importMonth, 'import');
    } catch (err) {
      message.error(t('allocationOrg.deleteBatchFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    }
  };

  // ==================== Verify handlers ====================
  const handleVerifyConfirm = async (record: Record<string, unknown>) => {
    try {
      await verifyAllocOrgEntry(record.id as number);
      message.success(t('allocationOrg.verifySuccess'));
      fetchData(selectedMonth, activeTab, appliedSearch, page, pageSize, appliedChangeType);
    } catch {
      message.error(t('allocationOrg.verifyFailed'));
    }
  };

  const handleVerifyEditOpen = (record: Record<string, unknown>) => {
    setVerifyEditingEntry(record);
    // 预填：优先匹配记录中 l1_branch 对应的分行，其次匹配 alloc_dept 所在分行
    const l1 = (record.l1_branch as string) || '';
    const allocDept = (record.alloc_dept as string) || '';
    let preselectBranchId: number | undefined;
    if (l1) {
      const match = orgList.find(o => o.type === 2 && o.name === l1);
      if (match) preselectBranchId = match.id;
    }
    if (!preselectBranchId) {
      // 尝试通过 alloc_dept 前缀匹配分行（分行下包含该部门路径）
      const match = orgList.find(o => o.type === 2 && allocDept.startsWith(o.name));
      if (match) preselectBranchId = match.id;
    }
    setVerifyBranchId(preselectBranchId);
    // 构建该分行下的分摊部门选项
    const branch = preselectBranchId != null ? orgList.find(o => o.id === preselectBranchId) : undefined;
    const options = buildDeptOptions(branch);
    setVerifyDeptOptions(options);
    verifyEditForm.setFieldsValue({
      branch_id: preselectBranchId,
      alloc_dept: options.length > 0 ? allocDept : allocDept,
    });
    setVerifyEditOpen(true);
  };

  /** 构建某分行下的分摊部门选项：部门（type=4）及子机构；额外加一个"自定义"输入选项 */
  const buildDeptOptions = (branch: Organization | undefined): { value: string; label: string }[] => {
    if (!branch) return [];
    const branchPath = branch.path;
    // 取该分行子树下所有机构（含部门、支行、子部门），排除分行自身
    const children = orgList.filter(o =>
      o.path && branchPath && o.path.startsWith(branchPath) && o.id !== branch.id
    );
    // 按 path 深度排序，保证上级在前
    const depth = (o: Organization) => (o.path ? o.path.split('/').length : 0);
    const sorted = [...children].sort((a, b) => depth(a) - depth(b));
    return sorted.map(o => ({ value: o.name, label: o.name }));
  };

  const handleVerifyBranchChange = (branchId: number) => {
    setVerifyBranchId(branchId);
    const branch = orgList.find(o => o.id === branchId);
    const options = buildDeptOptions(branch);
    setVerifyDeptOptions(options);
    // 清空已选分摊部门
    verifyEditForm.setFieldsValue({ alloc_dept: undefined });
  };

  const handleVerifyEditSave = async () => {
    if (!verifyEditingEntry) return;
    try {
      const values = await verifyEditForm.validateFields();
      setVerifyEditSaving(true);
      await verifyEditAllocOrgEntry(verifyEditingEntry.id as number, {
        alloc_dept: values.alloc_dept || '',
        branch_id: values.branch_id,
      });
      message.success(t('allocationOrg.verifySuccess'));
      setVerifyEditOpen(false);
      if (activeTab === 'import') {
        fetchBatchEntries(batchSearch, batchPage, batchPageSize);
      } else {
        fetchData(selectedMonth, activeTab, appliedSearch, page, pageSize, appliedChangeType);
      }
    } catch (err) {
      if (err instanceof Error) {
        message.error(t('allocationOrg.verifyFailed'));
      }
    } finally {
      setVerifyEditSaving(false);
    }
  };

  // ==================== Table columns ====================
  // 差异推送数据 Tab 列（与数据对比差异数据 Tab 一致）
  const TYPE_COLORS: Record<string, string> = {
    added: 'green',
    removed: 'red',
    changed: 'orange',
    exception: 'blue',
  };

  const pushColumns = [
    {
      title: t('dataComparison.typeCol'), dataIndex: 'change_type', key: 'change_type', width: 80, align: 'center' as const,
      render: (type: string) => {
        const labels: Record<string, string> = {
          added: t('dataComparison.typeAdded'),
          removed: t('dataComparison.typeRemoved'),
          changed: t('dataComparison.typeChanged'),
          exception: t('dataComparison.exceptionDataTab'),
        };
        return <Tag color={TYPE_COLORS[type] || 'default'}>{labels[type] || type || '-'}</Tag>;
      },
    },
    { title: t('dataComparison.usernameCol'), dataIndex: 'username', key: 'username', width: 120 },
    {
      title: t('dataComparison.extensionCol'), dataIndex: 'extension', key: 'extension', width: 120, align: 'center' as const,
      render: (v: string) => v || '-',
    },
    {
      title: t('dataComparison.phoneNumberCol'), dataIndex: 'phone_number', key: 'phone_number', width: 140,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('dataComparison.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 280,
      ellipsis: true,
    },
    {
      title: t('dataComparison.changedColumnsCol'), dataIndex: 'changed_columns', key: 'changed_columns', width: 140, align: 'center' as const,
      render: (cols: string[], record: Record<string, unknown>) => {
        if (!Array.isArray(cols) || cols.length === 0) return '-';
        const colLabels: Record<string, string> = {
          '用户名称': t('dataComparison.usernameCol'),
          '号码': t('dataComparison.phoneNumberCol'),
          '部门全路径': t('dataComparison.deptPathCol'),
          '最新通讯录未找到': t('dataComparison.notFoundCol'),
        };
        return <span>{cols.map((c: string) => <Tag key={c} color="orange" style={{ marginBottom: 2 }}>{colLabels[c] || c}</Tag>)}</span>;
      },
    },
    {
      title: t('allocationOrg.verifyCol'), key: 'verify', width: 140, align: 'center' as const, fixed: 'right' as const,
      render: (_: unknown, record: Record<string, unknown>) => {
        if (record.verified) {
          return <Tag color="green" icon={<CheckOutlined />}>{t('allocationOrg.verifySuccess')}</Tag>;
        }
        return (
          <Space size="small">
            <Button type="link" size="small" icon={<CheckOutlined />} onClick={() => handleVerifyConfirm(record)}>
              {t('allocationOrg.verifyConfirm')}
            </Button>
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleVerifyEditOpen(record)}>
              {t('allocationOrg.verifyEditDept')}
            </Button>
          </Space>
        );
      },
    },
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
              placeholder={t('allocationOrg.selectMonth')}
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
            <Button icon={<ExportOutlined />} onClick={() => exportAllocOrg(importMonth, 'import')}>
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

  // ==================== Render: push Tab (保持现状：按月份直接查明细) ====================
  const renderPushTab = () => (
    <>
      {/* Search */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col flex="auto" />
        <Col>
          <Space>
            <Select
              value={appliedChangeType || undefined}
              onChange={(v) => setAppliedChangeType(v || '')}
              placeholder={t('dataComparison.typeCol')}
              style={{ width: 120 }}
              allowClear
              options={[
                { value: 'added', label: t('dataComparison.typeAdded') },
                { value: 'removed', label: t('dataComparison.typeRemoved') },
                { value: 'changed', label: t('dataComparison.typeChanged') },
                { value: 'exception', label: t('dataComparison.exceptionDataTab') },
              ]}
            />
            <Input
              placeholder={t('allocationOrg.searchPlaceholder')}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onPressEnter={handleSearch}
              prefix={<SearchOutlined />}
              style={{ width: 220 }}
              allowClear
            />
            <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
              {t('common.search')}
            </Button>
          </Space>
        </Col>
      </Row>

      <Table
        dataSource={entries}
        columns={pushColumns}
        rowKey="id"
        loading={loading}
        size="small"
        scroll={{ x: 800 }}
        pagination={{
          current: page + 1,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (total: number) => t('common.totalCount', { count: total }),
          onChange: (p, s) => {
            fetchData(selectedMonth, activeTab, appliedSearch, p - 1, s, appliedChangeType);
          },
        }}
      />
    </>
  );

  // ==================== Render ====================
  return (
    <div style={{ padding: 24 }}>
      <Card
        title={t('allocationOrg.title')}
        styles={{ header: { background: COLORS.sageLight } }}
        extra={
          <Space>
            {activeTab === 'push' ? (
              <>
                <Select
                  value={selectedMonth}
                  onChange={(v) => setSelectedMonth(v)}
                  style={{ width: 130 }}
                  placeholder={t('allocationOrg.selectMonth')}
                  options={availableMonths.map(m => ({ label: m, value: m }))}
                />
                <Button icon={<ExportOutlined />} onClick={handleExport}>
                  {t('allocationOrg.export')}
                </Button>
              </>
            ) : null}
          </Space>
        }
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
              key: 'push',
              label: t('allocationOrg.pushDataTab'),
            },
          ]}
          style={{ marginBottom: 16 }}
        />

        {activeTab === 'import' ? renderImportTab() : renderPushTab()}
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

      {/* Verify edit (修改分摊部门) modal */}
      <Modal
        title={t('allocationOrg.verifyEditTitle')}
        open={verifyEditOpen}
        onOk={handleVerifyEditSave}
        onCancel={() => setVerifyEditOpen(false)}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={verifyEditSaving}
      >
        <p style={{ marginBottom: 12, color: '#999' }}>{t('allocationOrg.verifyEditHint')}</p>
        <Form form={verifyEditForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item
            name="branch_id"
            label={t('allocationOrg.verifyEditBranchLabel')}
            rules={[{ required: true, message: t('allocationOrg.verifyEditBranchRequired') }]}
          >
            <Select
              placeholder={t('allocationOrg.verifyEditBranchPlaceholder')}
              onChange={handleVerifyBranchChange}
              options={orgList.filter(o => o.type === 2).map(o => ({ value: o.id, label: o.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item
            name="alloc_dept"
            label={t('allocationOrg.verifyEditDeptLabel')}
            rules={[{ required: true, message: t('allocationOrg.verifyEditDeptRequired') }]}
          >
            <Select
              placeholder={t('allocationOrg.verifyEditDeptPlaceholder')}
              options={verifyDeptOptions}
              showSearch
              optionFilterProp="label"
              disabled={verifyBranchId == null}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AllocationOrgPage;