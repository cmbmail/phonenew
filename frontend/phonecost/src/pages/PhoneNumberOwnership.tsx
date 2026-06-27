import { useState, useEffect, useCallback, useMemo } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Select, Tag, Row, Col, message, Empty, Input, Statistic, Tabs } from 'antd';
import { SearchOutlined, CameraOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { OwnershipBatch, OwnershipEntry, DataSnapshot } from '../types/import';
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
  const [directoryOrgMap, setDirectoryOrgMap] = useState<Record<string, number>>({});
  const [directoryInfoMap, setDirectoryInfoMap] = useState<Record<string, { username: string; extension: string }>>({});
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
      apiGet<{ entries: OwnershipEntry[]; directoryOrgMap: Record<string, number>; directoryInfoMap: Record<string, { username: string; extension: string }> }>(`/import/ownership/entries/${selectedBatchId}`)
        .then(res => {
          setEntries(res.entries);
          setDirectoryOrgMap(res.directoryOrgMap || {});
          setDirectoryInfoMap(res.directoryInfoMap || {});
        })
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

  const LEVEL_LABELS = ['集团', '一级分行', '二级分行/部门', '三级部门', '四级部门'];

  /** Walk org tree from leaf to root, return [集团, 一级分行, 二级分行/部门, 三级部门, 四级部门] names */
  const getOrgLevels = useCallback((orgId: number | null): string[] => {
    const levels: string[] = ['', '', '', '', ''];
    if (!orgId) return levels;
    const visited = new Set<number>();
    let cur = orgMap.get(orgId);
    // Walk up and collect names from deepest to shallowest, skipping 集团(type=1) as a named level
    const stack: string[] = [];
    while (cur && !visited.has(cur.id)) {
      visited.add(cur.id);
      if (cur.type !== 1) stack.push(cur.name);
      if (!cur.parent_id) break;
      cur = orgMap.get(cur.parent_id);
    }
    // stack is [leaf, ..., branch], need to map into 5 slots from bottom up
    // levels[0]=集团名, levels[1]=一级分行, levels[2]=二级分行/部门, levels[3]=三级部门, levels[4]=四级部门
    // We fill from the deepest level upward
    for (let i = 0; i < stack.length && i < 5; i++) {
      levels[4 - i] = stack[i];
    }
    // The remaining top levels should be the ones above — but our org tree might have varying depth
    // Let's do it properly: collect from root to leaf
    const chain: string[] = [];
    cur = orgMap.get(orgId);
    visited.clear();
    while (cur && !visited.has(cur.id)) {
      visited.add(cur.id);
      chain.unshift(cur.name);
      if (!cur.parent_id) break;
      cur = orgMap.get(cur.parent_id);
    }
    // chain is [集团, 一级分行, ..., leaf]
    // Map: chain[0]→集团, chain[1]→一级分行, chain[2]→二级分行/部门, chain[3]→三级部门, chain[4]→四级部门
    for (let i = 0; i < Math.min(chain.length, 5); i++) {
      levels[i] = chain[i];
    }
    return levels;
  }, [orgMap]);

  const columns = [
    { title: LEVEL_LABELS[0], key: 'level0', width: 140, fixed: 'left' as const,
      render: (_: unknown, r: OwnershipEntry) => {
        const dirOrgId = directoryOrgMap[r.phone_number];
        const levels = getOrgLevels(dirOrgId ?? r.org_id);
        return levels[0] || '-';
      }},
    { title: LEVEL_LABELS[1], key: 'level1', width: 120,
      render: (_: unknown, r: OwnershipEntry) => {
        const dirOrgId = directoryOrgMap[r.phone_number];
        const levels = getOrgLevels(dirOrgId ?? r.org_id);
        return levels[1] || '-';
      }},
    { title: LEVEL_LABELS[2], key: 'level2', width: 160,
      render: (_: unknown, r: OwnershipEntry) => {
        const dirOrgId = directoryOrgMap[r.phone_number];
        const levels = getOrgLevels(dirOrgId ?? r.org_id);
        return levels[2] || '-';
      }},
    { title: LEVEL_LABELS[3], key: 'level3', width: 120,
      render: (_: unknown, r: OwnershipEntry) => {
        const dirOrgId = directoryOrgMap[r.phone_number];
        const levels = getOrgLevels(dirOrgId ?? r.org_id);
        return levels[3] || '-';
      }},
    { title: LEVEL_LABELS[4], key: 'level4', width: 120,
      render: (_: unknown, r: OwnershipEntry) => {
        const dirOrgId = directoryOrgMap[r.phone_number];
        const levels = getOrgLevels(dirOrgId ?? r.org_id);
        return levels[4] || '-';
      }},
    { title: '员工ID', key: 'username', width: 130,
      render: (_: unknown, r: OwnershipEntry) => directoryInfoMap[r.phone_number]?.username || '-' },
    { title: '分机号码', key: 'extension', width: 100,
      render: (_: unknown, r: OwnershipEntry) => directoryInfoMap[r.phone_number]?.extension || '-' },
    { title: '号码', dataIndex: 'phone_number', key: 'phone_number', width: 130 },
    { title: '例外', dataIndex: 'is_exception', key: 'is_exception', width: 70,
      render: (v: number) => v === 1 ? <Tag color={COLORS.danger}>P0</Tag> : '-' },
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
          <Col span={4}><Statistic title={t('phoneOwnership.exceptionCount')} value={exceptionCount} valueStyle={{ color: exceptionCount > 0 ? COLORS.danger : undefined }} /></Col>
        </Row>
      )}
      {selectedBatchId && (
        <Input prefix={<SearchOutlined />} placeholder={t('phoneOwnership.searchPlaceholder')} allowClear value={search}
          onChange={e => setSearch(e.target.value)} style={{ width: 320, marginBottom: 12 }} />
      )}
      {selectedBatchId && filteredEntries.length > 0 ? (
        <Table columns={columns} dataSource={filteredEntries} rowKey="id" size="small" loading={entriesLoading}
          pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: (total) => t('common.paginationTotal', { total }) }}
          scroll={{ x: 1000 }} />
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
          <Col style={{ color: COLORS.textMuted, fontSize: 13 }}>
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
          scroll={{ x: 1000 }} />
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
