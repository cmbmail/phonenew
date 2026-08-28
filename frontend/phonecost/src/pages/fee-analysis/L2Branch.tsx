import { Row, Col, Card, Table, Select, Statistic, Empty } from 'antd';
import { COLORS } from '../../theme/morandi';
import { useTranslation } from 'react-i18next';
import { YoyBarChart, createL1DetailColumns, type L1MonthlyResult, type Organization } from './shared';

interface L2BranchProps {
  l1Orgs: Organization[];
  l2Orgs: Organization[];
  selectedL1OrgId: number | null;
  setSelectedL1OrgId: (id: number | null) => void;
  selectedL2OrgId: number | null;
  setSelectedL2OrgId: (id: number | null) => void;
  l2MonthlyData: L1MonthlyResult | null;
  l2MonthlyLoading: boolean;
}

export default function L2Branch({ l1Orgs, l2Orgs, selectedL1OrgId, setSelectedL1OrgId, selectedL2OrgId, setSelectedL2OrgId, l2MonthlyData, l2MonthlyLoading }: L2BranchProps) {
  const { t } = useTranslation();
  const l1DetailColumns = createL1DetailColumns(t);

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col>
          <span style={{ marginRight: 8 }}>{t('feeAnalysis.selectL1')}</span>
          <Select style={{ width: 240 }} placeholder={t('feeAnalysis.selectL1Placeholder')} value={selectedL1OrgId} onChange={setSelectedL1OrgId}
            showSearch optionFilterProp="label"
            options={l1Orgs.map(o => ({ label: o.name, value: o.id }))} />
        </Col>
        {selectedL1OrgId && (
          <Col>
            <span style={{ marginRight: 8 }}>{t('feeAnalysis.selectL2')}</span>
            <Select style={{ width: 240 }} placeholder={t('feeAnalysis.selectL2Placeholder')} value={selectedL2OrgId} onChange={setSelectedL2OrgId}
              showSearch optionFilterProp="label"
              options={l2Orgs.map(o => ({ label: o.name, value: o.id }))} />
          </Col>
        )}
      </Row>

      {l2MonthlyLoading && <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>{t('feeAnalysis.loading')}</div>}

      {l2MonthlyData && l2MonthlyData.rows && l2MonthlyData.rows.length > 0 ? (
        <>
          <Row gutter={16} style={{ marginBottom: 20 }}>
            <Col span={5}>
              <Statistic title={t('feeAnalysis.l2BranchLabel')} value={l2MonthlyData.org_name} valueStyle={{ fontSize: 18, color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title={t('feeAnalysis.accumulatedFee')} value={Number(l2MonthlyData.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title={t('feeAnalysis.avgMonthlyFee')} value={Number(l2MonthlyData.avg_monthly_fee || 0).toFixed(2)} prefix="¥" />
            </Col>
            <Col span={3}>
              <Statistic title={t('feeAnalysis.dataMonths')} value={l2MonthlyData.month_count} suffix={t('feeAnalysis.monthUnit')} />
            </Col>
          </Row>

          <Card size="small" title={t('feeAnalysis.yoyChart')} style={{ marginBottom: 16 }}>
            <YoyBarChart data={l2MonthlyData.rows} />
          </Card>

          <Card size="small" title={t('feeAnalysis.monthlyDetail')}>
            <Table columns={l1DetailColumns} dataSource={l2MonthlyData.rows} rowKey="billing_month" size="small"
              pagination={false} scroll={{ x: 1100 }} />
          </Card>
        </>
      ) : selectedL2OrgId && !l2MonthlyLoading ? (
        <Empty description={t('feeAnalysis.noFeeData')} />
      ) : !selectedL2OrgId ? (
        <Empty description={selectedL1OrgId ? t('feeAnalysis.pleaseSelectL2') : t('feeAnalysis.pleaseSelectL1First')} />
      ) : null}
    </>
  );
}
