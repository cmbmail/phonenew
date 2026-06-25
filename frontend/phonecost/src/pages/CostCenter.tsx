import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Tag, Row, Col, message, Empty, Input, Statistic, Button } from 'antd';
import { SearchOutlined, DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getOrgTree } from '../api/org';
import { getCostCenterMappingUrl } from '../api/allocation';
import { getBillBatches } from '../api/import';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS, ORG_TYPE_OPTIONS } from '../types/organization';

export default function CostCenter() {
  const { t } = useTranslation();

  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [orgTypeFilter, setOrgTypeFilter] = useState<number | null>(null);

  const fetchOrgs = useCallback(async () => {
    setLoading(true);
    try { setOrgList(await getOrgTree()); } catch { message.error(t('costCenter.fetchFailed')); } finally { setLoading(false); }
  }, [t]);

  const fetchBatches = useCallback(async () => {
    try {
      const data = await getBillBatches();
      if (data.length > 0 && !selectedBatchId) {
        const sorted = [...data].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
        setSelectedBatchId(sorted[0].id);
      }
    } catch { /* silent */ }
  }, [selectedBatchId]);

  useEffect(() => { fetchOrgs(); fetchBatches(); }, [fetchOrgs, fetchBatches]);

  const orgMap = useMemo(() => {
    const m = new Map<number, Organization>();
    orgList.forEach(o => m.set(o.id, o));
    return m;
  }, [orgList]);

  // 扁平化组织列表（排除集团），带上完整路径
  const flatOrgs = useMemo(() => {
    return orgList
      .filter(o => o.type !== 1)
      .map(o => ({
        ...o,
        full_path: buildFullOrgPath(o.id, orgMap),
        parent_name: o.parent_id ? orgMap.get(o.parent_id)?.name || '' : '',
      }));
  }, [orgList, orgMap]);

  // 筛选
  const filteredOrgs = useMemo(() => {
    let result = flatOrgs;
    if (orgTypeFilter !== null) {
      result = result.filter(o => o.type === orgTypeFilter);
    }
    const kw = search.trim().toLowerCase();
    if (kw) {
      result = result.filter(o =>
        o.name.toLowerCase().includes(kw) ||
        (o.code || '').toLowerCase().includes(kw) ||
        o.full_path.toLowerCase().includes(kw)
      );
    }
    return result.sort((a, b) => {
      const ta = a.type || 99, tb = b.type || 99;
      return ta !== tb ? ta - tb : a.name.localeCompare(b.name);
    });
  }, [flatOrgs, orgTypeFilter, search]);

  const costCenterCount = useMemo(() => flatOrgs.filter(o => o.code).length, [flatOrgs]);
  const branchCount = useMemo(() => flatOrgs.filter(o => o.type === 2).length, [flatOrgs]);
  const deptCount = useMemo(() => flatOrgs.filter(o => o.type === 4).length, [flatOrgs]);

  const orgTypeLabel = (type: number) => ORG_TYPE_LABELS[type] || '-';

  const columns = [
    { title: t('costCenter.typeCol'), key: 'type', width: 90, render: (_: unknown, r: typeof flatOrgs[0]) => <Tag>{orgTypeLabel(r.type)}</Tag> },
    { title: t('costCenter.nameCol'), dataIndex: 'name', key: 'name', width: 160 },
    { title: t('costCenter.fullPathCol'), dataIndex: 'full_path', key: 'full_path', width: 260, ellipsis: true },
    { title: t('costCenter.codeCol'), dataIndex: 'code', key: 'code', width: 120, render: (v: string) => v || <span style={{ color: '#999' }}>-</span> },
    { title: t('costCenter.parentCol'), dataIndex: 'parent_name', key: 'parent_name', width: 140, render: (v: string) => v || '-' },
  ];

  return (
    <div>
      <Card>
        <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <span style={{ marginRight: 8 }}>{t('costCenter.typeFilter')}</span>
            <Select style={{ width: 140 }} allowClear placeholder={t('costCenter.typeFilterPlaceholder')}
              value={orgTypeFilter} onChange={setOrgTypeFilter}
              options={ORG_TYPE_OPTIONS.filter(o => o.value !== 1)} />
          </Col>
          <Col>
            {selectedBatchId && (
              <Button icon={<DownloadOutlined />}
                onClick={() => window.open(getCostCenterMappingUrl(selectedBatchId), '_blank', 'noopener,noreferrer')}>
                {t('costCenter.export')}
              </Button>
            )}
          </Col>
        </Row>

        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}><Statistic title={t('costCenter.totalOrgs')} value={flatOrgs.length} /></Col>
          <Col span={4}><Statistic title={t('costCenter.branchCount')} value={branchCount} /></Col>
          <Col span={4}><Statistic title={t('costCenter.deptCount')} value={deptCount} /></Col>
          <Col span={4}><Statistic title={t('costCenter.costCenterCount')} value={costCenterCount} /></Col>
        </Row>

        <Input
          prefix={<SearchOutlined />}
          placeholder={t('costCenter.searchPlaceholder')}
          allowClear
          value={search}
          onChange={e => setSearch(e.target.value)}
          style={{ width: 320, marginBottom: 12 }}
        />

        {filteredOrgs.length > 0 ? (
          <Table
            columns={columns}
            dataSource={filteredOrgs}
            rowKey="id"
            size="small"
            loading={loading}
            pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => t('common.paginationTotal', { total }) }}
            scroll={{ x: 780 }}
          />
        ) : (
          !loading && <Empty description={t('costCenter.noData')} />
        )}
      </Card>
    </div>
  );
}

function buildFullOrgPath(orgId: number, orgMap: Map<number, Organization>): string {
  const names: string[] = [];
  const visited = new Set<number>();
  let org = orgMap.get(orgId);
  while (org && !visited.has(org.id)) {
    if (org.type === 1) break;
    names.unshift(org.name);
    visited.add(org.id);
    if (!org.parent_id) break;
    org = orgMap.get(org.parent_id);
  }
  return names.join('/');
}
