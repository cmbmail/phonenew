import { Row, Col, Card, Table, Select, Statistic, Input, Button } from 'antd';
import { COLORS } from '../../theme/morandi';
import { useTranslation } from 'react-i18next';
import {
  BarChart, money, renderOwnershipSourceTag,
  type PhoneAnalysisResult, type PhoneListRow, type Organization,
} from './shared';

interface PhoneAnalysisProps {
  phoneData: PhoneAnalysisResult | null;
  setPhoneData: (data: PhoneAnalysisResult | null) => void;
  l1Orgs: Organization[];
  phoneList: PhoneListRow[];
  phoneListLoading: boolean;
  phoneSearch: string;
  setPhoneSearch: (v: string) => void;
  phoneListL1OrgId: number | null;
  setPhoneListL1OrgId: (id: number | null) => void;
  phonePageSize: number;
  setPhonePageSize: (size: number) => void;
  selectPhone: (phone: string) => void;
}

export default function PhoneAnalysis({
  phoneData, setPhoneData, l1Orgs,
  phoneList, phoneListLoading, phoneSearch, setPhoneSearch,
  phoneListL1OrgId, setPhoneListL1OrgId, phonePageSize, setPhonePageSize,
  selectPhone,
}: PhoneAnalysisProps) {
  const { t } = useTranslation();

  const phoneDetailColumns = [
    { title: t('feeAnalysis.monthCol'), dataIndex: 'billing_month', key: 'billing_month', width: 100, fixed: 'left' as const },
    { title: t('feeAnalysis.monthlyRent'), dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money },
    { title: t('feeAnalysis.callFee'), dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money },
    { title: t('feeAnalysis.recordingFee'), dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money },
    { title: t('feeAnalysis.crbtFee'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money },
    { title: t('feeAnalysis.flashMsgFee'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money },
    { title: t('feeAnalysis.totalCol'), dataIndex: 'total_fee', key: 'total_fee', width: 110, render: (v: number) => <strong>{money(v)}</strong> },
    { title: t('feeAnalysis.ownershipOrg'), dataIndex: 'org_name', key: 'org_name', width: 140 },
    { title: t('feeAnalysis.ownershipSource'), dataIndex: 'ownership_source', key: 'ownership_source', width: 100,
      render: (v: string) => renderOwnershipSourceTag(v, t) },
    { title: t('feeAnalysis.billDetailCount'), dataIndex: 'detail_count', key: 'detail_count', width: 80 },
  ];

  const phoneListColumns = [
    { title: t('feeAnalysis.phoneNumber'), dataIndex: 'phone_number', key: 'phone_number', width: 140, fixed: 'left' as const,
      render: (v: string) => <a onClick={() => selectPhone(v)} style={{ color: COLORS.sage, cursor: 'pointer' }}>{v}</a> },
    { title: t('feeAnalysis.ownershipOrg'), dataIndex: 'org_name', key: 'org_name', width: 140,
      render: (v: string) => v || <span style={{ color: COLORS.textMuted }}>{t('feeAnalysis.unassigned')}</span> },
    { title: t('feeAnalysis.ownershipSource'), dataIndex: 'ownership_source', key: 'ownership_source', width: 90,
      render: (v: string) => renderOwnershipSourceTag(v, t) },
    { title: t('feeAnalysis.accumulatedFee'), dataIndex: 'total_fee', key: 'total_fee', width: 120, render: (v: number) => <strong>{money(v)}</strong>,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.total_fee || 0) - (b.total_fee || 0), defaultSortOrder: 'descend' as const },
    { title: t('feeAnalysis.monthlyRent'), dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.monthly_rent || 0) - (b.monthly_rent || 0) },
    { title: t('feeAnalysis.callFee'), dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.call_fee || 0) - (b.call_fee || 0) },
    { title: t('feeAnalysis.recordingFee'), dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.recording_fee || 0) - (b.recording_fee || 0) },
    { title: t('feeAnalysis.crbtFee'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.crbt_fee || 0) - (b.crbt_fee || 0) },
    { title: t('feeAnalysis.flashMsgFee'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.flash_msg_fee || 0) - (b.flash_msg_fee || 0) },
    { title: t('feeAnalysis.dataMonths'), dataIndex: 'month_count', key: 'month_count', width: 80,
      sorter: (a: PhoneListRow, b: PhoneListRow) => (a.month_count || 0) - (b.month_count || 0) },
  ];

  const filteredPhoneList = phoneList.filter(r => {
    const terms = phoneSearch.split(/[,，]/).map(s => s.trim()).filter(Boolean);
    if (terms.length === 0) return true;
    return terms.some(t => (r.phone_number || '').includes(t));
  });

  if (phoneData && phoneData.rows && phoneData.rows.length > 0) {
    return (
      <>
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col>
            <Button onClick={() => setPhoneData(null)} style={{ borderColor: COLORS.sage, color: COLORS.sage }}>{t('feeAnalysis.backToList')}</Button>
          </Col>
        </Row>
        <Row gutter={16} style={{ marginBottom: 20 }}>
          <Col span={4}><Statistic title={t('feeAnalysis.phoneLabel')} value={String(phoneData.phone_number || '')} valueStyle={{ fontSize: 16, color: COLORS.sage, fontVariantNumeric: 'normal' }} groupSeparator="" /></Col>
          <Col span={4}><Statistic title={t('feeAnalysis.phoneOrgLabel')} value={phoneData.org_name || t('feeAnalysis.unassigned')} valueStyle={{ fontSize: 16 }} /></Col>
          <Col span={3}><Statistic title={t('feeAnalysis.phoneAccumulatedFee')} value={Number(phoneData.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} /></Col>
          <Col span={3}><Statistic title={t('feeAnalysis.phoneAvgMonthlyFee')} value={Number(phoneData.avg_monthly_fee || 0).toFixed(2)} prefix="¥" /></Col>
          <Col span={3}>
            <Statistic title={t('feeAnalysis.phoneDataMonths')} value={phoneData.month_count} suffix={t('feeAnalysis.monthUnit')} />
            {phoneData.mom_change !== null && (
              <div style={{ fontSize: 12, color: Number(phoneData.mom_change) > 0 ? COLORS.danger : COLORS.confirmed }}>
                {t('feeAnalysis.momLabel')} {Number(phoneData.mom_change) > 0 ? '+' : ''}{phoneData.mom_change}%
              </div>
            )}
          </Col>
        </Row>

        <Card size="small" title={t('feeAnalysis.monthlyTrend')} style={{ marginBottom: 16 }}>
          <BarChart data={phoneData.rows} />
        </Card>

        <Card size="small" title={t('feeAnalysis.feeList')}>
          <Table columns={phoneDetailColumns} dataSource={phoneData.rows} rowKey="billing_month" size="small"
            pagination={false} scroll={{ x: 1100 }} />
        </Card>
      </>
    );
  }

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }} align="middle">
        <Col>
          <span style={{ marginRight: 8 }}>{t('feeAnalysis.l1BranchColon')}</span>
          <Select
            style={{ width: 220 }}
            placeholder={t('feeAnalysis.allBranches')}
            allowClear
            value={phoneListL1OrgId}
            onChange={setPhoneListL1OrgId}
            showSearch optionFilterProp="label"
            options={l1Orgs.map(o => ({ label: o.name, value: o.id }))}
          />
        </Col>
        <Col flex="auto" />
        <Col>
          <Input.Search
            placeholder={t('feeAnalysis.phoneSearchPlaceholder')}
            allowClear
            style={{ width: 300 }}
            value={phoneSearch}
            onChange={e => setPhoneSearch(e.target.value)}
            onSearch={v => setPhoneSearch(v)}
          />
        </Col>
      </Row>
      <Card size="small" title={t('feeAnalysis.phoneListTitle', { count: filteredPhoneList.length })}>
        <Table
          columns={phoneListColumns}
          dataSource={filteredPhoneList}
          rowKey="phone_number"
          size="small"
          loading={phoneListLoading}
          pagination={{ pageSize: phonePageSize, showSizeChanger: true, pageSizeOptions: ['25', '50', '100'], showTotal: total => t('feeAnalysis.paginationTotal', { total }), onChange: (_p, s) => setPhonePageSize(s) }}
          scroll={{ x: 1100 }}
        />
      </Card>
    </>
  );
}
