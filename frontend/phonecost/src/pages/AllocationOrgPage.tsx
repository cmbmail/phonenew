import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Row, Col, message, Input, Button, Space, Select, Modal, Progress, DatePicker, Tabs, Form, Tag, Popconfirm, Dropdown } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, ExportOutlined, ReloadOutlined, EditOutlined, DeleteOutlined, DownOutlined } from '@ant-design/icons';
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
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';
import type { ImportProgress } from '../types/import';

type SourceTab = 'import' | 'exception' | 'exceptionDiff';

/**
 * 号码分摊机构页面 — 三 Tab：
 * 1) 号码分摊机构（import）：月份 + 批次列表 + 批次明细（含导入 ALLOC-ORG-、通讯录差异推送 COMP-、分行号码推送 BRN- 批次）
 *    分机号/部门全路径：同月通讯录按号码实时匹配；分摊部门/机构代码/成本中心：分摊机构对照表实时匹配
 * 2) 例外号码清单（exception）：数据对比页推送的例外号码（PUSH-EXC-），仅显示与上一自然月通讯录无差异的号码
 * 3) 差异数据（exceptionDiff）：例外清单中与上一自然月通讯录有差异的号码（用户名称/分机号/部门全路径），附上月值对比
 */
const AllocationOrgPage: React.FC = () => {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);
  const canDeleteBatch = useAuthStore((s) => s.role === 1);

  // ==================== Tab state ====================
  const [activeTab, setActiveTab] = useState<SourceTab>('import');

  // ==================== Data state ====================
  // 例外清单/差异数据 Tab：按月份查询
  const [entries, setEntries] = useState<Record<string, unknown>[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [prevMonth, setPrevMonth] = useState('');

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
      const months = await getAllocOrgMonths(source === 'import' ? 'import' : (source === 'exception' || source === 'exceptionDiff') ? 'exception' : undefined);
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

  // ==================== Fetch entries (例外清单/差异数据 Tab 按月份) ====================
  const fetchData = useCallback(async (month: string | undefined, source?: SourceTab, keyword = '', p = 0, size = 50) => {
    if (!month) return;
    setLoading(true);
    try {
      const data = await getAllocOrgEntriesByMonth(
        month, keyword || undefined, p, size,
        source === 'exception' ? 'exception' : source === 'exceptionDiff' ? 'exception-diff' : undefined,
      );
      setEntries(data.entries || []);
      setTotal(data.total);
      setPage(data.page);
      setPageSize(data.size);
      setPrevMonth((data as Record<string, unknown>).prev_month as string || '');
    } catch {
      message.error(t('allocationOrg.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if ((activeTab === 'exception' || activeTab === 'exceptionDiff') && selectedMonth) {
      fetchData(selectedMonth, activeTab, appliedSearch, 0, pageSize);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedMonth, activeTab, appliedSearch]);

  // ==================== Tab change ====================
  const handleTabChange = (key: string) => {
    const newTab = key as SourceTab;
    setActiveTab(newTab);
    setSearch('');
    setAppliedSearch('');
    setSelectedMonth(undefined);
    setImportMonth(undefined);
    setEntries([]);
    setTotal(0);
    setPage(0);
    setPrevMonth('');
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
    exportAllocOrg(selectedMonth, activeTab === 'exception' ? 'exception' : activeTab === 'exceptionDiff' ? 'exception-diff' : 'import');
  };

  // ==================== Search (例外清单/差异数据 Tab) ====================
  const handleSearch = () => {
    setAppliedSearch(search);
    fetchData(selectedMonth, activeTab, search, 0, pageSize);
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
      fetchBatchEntries(batchSearch, batchPage, batchPageSize);
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
      fetchBatchEntries(batchSearch, batchPage, batchPageSize);
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

  // ==================== Table columns ====================
  // 例外号码清单 Tab 列
  const exceptionColumns = [
    {
      title: t('dataComparison.usernameCol'), dataIndex: 'username', key: 'username', width: 120,
    },
    {
      title: t('dataComparison.extensionCol'), dataIndex: 'extension', key: 'extension', width: 110, align: 'center' as const,
      render: (v: string) => v || '-',
    },
    {
      title: t('dataComparison.phoneNumberCol'), dataIndex: 'phone_number', key: 'phone_number', width: 140,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('dataComparison.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 300,
      ellipsis: true,
    },
    {
      title: t('allocationOrg.colL1Branch'), dataIndex: 'l1_branch', key: 'l1_branch', width: 110, align: 'center' as const,
    },
    {
      title: t('allocationOrg.colRemark'), dataIndex: 'remark', key: 'remark', width: 180,
      ellipsis: true,
    },
  ];

  // 差异数据 Tab 列（与例外清单对比上月通讯录）
  const exceptionDiffColumns = [
    {
      title: t('dataComparison.usernameCol'), dataIndex: 'username', key: 'username', width: 110,
    },
    {
      title: t('dataComparison.extensionCol'), dataIndex: 'extension', key: 'extension', width: 100, align: 'center' as const,
      render: (v: string) => v || '-',
    },
    {
      title: t('dataComparison.phoneNumberCol'), dataIndex: 'phone_number', key: 'phone_number', width: 130,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('dataComparison.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 240,
      ellipsis: true,
    },
    {
      title: t('allocationOrg.diffPrevMonth', { month: prevMonth }), key: 'prev', width: 320, align: 'center' as const,
      render: (_: unknown, record: Record<string, unknown>) => {
        const items = [
          { label: t('dataComparison.usernameCol'), cur: record.username, prev: record.prev_username },
          { label: t('dataComparison.extensionCol'), cur: record.extension, prev: record.prev_extension },
          { label: t('dataComparison.deptPathCol'), cur: record.dept_path, prev: record.prev_dept_path },
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

  // ==================== Render: 例外清单/差异数据 Tab (按月份查询) ====================
  const renderExceptionTab = () => (
    <>
      {/* Search */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col flex="auto" />
        <Col>
          <Space>
            <Input
              placeholder={t('allocationOrg.exceptionSearchPlaceholder')}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onPressEnter={handleSearch}
              prefix={<SearchOutlined />}
              style={{ width: 260 }}
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
        columns={activeTab === 'exceptionDiff' ? exceptionDiffColumns : exceptionColumns}
        rowKey="id"
        loading={loading}
        size="small"
        scroll={{ x: activeTab === 'exceptionDiff' ? 1000 : 800 }}
        pagination={{
          current: page + 1,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (total: number) => t('common.totalCount', { count: total }),
          onChange: (p, s) => {
            fetchData(selectedMonth, activeTab, appliedSearch, p - 1, s);
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
            {activeTab !== 'import' ? (
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
          <Form.Item label={t('allocationOrg.colExtension')}>
            <Input value={editingEntry?.extension as string || ''} disabled />
          </Form.Item>
          <Form.Item label={t('allocationOrg.colDeptPath')}>
            <Input value={editingEntry?.dept_path as string || ''} disabled />
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
    </div>
  );
};

export default AllocationOrgPage;
