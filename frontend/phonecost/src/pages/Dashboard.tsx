import { useQuery } from '@tanstack/react-query';
import { Card, Row, Col, Statistic, Typography } from 'antd';
import { TeamOutlined, UserOutlined, FileTextOutlined, DollarOutlined, CheckCircleOutlined, ClockCircleOutlined } from '@ant-design/icons';
import { getDashboardStats } from '../api/dashboard';

const { Title } = Typography;

export default function Dashboard() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: getDashboardStats,
  });

  return (
    <div>
      <Title level={4}>系统看板</Title>
      <Row gutter={[16, 16]}>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title="组织数" value={stats?.org_count ?? 0} prefix={<TeamOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title="用户数" value={stats?.user_count ?? 0} prefix={<UserOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title="账单批次" value={stats?.bill_batch_count ?? 0} prefix={<FileTextOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title="费用总额" value={stats?.total_amount ?? 0} prefix={<DollarOutlined />} precision={2} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title="已确认" value={stats?.confirmed_count ?? 0} prefix={<CheckCircleOutlined />} valueStyle={{ color: '#3f8600' }} />
          </Card>
        </Col>
        <Col xs={12} sm={8} md={4}>
          <Card loading={isLoading}>
            <Statistic title="待确认" value={stats?.pending_count ?? 0} prefix={<ClockCircleOutlined />} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
