import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Tag, Row, Col, message, Empty, Input, Statistic, Tabs } from 'antd';
import { SearchOutlined, CameraOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { DirectoryBatch, DirectoryEntry, DataSnapshot } from '../types/import';
import { getDirectoryBatches, getSnapshots, getBillBatches } from '../api/import';
import { apiGet } from '../lib/request';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import type { BillBatch } from '../types/bill';

export default function DepartmentOwnership() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<DirectoryBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [entries, setEntries] = useState<DirectoryEntry[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [search, setSearch] = useState('');

  // Snapshot state
  const [snapshots, setSnapshots] = useState<DataSnapshot[]>([]);
  const [billBatches, setBillBatches] = useState<BillBatch[]>([]);
  const [snapshotsLoading, setSnapshotsLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('current');

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try { setBatches(await getDirectoryBatches()); } catch { message.error(t('deptOwnership.fetchFailed')); } finally { setLoading(false); }
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
    } catch { message.error(t('deptOwnership.snapshotFetchFailed')); } finally { setSnapshotsLoading(false); }
  }, [t]);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  useEffect(() => {
    if (activeTab === 'snapshot' && snapshots.length === 0) {
      fetchSnapshots();
    }
  }, [activeTab, snapshots.length, fetchSnapshots]);

  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.id - a.id);
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  useEffect(() => {
    if (selectedBatchId) {
      setEntriesLoading(true);
      apiGet<DirectoryEntry[]>(`/import/directory/entries/${selectedBatchId}`)
        .then(setEntries)
        .catch(() => message.error(t('deptOwnership.fetchFailed')))
        .finally(() => setEntriesLoading(false));
    }
  }, [selectedBatchId, t]);

  const orgMap = useMemo(() => {
    const m = new Map<number, Organization>();
    orgList.forEach(o => m.set(o.id, o));
    return m;
  }, [orgList]);

  const billBatchMap = useMemo(() => {
    const m = new Map<number, BillBatch>();
    billBatches.forEach(b => m.set(b.id, b));
    return m;
  }, [billBatches]);

  const secondedCount = useMemo(() => entries.filter(e => e.is_seconded === 1).length, [entries]);

  // Search filter
  const filteredEntries = useMemo(() => {
    const kw = search.trim().toLowerCase();
    if (!kw) return entries;
    return entries.filter(e => {
      const org = e.org_id ? orgMap.get(e.org_id) : null;
      const orgName = org ? org.name : '';
      return String(e.phone_number || '').toLowerCase().includes(kw) ||
        String(e.username || '').toLowerCase().includes(kw) ||
        String(e.extension || '').toLowerCase().includes(kw) ||
        String(e.dept_path || '').toLowerCase().includes(kw) ||
        orgName.toLowerCase().includes(kw);
    });
  }, [entries, search, orgMap]);

  const columns = [
    { title: t('deptOwnership.phoneCol'), dataIndex: 'phone_number', key: 'phone_number', width: 130, fixed: 'left' as const },
    { title: t('deptOwnership.extCol'), dataIndex: 'extension', key: 'extension', width: 80 },
    { title: t('deptOwnership.nameCol'), dataIndex: 'username', key: 'username', width: 80 },
    { title: t('deptOwnership.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 200, ellipsis: true },
    {
      title: t('deptOwnership.orgCol'), key: 'org_name', width: 200,
      render: (_: unknown, r: DirectoryEntry) => {
        if (!r.org_id) return <span style={{ color: '#999' }}>-</span>;
        const org = orgMap.get(r.org_id);
        return org ? buildFullOrgPath(r.org_id, orgMap) : String(r.org_id);
      },
    },
    {
      title: t('deptOwnership.costCenterCol'), key: 'cost_center', width: 100,
      render: (_: unknown, r: DirectoryEntry) => {
        const effectiveOrgId = r.is_seconded === 1 && r.actual_org_id ? r.actual_org_id : r.org_id;
        if (!effectiveOrgId) return '-';
        const org = orgMap.get(effectiveOrgId);
        return org?.code || '-';
      },
    },
    {
      title: t('deptOwnership.secondedCol'), dataIndex: 'is_seconded', key: 'is_seconded', width: 80,
      render: (v: number, _r: DirectoryEntry) => {
        if (v !== 1) return '-';
        return <Tag color="orange">借调</Tag>;
      },
    },
  ];

  // Snapshot columns
  const snapshotColumns = [
    {
      title: t('deptOwnership.snapshotBillMonth'), key: 'bill_month', width: 120,
      render: (_: unknown, r: DataSnapshot) => {
        const bill = billBatchMap.get(r.bill_batch_id);
        return bill?.billing_month || '-';
      },
    },
    {
      title: t('deptOwnership.snapshotBillBatch'), dataIndex: 'bill_batch_id', key: 'bill_batch_id', width: 120,
      render: (v: number) => {
        const bill = billBatchMap.get(v);
        return bill ? `${bill.batch_no}` : String(v);
      },
    },
    {
      title: t('deptOwnership.snapshotOwnershipBatch'), dataIndex: 'ownership_batch_id', key: 'ownership_batch_id', width: 150,
      render: (v: number | null) => v != null ? `批次 #${v}` : '-',
    },
    {
      title: t('deptOwnership.snapshotDirectoryBatch'), dataIndex: 'directory_batch_id', key: 'directory_batch_id', width: 150,
      render: (v: number | null) => v != null ? `批次 #${v}` : '-',
    },
    {
      title: t('deptOwnership.snapshotMatchedCount'), dataIndex: 'matched_count', key: 'matched_count', width: 100,
      render: (v: number) => v.toLocaleString(),
    },
    {
      title: t('deptOwnership.snapshotTime'), dataIndex: 'created_at', key: 'created_at', width: 180,
    },
  ];

  const currentDataContent = (
    <>
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <span style={{ marginRight: 8 }}>{t('deptOwnership.selectBatch')}</span>
          <Select style={{ width: 280 }} placeholder={t('deptOwnership.selectBatchPlaceholder')} loading={loading}
            value={selectedBatchId} onChange={setSelectedBatchId}
            options={[...batches].sort((a, b) => b.id - a.id).map(b => ({ label: `${b.batch_no} (${b.total_count}条)`, value: b.id }))} />
        </Col>
      </Row>

      {selectedBatchId && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}><Statistic title={t('deptOwnership.totalCount')} value={filteredEntries.length} /></Col>
          <Col span={4}><Statistic title={t('deptOwnership.secondedCount')} value={secondedCount} valueStyle={{ color: secondedCount > 0 ? '#d46b08' : undefined }} /></Col>
        </Row>
      )}

      {selectedBatchId && (
        <Input
          prefix={<SearchOutlined />}
          placeholder={t('deptOwnership.searchPlaceholder')}
          allowClear
          value={search}
          onChange={e => setSearch(e.target.value)}
          style={{ width: 360, marginBottom: 12 }}
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
          scroll={{ x: 900 }}
        />
      ) : (
        !entriesLoading && <Empty description={t('deptOwnership.noData')} />
      )}
    </>
  );

  const snapshotContent = snapshots.length > 0 ? (
    <Table
      columns={snapshotColumns}
      dataSource={snapshots}
      rowKey="id"
      size="small"
      loading={snapshotsLoading}
      pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (total) => t('common.paginationTotal', { total }) }}
      scroll={{ x: 800 }}
    />
  ) : (
    !snapshotsLoading && <Empty description={t('deptOwnership.snapshotNoData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
  );

  return (
    <div>
      <Card>
        <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
          { key: 'current', label: t('deptOwnership.currentDataTab'), children: currentDataContent },
          { key: 'snapshot', label: <><CameraOutlined /> {t('deptOwnership.snapshotTab')}</>, children: snapshotContent },
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
