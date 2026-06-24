import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Tag, Descriptions, Row, Col, message, Empty, Input, Statistic } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { OwnershipBatch, OwnershipEntry } from '../types/import';
import { MATCH_LEVEL_MAP } from '../types/import';
import { getOwnershipBatches } from '../api/import';
import { apiGet } from '../lib/request';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS } from '../types/organization';

export default function PhoneNumberOwnership() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<OwnershipBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [entries, setEntries] = useState<OwnershipEntry[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try { setBatches(await getOwnershipBatches()); } catch { message.error(t('phoneOwnership.fetchFailed')); } finally { setLoading(false); }
  }, [t]);

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { /* silent */ }
  }, []);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.id - a.id);
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  useEffect(() => {
    if (selectedBatchId) {
      setEntriesLoading(true);
      apiGet<OwnershipEntry[]>(`/import/ownership/entries/${selectedBatchId}`)
        .then(setEntries)
        .catch(() => message.error(t('phoneOwnership.fetchFailed')))
        .finally(() => setEntriesLoading(false));
    }
  }, [selectedBatchId, t]);

  const orgMap = useMemo(() => {
    const m = new Map<number, Organization>();
    orgList.forEach(o => m.set(o.id, o));
    return m;
  }, [orgList]);

  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  const exceptionCount = useMemo(() => entries.filter(e => e.is_exception === 1).length, [entries]);

  // 搜索过滤
  const filteredEntries = useMemo(() => {
    const kw = search.trim().toLowerCase();
    if (!kw) return entries;
    return entries.filter(e => {
      const org = e.org_id ? orgMap.get(e.org_id) : null;
      const orgName = org ? org.name : '';
      return String(e.phone_number || '').toLowerCase().includes(kw) ||
        String(e.description || '').toLowerCase().includes(kw) ||
        orgName.toLowerCase().includes(kw);
    });
  }, [entries, search, orgMap]);

  const columns = [
    { title: t('phoneOwnership.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 130, fixed: 'left' as const },
    { title: t('phoneOwnership.descCol'), dataIndex: 'description', key: 'description', width: 150, ellipsis: true },
    {
      title: t('phoneOwnership.exceptionCol'), dataIndex: 'is_exception', key: 'is_exception', width: 80,
      render: (v: number) => v === 1 ? <Tag color="red">P0</Tag> : '-',
    },
    {
      title: t('phoneOwnership.orgCol'), key: 'org_name', width: 200,
      render: (_: unknown, r: OwnershipEntry) => {
        if (!r.org_id) return <span style={{ color: '#999' }}>-</span>;
        const org = orgMap.get(r.org_id);
        return org ? buildFullOrgPath(r.org_id, orgMap) : String(r.org_id);
      },
    },
    {
      title: t('phoneOwnership.costCenterCol'), key: 'cost_center', width: 100,
      render: (_: unknown, r: OwnershipEntry) => {
        if (!r.org_id) return '-';
        const org = orgMap.get(r.org_id);
        return org?.code || '-';
      },
    },
    {
      title: t('phoneOwnership.matchLevelCol'), dataIndex: 'match_level', key: 'match_level', width: 100,
      render: (v: string) => {
        const info = MATCH_LEVEL_MAP[v];
        return info ? <Tag color={info.color}>{info.label}</Tag> : (v || '-');
      },
    },
  ];

  return (
    <div>
      <Card>
        <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <span style={{ marginRight: 8 }}>{t('phoneOwnership.selectBatch')}</span>
            <Select style={{ width: 280 }} placeholder={t('phoneOwnership.selectBatchPlaceholder')} loading={loading}
              value={selectedBatchId} onChange={setSelectedBatchId}
              options={[...batches].sort((a, b) => b.id - a.id).map(b => ({ label: `${b.batch_no} (${b.total_count}条)`, value: b.id }))} />
          </Col>
        </Row>

        {selectedBatchId && (
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={4}><Statistic title={t('phoneOwnership.totalCount')} value={filteredEntries.length} /></Col>
            <Col span={4}><Statistic title={t('phoneOwnership.exceptionCount')} value={exceptionCount} valueStyle={{ color: exceptionCount > 0 ? '#cf1322' : undefined }} /></Col>
          </Row>
        )}

        {selectedBatchId && (
          <Input
            prefix={<SearchOutlined />}
            placeholder={t('phoneOwnership.searchPlaceholder')}
            allowClear
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ width: 320, marginBottom: 12 }}
          />
        )}

        {selectedBatchId && filteredEntries.length > 0 ? (
          <Table
            columns={columns}
            dataSource={filteredEntries}
            rowKey="id"
            size="small"
            loading={entriesLoading}
            pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => t('common.paginationTotal', { total }) }}
            scroll={{ x: 800 }}
          />
        ) : (
          !entriesLoading && <Empty description={t('phoneOwnership.noData')} />
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
