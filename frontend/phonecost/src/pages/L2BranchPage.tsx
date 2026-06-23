import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Button, Descriptions, Row, Col, message, Empty, Tag } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BillBatch } from '../types/bill';
import type { AllocationResult } from '../types/allocation';
import { CONFIRM_STATUS_MAP } from '../types/allocation';
import { getBillBatches, getAllocationResults, getL2BranchDetailUrl } from '../api/allocation';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS } from '../types/organization';

export default function L2BranchPage() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [selectedBranchId, setSelectedBranchId] = useState<number | null>(null);
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [resultsLoading, setResultsLoading] = useState(false);

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try { setBatches(await getBillBatches()); } catch { message.error(t('l2Branch.fetchFailed')); } finally { setLoading(false); }
  }, [t]);

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { /* silent */ }
  }, []);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  // 自动选最近月份
  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  // 一级分行列表 (type=2)
  const branches = useMemo(() =>
    orgList.filter(o => o.type === 2).sort((a, b) => (a.code || '').localeCompare(b.code || '')),
    [orgList]);

  // 自动选第一个分行
  useEffect(() => {
    if (branches.length > 0 && !selectedBranchId) setSelectedBranchId(branches[0].id);
  }, [branches, selectedBranchId]);

  useEffect(() => {
    if (selectedBatchId) {
      setResultsLoading(true);
      getAllocationResults(selectedBatchId)
        .then(setResults)
        .catch(() => message.error(t('l2Branch.fetchFailed')))
        .finally(() => setResultsLoading(false));
    }
  }, [selectedBatchId, t]);

  const orgMap = useMemo(() => {
    const m = new Map<number, Organization>();
    orgList.forEach(o => m.set(o.id, o));
    return m;
  }, [orgList]);

  const selectedBranch = orgMap.get(selectedBranchId || 0);

  // 该一级分行的直属子组织
  const directChildren = useMemo(() => {
    if (!selectedBranchId) return [];
    return orgList
      .filter(o => o.parent_id === selectedBranchId)
      .sort((a, b) => {
        const ta = a.type || 99, tb = b.type || 99;
        return ta !== tb ? ta - tb : a.name.localeCompare(b.name);
      });
  }, [selectedBranchId, orgList]);

  // 汇总数据
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
      const confirmStatus = childResults.length > 0 ? childResults[0].confirm_status : -1;
      return { child, monthlyRent, callFee, recordingFee, crbtFee, flashFee, totalFee, phoneCount, confirmStatus };
    });
  }, [directChildren, results]);

  const branchTotal = childSummary.reduce((s, c) => s + c.totalFee, 0);
  const branchPhones = childSummary.reduce((s, c) => s + c.phoneCount, 0);
  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  const money = (v: number) => v ? `¥${v.toFixed(2)}` : '-';
  const orgTypeLabel = (type: number) => ORG_TYPE_LABELS[type] || '其他';

  const columns = [
    { title: t('l2Branch.seqCol'), key: 'seq', width: 50, render: (_: unknown, __: unknown, i: number) => i + 1 },
    { title: t('l2Branch.orgTypeCol'), key: 'orgType', width: 80, render: (_: unknown, r: typeof childSummary[0]) => orgTypeLabel(r.child.type) },
    { title: t('l2Branch.orgNameCol'), key: 'orgName', width: 140, render: (_: unknown, r: typeof childSummary[0]) => r.child.name },
    { title: t('l2Branch.costCenterCol'), key: 'costCenter', width: 90, render: (_: unknown, r: typeof childSummary[0]) => r.child.code || '-' },
    { title: t('l2Branch.monthlyRentCodeCol'), key: 'monthlyRent', width: 100, dataIndex: 'monthlyRent', render: money },
    { title: t('l2Branch.domesticFeeCol'), key: 'callFee', width: 100, dataIndex: 'callFee', render: money },
    { title: t('l2Branch.recordingFeeCol'), key: 'recordingFee', width: 100, dataIndex: 'recordingFee', render: money },
    { title: t('l2Branch.crbtFeeCol'), key: 'crbtFee', width: 90, dataIndex: 'crbtFee', render: money },
    { title: t('l2Branch.flashFeeCol'), key: 'flashFee', width: 90, dataIndex: 'flashFee', render: money },
    { title: t('l2Branch.totalCol'), key: 'totalFee', width: 120, dataIndex: 'totalFee',
      render: (v: number) => <strong>{money(v)}</strong>,
    },
    { title: t('l2Branch.phoneCountCol'), key: 'phoneCount', width: 70, dataIndex: 'phoneCount' },
    { title: t('l2Branch.confirmStatusCol'), key: 'confirmStatus', width: 90,
      render: (_: unknown, r: typeof childSummary[0]) => {
        if (r.confirmStatus < 0) return '-';
        const info = CONFIRM_STATUS_MAP[r.confirmStatus] || { label: '未知', color: 'default' };
        return <Tag color={info.color}>{info.label}</Tag>;
      },
    },
  ];

  return (
    <div>
      <Card>
        <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <span style={{ marginRight: 8 }}>{t('l2Branch.selectMonth')}</span>
            <Select style={{ width: 220 }} placeholder="选择月份" loading={loading} value={selectedBatchId} onChange={setSelectedBatchId}
              options={batches.sort((a, b) => b.billing_month.localeCompare(a.billing_month)).map(b => ({ label: `${b.billing_month}`, value: b.id }))} />
          </Col>
          <Col>
            <span style={{ marginRight: 8 }}>{t('l2Branch.selectBranch')}</span>
            <Select style={{ width: 180 }} placeholder="选择分行" value={selectedBranchId} onChange={setSelectedBranchId}
              options={branches.map(b => ({ label: b.name, value: b.id }))} />
          </Col>
          <Col>
            {selectedBatchId && selectedBranchId && (
              <Button type="primary" icon={<DownloadOutlined />}
                onClick={() => window.open(getL2BranchDetailUrl(selectedBatchId, selectedBranchId), '_blank')}>
                {t('l2Branch.exportL2')}
              </Button>
            )}
          </Col>
        </Row>

        {selectedBatchId && selectedBranchId && childSummary.length > 0 && (
          <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
            <Descriptions.Item label={t('l2Branch.descMonth')}>{selectedBatch?.billing_month}</Descriptions.Item>
            <Descriptions.Item label={t('l2Branch.descBranch')}>{selectedBranch?.name}</Descriptions.Item>
            <Descriptions.Item label={t('l2Branch.descChildCount')}>{directChildren.length}</Descriptions.Item>
            <Descriptions.Item label={t('l2Branch.descTotalFee')}>¥{branchTotal.toFixed(2)}</Descriptions.Item>
          </Descriptions>
        )}

        {selectedBatchId && selectedBranchId && childSummary.length > 0 ? (
          <Table
            columns={columns}
            dataSource={childSummary}
            rowKey={r => r.child.id}
            size="small"
            loading={resultsLoading}
            pagination={false}
            summary={() => (
              <Table.Summary.Row>
                <Table.Summary.Cell index={0} colSpan={4}><strong>{t('l2Branch.totalRow')}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={4} />
                <Table.Summary.Cell index={5} />
                <Table.Summary.Cell index={6} />
                <Table.Summary.Cell index={7} />
                <Table.Summary.Cell index={8} />
                <Table.Summary.Cell index={9}><strong>¥{branchTotal.toFixed(2)}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={10}><strong>{branchPhones}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={11} />
              </Table.Summary.Row>
            )}
          />
        ) : (
          !resultsLoading && <Empty description={t('l2Branch.noData')} />
        )}
      </Card>
    </div>
  );
}
