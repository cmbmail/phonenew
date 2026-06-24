import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Tag, Row, Col, message, Empty, Input, Statistic, Tabs } from 'antd';
import { SearchOutlined, CameraOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { OwnershipBatch, OwnershipEntry, DataSnapshot } from '../types/import';
import { MATCH_LEVEL_MAP } from '../types/import';
import { getOwnershipBatches, getSnapshots, getBillBatches } from '../api/import';
import { apiGet } from '../lib/request';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import type { BillBatch } from '../types/bill';

export default function PhoneNumberOwnership() {
  const { t } = useTranslation();

  // Current data state
  const [batches, setBatches] = useState<OwnershipBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [entries, setEntries] = useState<OwnershipEntry[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');

  // Snapshot state
  const [snapshots, setSnapshots] = useState<DataSnapshot[]>([]);
  const [billBatches, setBillBatches] = useState<BillBatch[]>([]);
  const [snapshotsLoading, setSnapshotsLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('current');
  const [selectedSnapshotMonth, setSelectedSnapshotMonth] = useState<string | null>(null);
  const [snapshotEntries, setSnapshotEntries] = useState<OwnershipEntry[]>([]);
  const [snapshotEntriesLoading, setSnapshotEntriesLoading] = useState(false);
  const [snapshotSearch, setSnapshotSearch] = useState('');

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try { setBatches(await getOwnershipBatches()); } catch { message.error(t('phoneOwnership.fetchFailed')); } finally { setLoading(false); }
  }, [t]);

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { /* silent */ }
  }, []);

  const fetchSnapshots = useCallback(async () => {
    setSnapshotsLoading(true);
    try {
      const [snaps, bills] = await Promise.all([getSnapshots(), getBillBatches()]);
      setSnapshots(snaps);
      setBillBatches(bills);
    } catch { message.error(t('phoneOwnership.snapshotFetchFailed')); } finally { setSnapshotsLoading(false); }
  }, [t]);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  useEffect(() => {
    if (activeTab === 'snapshot') {
      fetchSnapshots();
    }
  }, [activeTab, fetchSnapshots]);

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

  // Snapshot month → bill_batch_id mapping
  const snapshotMonthOptions = useMemo(() => {
    const m = new Map<string, string>();
    snapshots.forEach(s => {
      const bill = billBatches.find(b => b.id === s.bill_batch_id);
      if (bill?.billing_month) m.set(bill.billing_month, bill.billing_month);
    });
    return [...m.keys()].sort().reverse().map(month => ({ label: month, value: month }));
  }, [snapshots, billBatches]);

  // Auto-select first month
  useEffect(() => {
    if (activeTab === 'snapshot' && snapshotMonthOptions.length > 0 && !selectedSnapshotMonth) {
      setSelectedSnapshotMonth(snapshotMonthOptions[0].value);
    }
  }, [activeTab, snapshotMonthOptions, selectedSnapshotMonth]);

  // Fetch snapshot entries when month selected
  useEffect(() => {
    if (activeTab !== 'snapshot' || !selectedSnapshotMonth) return;
    const snap = snapshots.find(s => {
      const bill = billBatches.find(b => b.id === s.bill_batch_id);
      return bill?.billing_month === selectedSnapshotMonth;
    });
    if (!snap?.ownership_batch_id) {
      setSnapshotEntries([]);
      return;
    }
    setSnapshotEntriesLoading(true);
    apiGet<OwnershipEntry[]>(`/import/ownership/entries/${snap.ownership_batch_id}`)
      .then(setSnapshotEntries)
      .catch(() => { message.error(t('phoneOwnership.fetchFailed')); setSnapshotEntries([]); })
      .finally(() => setSnapshotEntriesLoading(false));
  }, [activeTab, selectedSnapshotMonth, snapshots, billBatches, t]);

  const orgMap = useMemo(() => {
    const m = new Map<number, Organization>();
    orgList.forEach(o => m.set(o.id, o));
    return m;
  }, [orgList]);

  const exceptionCount = useMemo(() => entries.filter(e => e.is_exception === 1).length, [entries]);
  const snapshotExceptionCount = useMemo(() => snapshotEntries.filter(e => e.is_exception === 1).length, [snapshotEntries]);

  // Search filter - current data
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

  // Search filter - snapshot data
  const filteredSnapshotEntries = useMemo(() => {
    const kw = snapshotSearch.trim().toLowerCase();
    if (!kw) return snapshotEntries;
    return snapshotEntries.filter(e => {
      const org = e.org_id ? orgMap.get(e.org_id) : null;
      const orgName = org ? org.name : '';
      return String(e.phone_number || '').toLowerCase().includes(kw) ||
        String(e.description || '').toLowerCase().includes(kw) ||
        orgName.toLowerCase().includes(kw);
    });
  }, [snapshotEntries, snapshotSearch, orgMap]);

  // Current snapshot info
  const selectedSnapshot = useMemo(() => {
    if (!selectedSnapshotMonth) return null;
    return snapshots.find(s => {
      const bill = billBatches.find(b => b.id === s.bill_batch_id);
      return bill?.billing_month === selectedSnapshotMonth;
    }) || null;
  }, [selectedSnapshotMonth, snapshots, billBatches]);

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

  const currentDataContent = (
    <>
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
        <Input prefix={<SearchOutlined />} placeholder={t('phoneOwnership.searchPlaceholder')} allowClear value={search}
          onChange={e => setSearch(e.target.value)} style={{ width: 320, marginBottom: 12 }} />
      )}
      {selectedBatchId && filteredEntries.length > 0 ? (
        <Table columns={columns} dataSource={filteredEntries} rowKey="id" size="small" loading={entriesLoading}
          pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => t('common.paginationTotal', { total }) }}
          scroll={{ x: 800 }} />
      ) : (!entriesLoading && <Empty description={t('phoneOwnership.noData')} />)}
    </>
  );

  const snapshotContent = snapshots.length === 0 && !snapshotsLoading ? (
    <Empty description={t('phoneOwnership.snapshotNoData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
  ) : (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <span style={{ marginRight: 8 }}>{t('phoneOwnership.snapshotSelectMonth')}</span>
          <Select style={{ width: 160 } } placeholder={t('phoneOwnership.snapshotMonthPlaceholder')} loading={snapshotsLoading}
            value={selectedSnapshotMonth} onChange={setSelectedSnapshotMonth}
            options={snapshotMonthOptions} />
        </Col>
        {selectedSnapshot && (
          <Col style={{ color: '#888', fontSize: 13 }}>
            {t('phoneOwnership.snapshotBatchInfo', { ownershipBatch: selectedSnapshot.ownership_batch_id ?? '-', matched: selectedSnapshot.matched_count })}
          </Col>
        )}
      </Row>
      {selectedSnapshotMonth && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}><Statistic title={t('phoneOwnership.totalCount')} value={filteredSnapshotEntries.length} /></Col>
          <Col span={4}><Statistic title={t('phoneOwnership.exceptionCount')} value={snapshotExceptionCount} valueStyle={{ color: snapshotExceptionCount > 0 ? '#cf1322' : undefined }} /></Col>
        </Row>
      )}
      {selectedSnapshotMonth && (
        <Input prefix={<SearchOutlined />} placeholder={t('phoneOwnership.searchPlaceholder')} allowClear value={snapshotSearch}
          onChange={e => setSnapshotSearch(e.target.value)} style={{ width: 320, marginBottom: 12 }} />
      )}
      {selectedSnapshotMonth && filteredSnapshotEntries.length > 0 ? (
        <Table columns={columns} dataSource={filteredSnapshotEntries} rowKey="id" size="small" loading={snapshotEntriesLoading}
          pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => t('common.paginationTotal', { total }) }}
          scroll={{ x: 800 }} />
      ) : (!snapshotEntriesLoading && selectedSnapshotMonth && <Empty description={t('phoneOwnership.noData')} />)}
    </>
  );

  return (
    <div>
      <Card>
        <Tabs activeKey={activeTab} onChange={(key) => { setActiveTab(key); if (key === 'snapshot') setSelectedSnapshotMonth(null); }} items={[
          { key: 'current', label: t('phoneOwnership.currentDataTab'), children: currentDataContent },
          { key: 'snapshot', label: <><CameraOutlined /> {t('phoneOwnership.snapshotTab')}</>, children: snapshotContent },
        ]} />
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
