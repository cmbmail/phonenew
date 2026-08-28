import { Row, Col, Card, Table, Select, Statistic, Empty } from 'antd';
import { COLORS } from '../../theme/morandi';
import { useTranslation } from 'react-i18next';
import { YoyBarChart, createL1DetailColumns, type L1MonthlyResult } from './shared';

interface DepartmentProps {
  deptOrgOptions: { label: string; value: number }[];
  selectedDeptOrgId: number | null;
  setSelectedDeptOrgId: (id: number | null) => void;
  deptMonthlyData: L1MonthlyResult | null;
  deptMonthlyLoading: boolean;
}

export default function Department({ deptOrgOptions, selectedDeptOrgId, setSelectedDeptOrgId, deptMonthlyData, deptMonthlyLoading }: DepartmentProps) {
  const { t } = useTranslation();
  const l1DetailColumns = createL1DetailColumns(t);

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col>
          <span style={{ marginRight: 8 }}>{t('feeAnalysis.selectDept')}</span>
          <Select style={{ width: 320 }} placeholder={t('feeAnalysis.selectDeptPlaceholder')} showSearch optionFilterProp="label"
            value={selectedDeptOrgId} onChange={setSelectedDeptOrgId} options={deptOrgOptions} />
        </Col>
      </Row>

      {deptMonthlyLoading && <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>{t('feeAnalysis.loading')}</div>}

      {deptMonthlyData && deptMonthlyData.rows && deptMonthlyData.rows.length > 0 ? (
        <>
          <Row gutter={16} style={{ marginBottom: 20 }}>
            <Col span={5}>
              <Statistic title={t('feeAnalysis.deptLabel')} value={deptMonthlyData.org_name} valueStyle={{ fontSize: 18, color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title={t('feeAnalysis.accumulatedFee')} value={Number(deptMonthlyData.total_fee || 0).toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} />
            </Col>
            <Col span={4}>
              <Statistic title={t('feeAnalysis.avgMonthlyFee')} value={Number(deptMonthlyData.avg_monthly_fee || 0).toFixed(2)} prefix="¥" />
            </Col>
            <Col span={3}>
              <Statistic title={t('feeAnalysis.dataMonths')} value={deptMonthlyData.month_count} suffix={t('feeAnalysis.monthUnit')} />
            </Col>
          </Row>

          <Card size="small" title={t('feeAnalysis.yoyChart')} style={{ marginBottom: 16 }}>
            <YoyBarChart data={deptMonthlyData.rows} />
          </Card>

          <Card size="small" title={t('feeAnalysis.monthlyDetail')}>
            <Table columns={l1DetailColumns} dataSource={deptMonthlyData.rows} rowKey="billing_month" size="small"
              pagination={false} scroll={{ x: 1100 }} />
          </Card>
        </>
      ) : selectedDeptOrgId && !deptMonthlyLoading ? (
        <Empty description={t('feeAnalysis.noDeptFeeData')} />
      ) : !selectedDeptOrgId ? (
        <Empty description={t('feeAnalysis.pleaseSelectDept')} />
      ) : null}
    </>
  );
}
