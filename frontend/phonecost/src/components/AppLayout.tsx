import React, { useState } from 'react';
import { Layout, Menu, Avatar, Dropdown, Typography, Popconfirm, Modal, Form, Input, message } from 'antd';
import { DashboardOutlined, FileTextOutlined, PhoneOutlined, TeamOutlined, SettingOutlined, LogoutOutlined, ImportOutlined } from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import { apiPost } from '../lib/request';
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
  const [changePwdOpen, setChangePwdOpen] = useState(false);
  const [changePwdLoading, setChangePwdLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const location = useLocation();
  const { username, realName, mustChangePwd, clearMustChangePwd, logout } = useAuthStore();

  const handleChangePwd = async () => {
    try {
      const values = await form.validateFields();
      setChangePwdLoading(true);
      await apiPost('/auth/change-password', { old_password: values.old_password, new_password: values.new_password });
      message.success('密码修改成功');
      setChangePwdOpen(false);
      clearMustChangePwd();
      form.resetFields();
    } catch (e: any) {
      message.error(e?.response?.data?.message || '密码修改失败');
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
        <Menu theme="dark" mode="inline" selectedKeys={[location.pathname]} items={menuItems} onClick={({ key }) => navigate(key)} />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 80 : 200, transition: 'margin-left 0.2s' }}>
        <Header style={{ padding: '0 24px', background: '#fff', display: 'flex', justifyContent: 'flex-end', alignItems: 'center', borderBottom: '1px solid #f0f0f0' }}>
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
