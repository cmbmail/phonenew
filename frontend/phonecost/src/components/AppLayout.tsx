import React, { useState } from 'react';
import { Layout, Menu, Avatar, Dropdown, Typography, Popconfirm } from 'antd';
import { DashboardOutlined, FileTextOutlined, PhoneOutlined, TeamOutlined, SettingOutlined, LogoutOutlined, ImportOutlined } from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
const { Sider, Header, Content } = Layout;
const { Text } = Typography;

const menuItems = [
  { key: '/', icon: <DashboardOutlined />, label: '系统看板' },
  { key: '/bill', icon: <FileTextOutlined />, label: '账单管理' },
  { key: '/import', icon: <ImportOutlined />, label: '数据导入' },
  { key: '/allocation', icon: <PhoneOutlined />, label: '费用分摊' },
  { key: '/org', icon: <TeamOutlined />, label: '组织架构' },
  { key: '/settings', icon: <SettingOutlined />, label: '系统管理' },
];

const AppLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(true);
  const navigate = useNavigate();
  const location = useLocation();
  const { username, realName, logout } = useAuthStore();
  const userMenu = { items: [
    { key: 'change-pwd', icon: <SettingOutlined />, label: '修改密码' },
    { type: 'divider' as const },
    { key: 'logout', icon: <LogoutOutlined />, label: <Popconfirm title="确定退出登录？" onConfirm={() => { logout(); navigate('/login'); }}>退出登录</Popconfirm> },
  ]};

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider collapsible collapsed={collapsed} onCollapse={setCollapsed} style={{ overflow: 'auto', height: '100vh', position: 'fixed', left: 0 }}>
        <div style={{ height: 32, margin: 16, background: 'rgba(255,255,255,0.2)', borderRadius: 6, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: '#fff', fontSize: collapsed ? 14 : 16, fontWeight: 'bold' }}>{collapsed ? 'PC' : '费用分摊'}</Text>
        </div>
        <Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} items={menuItems} onClick={({ key }) => navigate(key)} />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 80 : 200, transition: 'margin-left 0.2s' }}>
        <Header style={{ padding: '0 24px', background: '#fff', display: 'flex', justifyContent: 'flex-end', alignItems: 'center', borderBottom: '1px solid #f0f0f0' }}>
          <Dropdown menu={userMenu}><div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}><Avatar size="small">{(realName || username || '?')[0]}</Avatar><Text>{realName || username}</Text></div></Dropdown>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#fff', borderRadius: 8, minHeight: 360 }}><Outlet /></Content>
      </Layout>
    </Layout>
  );
};
export default AppLayout;
