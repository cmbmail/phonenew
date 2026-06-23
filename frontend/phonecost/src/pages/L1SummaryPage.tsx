import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Table, Select, Button, Descriptions, Statistic, Row, Col, message, Empty } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BillBatch } from '../types/bill';
import type { AllocationResult } from '../types/allocation';
import { CONFIRM_STATUS_MAP } from '../types/allocation';
import { getBillBatches, getAllocationResults, getL1SummaryUrl } from '../api/allocation';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';

export default function L1SummaryPage() {
  const { t } = useTranslation();

  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [resultsLoading, setResultsLoading] = useState(false);

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getBillBatches();
      setBatches(data);
    } catch {
      message.error(t('l1Summary.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { /* silent */ }
  }, []);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  // 自动选择最近月份
  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  useEffect(() => {
    if (selectedBatchId) {
      setResultsLoading(true);
      getAllocationResults(selectedBatchId)
        .then(setResults)
        .catch(() => message.error(t('l1Summary.fetchFailed')))
        .finally(() => setResultsLoading(false));
    }
  }, [selectedBatchId, t]);

  const orgMap = useMemo(() => {
    const m = new Map<number, Organization>();
    orgList.forEach(o => m.set(o.id, o));
    return m;
  }, [orgList]);

  // 找出所有一级分行 (type=2)
  const branches = useMemo(() => {
    return orgList.filter(o => o.type === 2).sort((a, b) => (a.code || '').localeCompare(b.code || ''));
  }, [orgList]);

  // 按一级分行聚合结果
  const branchSummary = useMemo(() => {
    return branches.map(branch => {
      const branchPath = branch.path;
      const childResults = results.filter(r => {
        if (r.org_id == null || r.org_id === -1) return false;
        const rOrg = orgMap.get(r.org_id);
        return rOrg && rOrg.path && rOrg.path.startsWith(branchPath);
      });
      const totalFee = childResults.reduce((s, r) => s + (r.total_fee || 0), 0);
      const phoneCount = childResults.reduce((s, r) => s + (r.phone_count || 0), 0);
      const confirmed = childResults.filter(r => r.confirm_status === 1).length;
      const pending = childResults.filter(r => r.confirm_status === 0).length;
      return { branch, totalFee, phoneCount, childCount: childResults.length, confirmed, pending };
    });
  }, [branches, results, orgMap]);

  const grandTotal = results.reduce((s, r) => s + (r.total_fee || 0), 0);
  const totalPhones = results.reduce((s, r) => s + (r.phone_count || 0), 0);

  const selectedBatch = batches.find(b => b.id === selectedBatchId);

  const money = (v: number) => v ? `¥${v.toFixed(2)}` : '-';

  const columns = [
    { title: t('l1Summary.branchCol'), dataIndex: 'branchName', key: 'branchName', width: 140 },
    { title: t('l1Summary.costCenterCol'), dataIndex: 'costCenter', key: 'costCenter', width: 90 },
    { title: t('l1Summary.monthlyRentCodeCol'), dataIndex: 'monthlyRent', key: 'monthlyRent', width: 100, render: money },
    { title: t('l1Summary.callFeeCol') || '通话费', dataIndex: 'callFee', key: 'callFee', width: 100, render: money },
    { title: t('l1Summary.recordingFeeCol'), dataIndex: 'recordingFee', key: 'recordingFee', width: 100, render: money },
    { title: t('l1Summary.crbtFeeCol'), dataIndex: 'crbtFee', key: 'crbtFee', width: 90, render: money },
    { title: t('l1Summary.flashFeeCol'), dataIndex: 'flashFee', key: 'flashFee', width: 90, render: money },
    { title: t('l1Summary.totalCol'), dataIndex: 'totalFee', key: 'totalFee', width: 120,
      render: (v: number) => <strong>{money(v)}</strong>,
    },
    { title: t('l1Summary.phoneCountCol'), dataIndex: 'phoneCount', key: 'phoneCount', width: 70 },
    { title: '已确认', dataIndex: 'confirmed', key: 'confirmed', width: 70 },
    { title: '待确认', dataIndex: 'pending', key: 'pending', width: 70 },
  ];

  const dataSource = branchSummary.map(({ branch, totalFee, phoneCount, confirmed, pending }) => {
    // 聚合该分行下各类费用
    const branchPath = branch.path;
    const childResults = results.filter(r => {
      if (r.org_id == null || r.org_id === -1) return false;
      const rOrg = orgMap.get(r.org_id);
      return rOrg && rOrg.path && rOrg.path.startsWith(branchPath);
    });
    const monthlyRent = childResults.reduce((s, r) => s + (r.monthly_rent || 0), 0);
    const callFee = childResults.reduce((s, r) => s + (r.call_fee || 0), 0);
    const recordingFee = childResults.reduce((s, r) => s + (r.recording_fee || 0), 0);
    const crbtFee = childResults.reduce((s, r) => s + (r.crbt_fee || 0), 0);
    const flashFee = childResults.reduce((s, r) => s + (r.flash_msg_fee || 0), 0);
    return {
      key: branch.id,
      branchName: branch.name,
      costCenter: branch.code || '-',
      monthlyRent, callFee, recordingFee, crbtFee, flashFee,
      totalFee, phoneCount, confirmed, pending,
    };
  });

  return (
    <div>
      <Card>
        <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <span style={{ marginRight: 8 }}>{t('l1Summary.selectMonth')}</span>
            <Select
              style={{ width: 280 }}
              placeholder="选择月份"
              loading={loading}
              value={selectedBatchId}
              onChange={setSelectedBatchId}
              options={batches
                .sort((a, b) => b.billing_month.localeCompare(a.billing_month))
                .map(b => ({ label: `${b.billing_month} (${b.batch_no})`, value: b.id }))}
            />
          </Col>
          <Col>
            {selectedBatchId && (
              <Button type="primary" icon={<DownloadOutlined />}
                onClick={() => window.open(getL1SummaryUrl(selectedBatchId), '_blank')}>
                {t('l1Summary.exportL1')}
              </Button>
            )}
          </Col>
        </Row>

        {selectedBatchId && results.length > 0 && (
          <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="月份">{selectedBatch?.billing_month}</Descriptions.Item>
            <Descriptions.Item label="总费用">¥{grandTotal.toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label="号码总数">{totalPhones}</Descriptions.Item>
            <Descriptions.Item label="分行数">{branches.length}</Descriptions.Item>
          </Descriptions>
        )}

        {selectedBatchId && results.length > 0 ? (
          <Table
            columns={columns}
            dataSource={dataSource}
            rowKey="key"
            size="small"
            loading={resultsLoading}
            pagination={false}
            summary={() => (
              <Table.Summary.Row>
                <Table.Summary.Cell index={0}><strong>{t('l1Summary.grandTotalRow')}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={1} />
                <Table.Summary.Cell index={2} />
                <Table.Summary.Cell index={3} />
                <Table.Summary.Cell index={4} />
                <Table.Summary.Cell index={5} />
                <Table.Summary.Cell index={6} />
                <Table.Summary.Cell index={7}><strong>¥{grandTotal.toFixed(2)}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={8}><strong>{totalPhones}</strong></Table.Summary.Cell>
                <Table.Summary.Cell index={9} />
                <Table.Summary.Cell index={10} />
              </Table.Summary.Row>
            )}
          />
        ) : (
          !resultsLoading && <Empty description={t('l1Summary.noData')} />
        )}
      </Card>
    </div>
  );
}
