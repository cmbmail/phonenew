import { useQuery } from '@tanstack/react-query';
import { Card, Row, Col, Statistic, Typography } from 'antd';
import { TeamOutlined, UserOutlined, FileTextOutlined, DollarOutlined, CheckCircleOutlined, ClockCircleOutlined } from '@ant-design/icons';
import { getDashboardStats } from '../api/dashboard';
import { useTranslation } from 'react-i18next';

const { Title } = Typography;

export default function Dashboard() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: getDashboardStats,
  });
  const { t } = useTranslation();

  return (
    <div>
      <Title level={4}>{t('dashboard.title')}</Title>
      <Row gutter={[16, 16]}>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title={t('dashboard.orgCount')} value={stats?.org_count ?? 0} prefix={<TeamOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title={t('dashboard.userCount')} value={stats?.user_count ?? 0} prefix={<UserOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title={t('dashboard.billBatchCount')} value={stats?.bill_batch_count ?? 0} prefix={<FileTextOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title={t('dashboard.totalAmount')} value={stats?.total_amount ?? 0} prefix={<DollarOutlined />} precision={2} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title={t('dashboard.confirmedCount')} value={stats?.confirmed_count ?? 0} prefix={<CheckCircleOutlined />} valueStyle={{ color: '#3f8600' }} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title={t('dashboard.pendingCount')} value={stats?.pending_count ?? 0} prefix={<ClockCircleOutlined />} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
