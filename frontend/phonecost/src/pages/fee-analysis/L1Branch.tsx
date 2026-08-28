import { Row, Col, Card, Table, Select, Statistic, Empty } from 'antd';
import { COLORS } from '../../theme/morandi';
import { useTranslation } from 'react-i18next';
import { YoyBarChart, createL1DetailColumns, type L1MonthlyResult, type Organization } from './shared';

interface L1BranchProps {
  l1Orgs: Organization[];
  selectedL1OrgId: number | null;
  setSelectedL1OrgId: (id: number | null) => void;
  l1MonthlyData: L1MonthlyResult | null;
  l1MonthlyLoading: boolean;
}

export default function L1Branch({ l1Orgs, selectedL1OrgId, setSelectedL1OrgId, l1MonthlyData, l1MonthlyLoading }: L1BranchProps) {
  const { t } = useTranslation();
  const l1DetailColumns = createL1DetailColumns(t);

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col>
          <span style={{ marginRight: 8 }}>{t('feeAnalysis.selectL1')}</span>
          <Select style={{ width: 260 }} placeholder={t('feeAnalysis.selectL1Placeholder')} value={selectedL1OrgId} onChange={setSelectedL1OrgId}
            showSearch optionFilterProp="label"
            options={l1Orgs.map(o => ({ label: o.name, value: o.id }))} />
        </Col>
      </Row>

      {l1MonthlyLoading && <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>{t('feeAnalysis.loading')}</div>}

      {l1MonthlyData && l1MonthlyData.rows && l1MonthlyData.rows.length > 0 ? (
        <>
          <Row gutter={16} style={{ marginBottom: 20 }}>
            <Col span={5}>
              <Statistic title={t('feeAnalysis.l1Branch')} value={l1MonthlyData.org_name} valueStyle={{ fontSize: 18, color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title={t('feeAnalysis.accumulatedFee')} value={Number(l1MonthlyData.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title={t('feeAnalysis.avgMonthlyFee')} value={Number(l1MonthlyData.avg_monthly_fee || 0).toFixed(2)} prefix="¥" />
            </Col>
            <Col span={3}>
              <Statistic title={t('feeAnalysis.dataMonths')} value={l1MonthlyData.month_count} suffix={t('feeAnalysis.monthUnit')} />
            </Col>
          </Row>

          <Card size="small" title={t('feeAnalysis.yoyChart')} style={{ marginBottom: 16 }}>
            <YoyBarChart data={l1MonthlyData.rows} />
          </Card>

          <Card size="small" title={t('feeAnalysis.monthlyDetail')}>
            <Table columns={l1DetailColumns} dataSource={l1MonthlyData.rows} rowKey="billing_month" size="small"
              pagination={false} scroll={{ x: 1100 }} />
          </Card>
        </>
      ) : selectedL1OrgId && !l1MonthlyLoading ? (
        <Empty description={t('feeAnalysis.noFeeData')} />
      ) : !selectedL1OrgId ? (
        <Empty description={t('feeAnalysis.pleaseSelectL1')} />
      ) : null}
    </>
  );
}
