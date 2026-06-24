import React, { useState, useMemo } from 'react';
import { Layout, Menu, Avatar, Dropdown, Typography, Popconfirm, Modal, Form, Input, message } from 'antd';
import { DashboardOutlined, FileTextOutlined, PhoneOutlined, TeamOutlined, SettingOutlined, LogoutOutlined, ImportOutlined, ToolOutlined, BankOutlined, BranchesOutlined, DatabaseOutlined, NumberOutlined, ApartmentOutlined, UserSwitchOutlined } from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import { getErrorMessage } from '../types/api';
import { apiPost } from '../lib/request';
import { useTranslation } from 'react-i18next';
const { Sider, Header, Content } = Layout;
const { Text } = Typography;

interface MenuItemDef {
  key: string;
  icon: React.ReactNode;
  label: string;
  roles?: number[]; // undefined = all roles, otherwise only listed roles can see
  children?: MenuItemDef[];
}

const allMenuItems: MenuItemDef[] = [
  { key: '/', icon: <DashboardOutlined />, label: '系统看板' },
  { key: '/bill', icon: <FileTextOutlined />, label: '账单管理' },
  { key: '/import', icon: <ImportOutlined />, label: '数据导入', roles: [1, 2, 4] },
  {
    key: '/allocation-group',
    icon: <PhoneOutlined />,
    label: '费用分摊',
    children: [
      { key: '/allocation', icon: <PhoneOutlined />, label: '分摊汇总' },
      { key: '/allocation/branch', icon: <BankOutlined />, label: '一级分行' },
      { key: '/allocation/sub-branch', icon: <BranchesOutlined />, label: '二级分行' },
    ],
  },
  { key: '/org', icon: <TeamOutlined />, label: '组织架构' },
  {
    key: '/base-data-group',
    icon: <DatabaseOutlined />,
    label: '基础数据',
    children: [
      { key: '/base/phone-ownership', icon: <NumberOutlined />, label: '号码归属' },
      { key: '/base/cost-center', icon: <ApartmentOutlined />, label: '成本中心' },
      { key: '/base/dept-ownership', icon: <UserSwitchOutlined />, label: '部门归属' },
    ],
  },
  { key: '/settings', icon: <SettingOutlined />, label: '系统管理', roles: [1] },
  { key: '/templates', icon: <ToolOutlined />, label: '模板管理', roles: [1] },
];

const AppLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(true);
  const [changePwdOpen, setChangePwdOpen] = useState(false);
  const [changePwdLoading, setChangePwdLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const location = useLocation();
  const { username, realName, role, mustChangePwd, clearMustChangePwd, logout } = useAuthStore();
  const { t, i18n } = useTranslation();

  // Filter menu items by role
  const menuItems = useMemo(() => {
    if (!role) return allMenuItems.map(({ key, icon, label, children }) => ({ key, icon, label, children }));
    return allMenuItems
      .filter(item => !item.roles || item.roles.includes(role))
      .map(({ key, icon, label, children }) => {
        if (children) {
          const filteredChildren = children
            .filter(c => !c.roles || c.roles.includes(role))
            .map(({ key, icon, label }) => ({ key, icon, label }));
          return { key, icon, label, children: filteredChildren };
        }
        return { key, icon, label };
      });
  }, [role]);

  const handleChangePwd = async () => {
    try {
      const values = await form.validateFields();
      setChangePwdLoading(true);
      await apiPost('/auth/change-password', { old_password: values.old_password, new_password: values.new_password });
      message.success('密码修改成功');
      setChangePwdOpen(false);
      clearMustChangePwd();
      form.resetFields();
    } catch (e) {
      message.error(getErrorMessage(e, '密码修改失败'));
    } finally {
      setChangePwdLoading(false);
    }
  };

  const userMenu = { items: [
    { key: 'change-pwd', icon: <SettingOutlined />, label: '修改密码', onClick: () => setChangePwdOpen(true) },
    { type: 'divider' as const },
    { key: 'logout', icon: <LogoutOutlined />, label: <Popconfirm title="确定退出登录？" onConfirm={() => { logout(); navigate('/login'); }}>退出登录</Popconfirm> },
  ]};

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider collapsible collapsed={collapsed} onCollapse={setCollapsed} style={{ overflow: 'auto', height: '100vh', position: 'fixed', left: 0 }}>
        <div style={{ height: 32, margin: 16, background: 'rgba(255,255,255,0.2)', borderRadius: 6, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: '#fff', fontSize: collapsed ? 14 : 16, fontWeight: 'bold' }}>{collapsed ? 'PC' : '费用分摊'}</Text>
        </div>
        <Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} defaultOpenKeys={['/allocation-group']} items={menuItems} onClick={({ key }) => navigate(key)} />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 80 : 200, transition: 'margin-left 0.2s' }}>
        <Header style={{ padding: '0 24px', background: '#fff', display: 'flex', justifyContent: 'flex-end', alignItems: 'center', borderBottom: '1px solid #f0f0f0', gap: 16 }}>
          <Dropdown menu={userMenu}><div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}><Avatar size="small">{(realName || username || '?')[0]}</Avatar><Text>{realName || username}</Text></div></Dropdown>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#fff', borderRadius: 8, minHeight: 360 }}><Outlet /></Content>
      </Layout>
      <Modal title="修改密码" open={changePwdOpen || mustChangePwd} onCancel={mustChangePwd ? undefined : () => setChangePwdOpen(false)} footer={null} closable={!mustChangePwd} maskClosable={!mustChangePwd}>
        {mustChangePwd && <Typography.Paragraph type="warning" style={{ marginBottom: 16 }}>首次登录需要修改密码后才能使用系统</Typography.Paragraph>}
        <Form form={form} layout="vertical" onFinish={handleChangePwd}>
          <Form.Item name="old_password" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}><Input.Password /></Form.Item>
          <Form.Item name="new_password" label="新密码" rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '密码至少6位' }]}><Input.Password /></Form.Item>
          <Form.Item name="confirm_password" label="确认新密码" dependencies={['new_password']} rules={[{ required: true, message: '请确认新密码' }, ({ getFieldValue }) => ({ validator(_, value) { return value && value !== getFieldValue('new_password') ? Promise.reject('两次密码不一致') : Promise.resolve(); } })]}><Input.Password /></Form.Item>
          <Form.Item><button type="submit" style={{ width: '100%', padding: '8px 16px', background: '#1677ff', color: '#fff', border: 'none', borderRadius: 6, cursor: changePwdLoading ? 'not-allowed' : 'pointer' }} disabled={changePwdLoading}>{changePwdLoading ? '提交中...' : '确定'}</button></Form.Item>
        </Form>
      </Modal>
    </Layout>
  );
};
export default AppLayout;
