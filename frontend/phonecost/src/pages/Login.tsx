import React from 'react';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { apiPost } from '../lib/request';
import { useAuthStore } from '../store/auth';
import { getErrorMessage } from '../types/api';
import type { LoginResponse } from '../types/auth';
import { useTranslation } from 'react-i18next';

const { Title } = Typography;

const Login: React.FC = () => {
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [loading, setLoading] = React.useState(false);
  const { t } = useTranslation();

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const data = await apiPost<LoginResponse>('/auth/login', values);
      setAuth(data);
      message.success(t('login.loginSuccess'));
      navigate('/');
    } catch (err) { message.error(getErrorMessage(err, t('login.loginFailed'))); }
    finally { setLoading(false); }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }}>
        <Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>{t('login.title')}</Title>
        <Form form={form} onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: t('login.usernameRequired') }]}>
            <Input prefix={<UserOutlined />} placeholder={t('login.usernamePlaceholder')} autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: t('login.passwordRequired') }]}>
            <Input.Password prefix={<LockOutlined />} placeholder={t('login.passwordPlaceholder')} autoComplete="current-password" />
          </Form.Item>
          <Form.Item><Button type="primary" htmlType="submit" loading={loading} block>{t('login.loginBtn')}</Button></Form.Item>
        </Form>
      </Card>
    </div>
  );
};
export default Login;
