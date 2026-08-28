import { useState, useEffect, useCallback } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Tag, Row, Col, message, Input, Button, Space, Select, Popconfirm } from 'antd';
import { SearchOutlined, ThunderboltOutlined, ExportOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

import {
  getOwnershipEntriesByMonth,
  getOwnershipMonths,
  exportOwnership,
  syncAllocationOrg,
} from '../api/import';
import { apiPost } from '../lib/request';
import { useAuthStore } from '../store/auth';

/**
 * 号码归属页面 — 8列展示（4步匹配自动生成）
 * 列：号码、分机号、一级分行、分摊部门、部门全路径、机构代码、成本中心、例外
 */
const PhoneNumberOwnership: React.FC = () => {
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

  // Month filter
  const [availableMonths, setAvailableMonths] = useState<string[]>([]);
  const [selectedMonth, setSelectedMonth] = useState<string | undefined>(undefined);

  // Generate (4-step matching)
  const [generating, setGenerating] = useState(false);
  const [generateMonth, setGenerateMonth] = useState<string>(new Date().toISOString().slice(0, 7));

  // Sync allocation org
  const [syncing, setSyncing] = useState(false);

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
    } catch {
      message.error(t('phoneOwnership.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (selectedMonth) fetchData(selectedMonth);
  }, [selectedMonth, fetchData]);

  // ==================== Generate handler ====================
  const handleGenerate = async () => {
    setGenerating(true);
    try {
      const result = await apiPost<{ total_count: number; exception_count: number; elapsed_ms: number }>(
        '/import/ownership/generate',
        { billing_month: generateMonth }
      );
      message.success(t('phoneOwnership.generateSuccess', {
        count: result.total_count,
        month: generateMonth,
      }));
      setSelectedMonth(generateMonth);
      fetchMonths();
      fetchData(generateMonth);
    } catch (err) {
      message.error(t('phoneOwnership.generateFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    } finally {
      setGenerating(false);
    }
  };

  // ==================== Sync allocation org ====================
  const handleSync = async () => {
    if (!selectedMonth) {
      message.warning(t('phoneOwnership.selectMonthFirst'));
      return;
    }
    setSyncing(true);
    try {
      const result = await syncAllocationOrg(selectedMonth);
      message.success(t('phoneOwnership.syncSuccess', {
        updated: result.updated,
        total: result.total,
        month: selectedMonth,
      }));
      fetchData(selectedMonth, appliedSearch, page, pageSize);
    } catch (err) {
      message.error(t('phoneOwnership.syncFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    } finally {
      setSyncing(false);
    }
  };

  // ==================== Export ====================
  const handleExport = () => {
    exportOwnership();
  };

  // ==================== Search ====================
  const handleSearch = () => {
    setAppliedSearch(search);
    fetchData(selectedMonth, search, 0, pageSize);
  };

  // ==================== Table columns ====================
  const columns = [
    {
      title: t('phoneOwnership.colPhoneNumber'),
      dataIndex: 'phone_number',
      key: 'phone_number',
      width: 140,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('phoneOwnership.colExtension'),
      dataIndex: 'extension',
      key: 'extension',
      width: 100,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('phoneOwnership.colL1Branch'),
      dataIndex: 'l1_branch',
      key: 'l1_branch',
      width: 120,
    },
    {
      title: t('phoneOwnership.colAllocDept'),
      dataIndex: 'alloc_dept',
      key: 'alloc_dept',
      width: 140,
    },
    {
      title: t('phoneOwnership.colFullPath'),
      dataIndex: 'full_path',
      key: 'full_path',
      width: 200,
      ellipsis: true,
    },
    {
      title: t('phoneOwnership.colOrgCode'),
      dataIndex: 'org_code',
      key: 'org_code',
      width: 100,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('phoneOwnership.colCostCenter'),
      dataIndex: 'cost_center',
      key: 'cost_center',
      width: 100,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('phoneOwnership.colException'),
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
        title={t('phoneOwnership.title')}
        styles={{ header: { background: COLORS.sageLight } }}
        extra={
          <Space>
            <Select
              value={selectedMonth}
              onChange={(v) => setSelectedMonth(v)}
              style={{ width: 130 }}
              placeholder={t('phoneOwnership.selectMonth')}
              options={availableMonths.map(m => ({ label: m, value: m }))}
            />
            {canEdit && (
              <Popconfirm
                title={t('phoneOwnership.generateConfirm', { month: generateMonth })}
                onConfirm={handleGenerate}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
              >
                <Button
                  type="primary"
                  icon={<ThunderboltOutlined />}
                  loading={generating}
                  style={{ background: COLORS.teal, borderColor: COLORS.teal }}
                >
                  {t('phoneOwnership.generate')}
                </Button>
              </Popconfirm>
            )}
            {canEdit && (
              <Popconfirm
                title={t('phoneOwnership.syncConfirm', { month: selectedMonth || '' })}
                onConfirm={handleSync}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
              >
                <Button
                  icon={<SyncOutlined />}
                  loading={syncing}
                >
                  {t('phoneOwnership.syncAllocOrg')}
                </Button>
              </Popconfirm>
            )}
            <Button icon={<ExportOutlined />} onClick={handleExport}>
              {t('phoneOwnership.export')}
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
        {/* Generate month selector + search */}
        <Row gutter={16} style={{ marginBottom: 16 }}>
          {canEdit && (
            <Col>
              <Space>
                <span style={{ color: COLORS.slate, fontSize: 13 }}>{t('phoneOwnership.generateMonth')}:</span>
                <Input
                  type="month"
                  value={generateMonth}
                  onChange={(e) => setGenerateMonth(e.target.value)}
                  style={{ width: 150 }}
                />
              </Space>
            </Col>
          )}
          <Col flex="auto" />
          <Col>
            <Space>
              <Input
                placeholder={t('phoneOwnership.searchPlaceholder')}
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
    </div>
  );
};

export default PhoneNumberOwnership;
