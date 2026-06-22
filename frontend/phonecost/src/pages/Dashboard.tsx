import React from 'react';
import { Card, Typography, Row, Col, Statistic } from 'antd';
import { PhoneOutlined, FileTextOutlined, TeamOutlined } from '@ant-design/icons';
const { Title } = Typography;
const Dashboard: React.FC = () => (
  <div>
    <Title level={4}>系统看板</Title>
    <Row gutter={16}>
      <Col span={8}><Card><Statistic title="本月账单" value={0} prefix={<FileTextOutlined />} /></Card></Col>
      <Col span={8}><Card><Statistic title="待确认分摊" value={0} prefix={<PhoneOutlined />} /></Card></Col>
      <Col span={8}><Card><Statistic title="组织数量" value={0} prefix={<TeamOutlined />} /></Card></Col>
    </Row>
  </div>
);
export default Dashboard;
