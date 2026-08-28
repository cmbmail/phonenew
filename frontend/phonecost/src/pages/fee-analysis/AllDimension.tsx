import { Row, Col, Card, Table, Statistic } from 'antd';
import { COLORS } from '../../theme/morandi';
import { useTranslation } from 'react-i18next';
import { BarChart, money, type BarRow } from './shared';

interface AllDimensionProps {
  allMonthlyData: BarRow[];
  allMonthlyLoading: boolean;
}

export default function AllDimension({ allMonthlyData, allMonthlyLoading }: AllDimensionProps) {
  const { t } = useTranslation();

  const allMonthlyColumns = [
    { title: t('feeAnalysis.monthCol'), dataIndex: 'billing_month', key: 'billing_month', width: 100, fixed: 'left' as const },
    { title: t('feeAnalysis.totalFee'), dataIndex: 'total_fee', key: 'total_fee', width: 120, render: (v: number) => <strong>{money(v)}</strong>,
      sorter: (a: BarRow, b: BarRow) => (a.total_fee || 0) - (b.total_fee || 0) },
    { title: t('feeAnalysis.monthlyRent'), dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100, render: money },
    { title: t('feeAnalysis.callFee'), dataIndex: 'call_fee', key: 'call_fee', width: 100, render: money },
    { title: t('feeAnalysis.recordingFee'), dataIndex: 'recording_fee', key: 'recording_fee', width: 100, render: money },
    { title: t('feeAnalysis.crbtFee'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100, render: money },
    { title: t('feeAnalysis.flashMsgFee'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100, render: money },
    { title: t('feeAnalysis.phoneCount'), dataIndex: 'phone_count', key: 'phone_count', width: 80 },
    { title: t('feeAnalysis.orgCount'), dataIndex: 'org_count', key: 'org_count', width: 80 },
  ];

  if (allMonthlyLoading) return <div style={{ textAlign: 'center', padding: 40, color: COLORS.textMuted }}>{t('feeAnalysis.loading')}</div>;

  const grandTotal = allMonthlyData.reduce((s, r) => s + (Number(r.total_fee) || 0), 0);
  const avgMonthly = allMonthlyData.length > 0 ? grandTotal / allMonthlyData.length : 0;
  const lastRow = allMonthlyData[allMonthlyData.length - 1];
  const phoneCount = lastRow?.phone_count || 0;
  const orgCount = lastRow?.org_count || 0;

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 20 }}>
        <Col span={6}><Statistic title={t('feeAnalysis.grandTotal')} value={grandTotal.toFixed(2)} prefix="¥" valueStyle={{ color: COLORS.sage }} /></Col>
        <Col span={6}><Statistic title={t('feeAnalysis.avgMonthlyFee')} value={avgMonthly.toFixed(2)} prefix="¥" /></Col>
        <Col span={4}><Statistic title={t('feeAnalysis.phoneCount')} value={phoneCount} /></Col>
        <Col span={4}><Statistic title={t('feeAnalysis.orgCount')} value={orgCount} /></Col>
        <Col span={4}><Statistic title={t('feeAnalysis.dataMonths')} value={allMonthlyData.length} suffix={t('feeAnalysis.monthUnit')} /></Col>
      </Row>

      <Card size="small" title={t('feeAnalysis.totalTrend')} style={{ marginBottom: 16 }}>
        <BarChart data={allMonthlyData} field="total_fee" />
      </Card>

      <Card size="small" title={t('feeAnalysis.monthlyDetail')}>
        <Table columns={allMonthlyColumns} dataSource={allMonthlyData} rowKey="billing_month" size="small"
          pagination={false} scroll={{ x: 880 }} />
      </Card>
    </>
  );
}
