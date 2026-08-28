import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Tag, Row, Col, message, Input, Button, Space, Select, Modal, Popconfirm, Progress, DatePicker, Statistic } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, ExportOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';

import {
  getOwnershipEntriesByMonth,
  getOwnershipMonths,
  exportOwnership,
  importOwnership,
  getOwnershipProgress,
  downloadOwnershipTemplate,
  syncAllocationOrg,
} from '../api/import';
import { useImportProgress } from '../hooks/useImportProgress';
import { useAuthStore } from '../store/auth';
import type { ImportProgress } from '../types/import';

/**
 * 分摊号码归属页面 — 8列展示，数据来源为导入
 * 列：号码、分机号、一级分行、分摊部门、部门全路径、机构代码、成本中心、例外
 */
const AllocationPhoneOwnership: React.FC = () => {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);

  // ==================== Data state ====================
  const [entries, setEntries] = useState<Record<string, unknown>[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');

  // Statistics
  const [stats, setStats] = useState({ total: 0, used: 0, idle: 0, l1Branches: 0, allocDepts: 0, exceptions: 0 });

  // Month filter
  const [availableMonths, setAvailableMonths] = useState<string[]>([]);
  const [selectedMonth, setSelectedMonth] = useState<string | undefined>(undefined);

  // Import
  const [uploading, setUploading] = useState(false);
  const [importMonthModal, setImportMonthModal] = useState(false);
  const [importBillingMonth, setImportBillingMonth] = useState<string>(dayjs().format('YYYY-MM'));
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Sync allocation org
  const [syncing, setSyncing] = useState(false);

  // Async import progress
  const { progress: importProgress, polling: importPolling, startPolling, percent: importPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(t('allocationOwnership.importSuccess', { total: p.total }));
      fetchMonths();
      if (importBillingMonth) {
        setSelectedMonth(importBillingMonth);
        fetchData(importBillingMonth);
      }
      setUploading(false);
    },
    onError: (p: ImportProgress) => {
      message.error(t('allocationOwnership.importFailed', { error: p.message || t('common.unknown') }));
      setUploading(false);
    },
  });

  // ==================== Fetch months ====================
  const fetchMonths = useCallback(async () => {
    try {
      const months = await getOwnershipMonths();
      setAvailableMonths(months);
      if (months.length > 0 && !selectedMonth) {
        setSelectedMonth(months[months.length - 1]);
      }
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => { fetchMonths(); }, [fetchMonths]);

  // ==================== Fetch entries ====================
  const fetchData = useCallback(async (month: string | undefined, keyword = '', p = 0, size = 50) => {
    if (!month) return;
    setLoading(true);
    try {
      const data = await getOwnershipEntriesByMonth(month, keyword || undefined, p, size);
      setEntries(data.entries || []);
      setTotal(data.total);
      setPage(data.page);
      setPageSize(data.size);
      setStats({
        total: (data as any).stats_total ?? 0,
        used: (data as any).stats_used ?? 0,
        idle: (data as any).stats_idle ?? 0,
        l1Branches: (data as any).stats_l1_branches ?? 0,
        allocDepts: (data as any).stats_alloc_depts ?? 0,
        exceptions: (data as any).stats_exceptions ?? 0,
      });
    } catch {
      message.error(t('allocationOwnership.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (selectedMonth) fetchData(selectedMonth);
  }, [selectedMonth, fetchData]);

  // ==================== Import handlers ====================
  const handleImportClick = () => {
    setImportMonthModal(true);
  };

  const handleConfirmMonth = () => {
    if (!importBillingMonth) {
      message.warning(t('allocationOwnership.selectMonthFirst'));
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
      const result = await importOwnership(file, month);
      startPolling(result.batch_id, getOwnershipProgress);
    } catch (err) {
      message.error(t('allocationOwnership.importFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
      setUploading(false);
    }
  };

  // ==================== Sync allocation org ====================
  const handleSync = async () => {
    if (!selectedMonth) {
      message.warning(t('allocationOwnership.selectMonthFirst'));
      return;
    }
    setSyncing(true);
    try {
      const result = await syncAllocationOrg(selectedMonth);
      message.success(t('allocationOwnership.syncSuccess', {
        updated: result.updated,
        total: result.total,
        month: selectedMonth,
      }));
      fetchData(selectedMonth, appliedSearch, page, pageSize);
    } catch (err) {
      message.error(t('allocationOwnership.syncFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    } finally {
      setSyncing(false);
    }
  };

  // ==================== Export ====================
  const handleExport = () => {
    exportOwnership(selectedMonth);
  };

  // ==================== Search ====================
  const handleSearch = () => {
    setAppliedSearch(search);
    fetchData(selectedMonth, search, 0, pageSize);
  };

  // ==================== Table columns ====================
  const columns = [
    {
      title: t('allocationOwnership.colPhoneNumber'),
      dataIndex: 'phone_number',
      key: 'phone_number',
      width: 140,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOwnership.colExtension'),
      dataIndex: 'extension',
      key: 'extension',
      width: 100,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOwnership.colL1Branch'),
      dataIndex: 'l1_branch',
      key: 'l1_branch',
      width: 120,
    },
    {
      title: t('allocationOwnership.colAllocDept'),
      dataIndex: 'alloc_dept',
      key: 'alloc_dept',
      width: 140,
    },
    {
      title: t('allocationOwnership.colFullPath'),
      dataIndex: 'full_path',
      key: 'full_path',
      width: 200,
      ellipsis: true,
    },
    {
      title: t('allocationOwnership.colOrgCode'),
      dataIndex: 'org_code',
      key: 'org_code',
      width: 100,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOwnership.colCostCenter'),
      dataIndex: 'cost_center',
      key: 'cost_center',
      width: 100,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOwnership.colException'),
      dataIndex: 'is_exception',
      key: 'is_exception',
      width: 70,
      align: 'center' as const,
      render: (v: number) => v === 1
        ? <Tag color="volcano">{t('common.yes')}</Tag>
        : <Tag color="default">{t('common.no')}</Tag>,
    },
  ];

  // ==================== Render ====================
  return (
    <div style={{ padding: 24 }}>
      <Card
        title={t('allocationOwnership.title')}
        styles={{ header: { background: COLORS.sageLight } }}
        extra={
          <Space>
            <Select
              value={selectedMonth}
              onChange={(v) => setSelectedMonth(v)}
              style={{ width: 130 }}
              placeholder={t('allocationOwnership.selectMonth')}
              options={availableMonths.map(m => ({ label: m, value: m }))}
            />
            {canEdit && (
              <>
                <Button
                  icon={<UploadOutlined />}
                  onClick={handleImportClick}
                  loading={uploading && !importPolling}
                  disabled={uploading}
                >
                  {t('allocationOwnership.importLabel')}
                </Button>
                <Button
                  icon={<DownloadOutlined />}
                  onClick={() => downloadOwnershipTemplate()}
                >
                  {t('allocationOwnership.downloadTemplate')}
                </Button>
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
              </>
            )}
            {importPolling && importProgress && (
              <Progress
                percent={importPercent}
                size="small"
                style={{ width: 160, display: 'inline-block', verticalAlign: 'middle' }}
                format={() => `${importProgress.processed}/${importProgress.total}`}
              />
            )}
            {canEdit && (
              <Popconfirm
                title={t('allocationOwnership.syncConfirm', { month: selectedMonth || '' })}
                onConfirm={handleSync}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
              >
                <Button
                  icon={<SyncOutlined />}
                  loading={syncing}
                >
                  {t('allocationOwnership.syncAllocOrg')}
                </Button>
              </Popconfirm>
            )}
            <Button icon={<ExportOutlined />} onClick={handleExport}>
              {t('allocationOwnership.export')}
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => fetchData(selectedMonth, appliedSearch, page, pageSize)}
            >
              {t('common.refresh')}
            </Button>
          </Space>
        }
      >
        {/* Statistics */}
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col><Statistic title={t('allocationOwnership.statsTotal')} value={stats.total} /></Col>
          <Col><Statistic title={t('allocationOwnership.statsUsed')} value={stats.used} valueStyle={{ color: '#3f8600' }} /></Col>
          <Col><Statistic title={t('allocationOwnership.statsIdle')} value={stats.idle} valueStyle={{ color: '#cf1322' }} /></Col>
          <Col><Statistic title={t('allocationOwnership.statsL1Branches')} value={stats.l1Branches} /></Col>
          <Col><Statistic title={t('allocationOwnership.statsAllocDepts')} value={stats.allocDepts} /></Col>
          <Col><Statistic title={t('allocationOwnership.statsExceptions')} value={stats.exceptions} valueStyle={{ color: '#fa8c16' }} /></Col>
        </Row>

        {/* Search */}
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col flex="auto" />
          <Col>
            <Space>
              <Input
                placeholder={t('allocationOwnership.searchPlaceholder')}
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
          columns={columns}
          rowKey="id"
          loading={loading}
          size="small"
          scroll={{ x: 1100 }}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (total: number) => t('common.totalCount', { count: total }),
            onChange: (p, s) => {
              fetchData(selectedMonth, appliedSearch, p - 1, s);
            },
          }}
        />
      </Card>

      {/* Import month picker modal */}
      <Modal
        title={t('allocationOwnership.importMonthTitle')}
        open={importMonthModal}
        onOk={handleConfirmMonth}
        onCancel={() => setImportMonthModal(false)}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        okButtonProps={{ disabled: !importBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('allocationOwnership.importMonthHint')}</p>
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
    </div>
  );
};

export default AllocationPhoneOwnership;
