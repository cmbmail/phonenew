import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Button, Descriptions, Row, Col, message, Empty } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BillBatch } from '../types/bill';
import type { AllocationResult } from '../types/allocation';
import { getBillBatches, getAllocationResults, getL3SubBranchDetailUrl } from '../api/allocation';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS } from '../types/organization';

export default function L3SubBranchPage() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [selectedSubBranchId, setSelectedSubBranchId] = useState<number | null>(null);
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [resultsLoading, setResultsLoading] = useState(false);

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try { setBatches(await getBillBatches()); } catch { message.error(t('l3SubBranch.fetchFailed')); } finally { setLoading(false); }
  }, [t]);

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { /* silent */ }
  }, []);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  // 二级分行列表 (type=3)
  const subBranches = useMemo(() =>
    orgList.filter(o => o.type === 3).sort((a, b) => a.name.localeCompare(b.name)),
    [orgList]);

  // 按一级分行分组
  const orgMap = useMemo(() => {
    const m = new Map<number, Organization>();
    orgList.forEach(o => m.set(o.id, o));
    return m;
  }, [orgList]);

  const subBranchGroups = useMemo(() => {
    const groups = new Map<string, { label: string; options: { label: string; value: number }[] }>();
    for (const sb of subBranches) {
      const parent = orgMap.get(sb.parent_id || 0);
      const groupLabel = parent ? parent.name : '其他';
      if (!groups.has(groupLabel)) groups.set(groupLabel, { label: groupLabel, options: [] });
      groups.get(groupLabel)!.options.push({ label: sb.name, value: sb.id });
    }
    return Array.from(groups.values());
  }, [subBranches, orgMap]);

  useEffect(() => {
    if (subBranches.length > 0 && !selectedSubBranchId) setSelectedSubBranchId(subBranches[0].id);
  }, [subBranches, selectedSubBranchId]);

  useEffect(() => {
    if (selectedBatchId) {
      setResultsLoading(true);
      getAllocationResults(selectedBatchId)
        .then(setResults)
        .catch(() => message.error(t('l3SubBranch.fetchFailed')))
        .finally(() => setResultsLoading(false));
    }
  }, [selectedBatchId, t]);

  const selectedSubBranch = orgMap.get(selectedSubBranchId || 0);

  // 该二级分行的直属子组织
  const directChildren = useMemo(() => {
    if (!selectedSubBranchId) return [];
    return orgList
      .filter(o => o.parent_id === selectedSubBranchId)
      .sort((a, b) => {
        const ta = a.type || 99, tb = b.type || 99;
        return ta !== tb ? ta - tb : a.name.localeCompare(b.name);
      });
  }, [selectedSubBranchId, orgList]);

  const childSummary = useMemo(() => {
    return directChildren.map(child => {
      const childResults = results.filter(r => r.org_id === child.id);
      const monthlyRent = childResults.reduce((s, r) => s + (r.monthly_rent || 0), 0);
      const callFee = childResults.reduce((s, r) => s + (r.call_fee || 0), 0);
      const recordingFee = childResults.reduce((s, r) => s + (r.recording_fee || 0), 0);
      const crbtFee = childResults.reduce((s, r) => s + (r.crbt_fee || 0), 0);
      const flashFee = childResults.reduce((s, r) => s + (r.flash_msg_fee || 0), 0);
      const totalFee = childResults.reduce((s, r) => s + (r.total_fee || 0), 0);
      const phoneCount = childResults.reduce((s, r) => s + (r.phone_count || 0), 0);
      return { child, monthlyRent, callFee, recordingFee, crbtFee, flashFee, totalFee, phoneCount };
    });
  }, [directChildren, results]);

  const branchTotal = childSummary.reduce((s, c) => s + c.totalFee, 0);
  const branchPhones = childSummary.reduce((s, c) => s + c.phoneCount, 0);
  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  const money = (v: number) => v ? `¥${v.toFixed(2)}` : '-';
  const orgTypeLabel = (type: number) => ORG_TYPE_LABELS[type] || '其他';

  const columns = [
    { title: t('l3SubBranch.seqCol'), key: 'seq', width: 50, render: (_: unknown, __: unknown, i: number) => i + 1 },
    { title: t('l3SubBranch.orgTypeCol'), key: 'orgType', width: 80, render: (_: unknown, r: typeof childSummary[0]) => orgTypeLabel(r.child.type) },
    { title: t('l3SubBranch.orgNameCol'), key: 'orgName', width: 140, render: (_: unknown, r: typeof childSummary[0]) => r.child.name },
    { title: t('l3SubBranch.costCenterCol'), key: 'costCenter', width: 90, render: (_: unknown, r: typeof childSummary[0]) => r.child.code || '-' },
    { title: t('l3SubBranch.monthlyRentCodeCol'), key: 'monthlyRent', width: 100, dataIndex: 'monthlyRent', render: money },
    { title: t('l3SubBranch.domesticFeeCol'), key: 'callFee', width: 100, dataIndex: 'callFee', render: money },
    { title: t('l3SubBranch.recordingFeeCol'), key: 'recordingFee', width: 100, dataIndex: 'recordingFee', render: money },
    { title: t('l3SubBranch.crbtFeeCol'), key: 'crbtFee', width: 90, dataIndex: 'crbtFee', render: money },
    { title: t('l3SubBranch.flashFeeCol'), key: 'flashFee', width: 90, dataIndex: 'flashFee', render: money },
    { title: t('l3SubBranch.totalCol'), key: 'totalFee', width: 120, dataIndex: 'totalFee',
      render: (v: number) => <strong>{money(v)}</strong>,
    },
    { title: t('l3SubBranch.phoneCountCol'), key: 'phoneCount', width: 70, dataIndex: 'phoneCount' },
  ];

  return (
    <div>
      <Card>
        <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <span style={{ marginRight: 8 }}>{t('l3SubBranch.selectMonth')}</span>
            <Select style={{ width: 220 }} placeholder="选择月份" loading={loading} value={selectedBatchId} onChange={setSelectedBatchId}
              options={batches.sort((a, b) => b.billing_month.localeCompare(a.billing_month)).map(b => ({ label: `${b.billing_month}`, value: b.id }))} />
          </Col>
          <Col>
            <span style={{ marginRight: 8 }}>{t('l3SubBranch.selectSubBranch')}</span>
            <Select style={{ width: 200 }} placeholder="选择二级分行" value={selectedSubBranchId} onChange={setSelectedSubBranchId}
              options={subBranchGroups} showSearch optionFilterProp="label" />
          </Col>
          <Col>
            {selectedBatchId && selectedSubBranchId && (
              <Button type="primary" icon={<DownloadOutlined />}
                onClick={() => window.open(getL3SubBranchDetailUrl(selectedBatchId, selectedSubBranchId), '_blank')}>
                {t('l3SubBranch.exportL3')}
              </Button>
            )}
          </Col>
        </Row>

        {selectedBatchId && selectedSubBranchId && childSummary.length > 0 && (
          <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="月份">{selectedBatch?.billing_month}</Descriptions.Item>
            <Descriptions.Item label="二级分行">{selectedSubBranch?.name}</Descriptions.Item>
            <Descriptions.Item label="下属组织数">{directChildren.length}</Descriptions.Item>
            <Descriptions.Item label="费用合计">¥{branchTotal.toFixed(2)}</Descriptions.Item>
          </Descriptions>
        )}

        {selectedBatchId && selectedSubBranchId && childSummary.length > 0 ? (
          <Table
            columns={columns}
            dataSource={childSummary}
            rowKey={r => r.child.id}
            size="small"
            loading={resultsLoading}
            pagination={false}
            summary={() => (
              <Table.Summary.Row>
                <Table.Summary.Cell index={0} colSpan={4}><strong>{t('l3SubBranch.totalRow')}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={4} />
                <Table.Summary.Cell index={5} />
                <Table.Summary.Cell index={6} />
                <Table.Summary.Cell index={7} />
                <Table.Summary.Cell index={8} />
                <Table.Summary.Cell index={9}><strong>¥{branchTotal.toFixed(2)}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={10}><strong>{branchPhones}</strong></Table.Summary.Cell>
              </Table.Summary.Row>
            )}
          />
        ) : (
          !resultsLoading && <Empty description={t('l3SubBranch.noData')} />
        )}
      </Card>
    </div>
  );
}
