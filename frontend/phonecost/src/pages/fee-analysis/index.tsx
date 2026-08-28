import { useState, useEffect, useCallback, useMemo } from 'react';
import { Card, Segmented, message } from 'antd';
import { BarChartOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { apiGet } from '../../lib/request';
import { getBillBatches } from '../../api/import';
import { getOrgTree } from '../../api/org';
import { useAbortableEffect } from '../../hooks/useAbortableEffect';
import type { Organization } from '../../types/organization';
import type { BillBatch } from '../../types/bill';
import {
  DIMENSION_OPTIONS, getOrgTypeLabel,
  type Dimension, type BarRow, type L1MonthlyResult, type PhoneAnalysisResult, type PhoneListRow,
} from './shared';
import AllDimension from './AllDimension';
import L1Branch from './L1Branch';
import L2Branch from './L2Branch';
import Department from './Department';
import PhoneAnalysis from './PhoneAnalysis';

export default function FeeAnalysisPage() {
  const { t } = useTranslation();
  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [dimension, setDimension] = useState<Dimension>('all');

  const [phoneData, setPhoneData] = useState<PhoneAnalysisResult | null>(null);

  const [allMonthlyData, setAllMonthlyData] = useState<BarRow[]>([]);
  const [allMonthlyLoading, setAllMonthlyLoading] = useState(false);

  const [l1MonthlyData, setL1MonthlyData] = useState<L1MonthlyResult | null>(null);
  const [l1MonthlyLoading, setL1MonthlyLoading] = useState(false);
  const [selectedL1OrgId, setSelectedL1OrgId] = useState<number | null>(null);

  const [l2MonthlyData, setL2MonthlyData] = useState<L1MonthlyResult | null>(null);
  const [l2MonthlyLoading, setL2MonthlyLoading] = useState(false);
  const [selectedL2OrgId, setSelectedL2OrgId] = useState<number | null>(null);

  const [deptMonthlyData, setDeptMonthlyData] = useState<L1MonthlyResult | null>(null);
  const [deptMonthlyLoading, setDeptMonthlyLoading] = useState(false);
  const [selectedDeptOrgId, setSelectedDeptOrgId] = useState<number | null>(null);

  const [orgList, setOrgList] = useState<Organization[]>([]);

  const [phoneList, setPhoneList] = useState<PhoneListRow[]>([]);
  const [phoneListLoading, setPhoneListLoading] = useState(false);
  const [phoneSearch, setPhoneSearch] = useState('');
  const [phoneListL1OrgId, setPhoneListL1OrgId] = useState<number | null>(null);
  const [phonePageSize, setPhonePageSize] = useState(50);

  const fetchBatches = useCallback(async () => {
    try { setBatches(await getBillBatches()); } catch { message.error(t('feeAnalysis.fetchBatchesFailed')); }
  }, [t]);

  const fetchOrgs = useCallback(async () => {
    try { setOrgList(await getOrgTree()); } catch { message.error(t('feeAnalysis.fetchOrgsFailed')); }
  }, [t]);

  useEffect(() => { fetchBatches(); fetchOrgs(); }, [fetchBatches, fetchOrgs]);

  useAbortableEffect((signal) => {
    if (dimension !== 'all') return;
    setAllMonthlyLoading(true);
    apiGet<BarRow[]>('/allocation/analysis/monthly-comparison', undefined, signal)
      .then(data => setAllMonthlyData(data || []))
      .catch(() => setAllMonthlyData([]))
      .finally(() => setAllMonthlyLoading(false));
  }, [dimension]);

  useEffect(() => {
    if (batches.length > 0 && !selectedBatchId) {
      const sorted = [...batches].sort((a, b) => b.billing_month.localeCompare(a.billing_month));
      setSelectedBatchId(sorted[0].id);
    }
  }, [batches, selectedBatchId]);

  useAbortableEffect((signal) => {
    if (dimension === 'l1' && selectedL1OrgId) {
      setL1MonthlyLoading(true);
      apiGet<L1MonthlyResult>(`/allocation/analysis/l1-monthly?orgId=${selectedL1OrgId}`, undefined, signal)
        .then(setL1MonthlyData).catch(() => setL1MonthlyData(null))
        .finally(() => setL1MonthlyLoading(false));
    }
  }, [dimension, selectedL1OrgId]);

  useAbortableEffect((signal) => {
    if (dimension === 'l2' && selectedL2OrgId) {
      setL2MonthlyLoading(true);
      apiGet<L1MonthlyResult>(`/allocation/analysis/l2-monthly?orgId=${selectedL2OrgId}`, undefined, signal)
        .then(setL2MonthlyData).catch(() => setL2MonthlyData(null))
        .finally(() => setL2MonthlyLoading(false));
    }
  }, [dimension, selectedL2OrgId]);

  useAbortableEffect((signal) => {
    if (dimension === 'dept' && selectedDeptOrgId) {
      setDeptMonthlyLoading(true);
      apiGet<L1MonthlyResult>(`/allocation/analysis/dept-monthly?orgId=${selectedDeptOrgId}`, undefined, signal)
        .then(setDeptMonthlyData).catch(() => setDeptMonthlyData(null))
        .finally(() => setDeptMonthlyLoading(false));
    }
  }, [dimension, selectedDeptOrgId]);

  const selectPhone = useCallback(async (phone: string) => {
    try {
      const data = await apiGet<PhoneAnalysisResult>(`/allocation/analysis?batchId=${selectedBatchId || 0}&dimension=PHONE&phoneNumber=${phone}`);
      setPhoneData(data);
    } catch {
      setPhoneData(null);
    }
  }, [selectedBatchId]);

  useAbortableEffect((signal) => {
    if (dimension !== 'phone') return;
    setPhoneListLoading(true);
    const url = phoneListL1OrgId
      ? `/allocation/analysis/phone-list?orgId=${phoneListL1OrgId}`
      : '/allocation/analysis/phone-list';
    apiGet<{ total_count: number; rows: PhoneListRow[] }>(url, undefined, signal)
      .then(data => setPhoneList(data.rows || []))
      .catch(() => setPhoneList([]))
      .finally(() => setPhoneListLoading(false));
  }, [dimension, phoneListL1OrgId]);

  const l1Orgs = useMemo(() => orgList.filter(o => o.type === 2), [orgList]);

  const l2Orgs = useMemo(() => {
    if (!selectedL1OrgId) return [];
    const l1 = orgList.find(o => o.id === selectedL1OrgId);
    if (!l1?.path) return [];
    return orgList.filter(o => o.type === 3 && o.path?.startsWith(l1.path) && o.id !== l1.id);
  }, [orgList, selectedL1OrgId]);

  const deptOrgOptions = useMemo(() => {
    return orgList
      .filter(o => o.type !== 1)
      .map(o => ({ label: `${o.name} (${getOrgTypeLabel(o.type, t)})`, value: o.id }));
  }, [orgList, t]);

  return (
    <Card title={<><BarChartOutlined style={{ marginRight: 8 }} />{t('feeAnalysis.title')}</>} styles={{ body: { padding: '16px 20px' } }}>
      <div style={{ marginBottom: 16 }}>
        <Segmented
          options={DIMENSION_OPTIONS.map(o => ({ value: o.value, label: t(o.labelKey) }))}
          value={dimension}
          onChange={v => setDimension(v as Dimension)}
        />
      </div>

      {dimension === 'all' && (
        <AllDimension allMonthlyData={allMonthlyData} allMonthlyLoading={allMonthlyLoading} />
      )}
      {dimension === 'l1' && (
        <L1Branch l1Orgs={l1Orgs} selectedL1OrgId={selectedL1OrgId} setSelectedL1OrgId={setSelectedL1OrgId} l1MonthlyData={l1MonthlyData} l1MonthlyLoading={l1MonthlyLoading} />
      )}
      {dimension === 'l2' && (
        <L2Branch l1Orgs={l1Orgs} l2Orgs={l2Orgs} selectedL1OrgId={selectedL1OrgId} setSelectedL1OrgId={setSelectedL1OrgId} selectedL2OrgId={selectedL2OrgId} setSelectedL2OrgId={setSelectedL2OrgId} l2MonthlyData={l2MonthlyData} l2MonthlyLoading={l2MonthlyLoading} />
      )}
      {dimension === 'dept' && (
        <Department deptOrgOptions={deptOrgOptions} selectedDeptOrgId={selectedDeptOrgId} setSelectedDeptOrgId={setSelectedDeptOrgId} deptMonthlyData={deptMonthlyData} deptMonthlyLoading={deptMonthlyLoading} />
      )}
      {dimension === 'phone' && (
        <PhoneAnalysis phoneData={phoneData} setPhoneData={setPhoneData} l1Orgs={l1Orgs} phoneList={phoneList} phoneListLoading={phoneListLoading} phoneSearch={phoneSearch} setPhoneSearch={setPhoneSearch} phoneListL1OrgId={phoneListL1OrgId} setPhoneListL1OrgId={setPhoneListL1OrgId} phonePageSize={phonePageSize} setPhonePageSize={setPhonePageSize} selectPhone={selectPhone} />
      )}
    </Card>
  );
}
