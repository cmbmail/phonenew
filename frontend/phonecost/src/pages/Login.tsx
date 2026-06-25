import React from 'react';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { apiPost } from '../lib/request';
import { useAuthStore } from '../store/auth';
import { getErrorMessage } from '../types/api';
import type { LoginResponse } from '../types/auth';
import { useTranslation } from 'react-i18next';
import { COLORS } from '../theme/morandi';

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
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: `linear-gradient(135deg, ${COLORS.cream} 0%, #E8E5E0 100%)` }}>
      <Card style={{ width: 400, borderRadius: 16, boxShadow: '0 8px 32px rgba(0,0,0,0.08)', border: 'none' }}>
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <div style={{ width: 56, height: 56, borderRadius: 14, background: COLORS.charcoal, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', marginBottom: 16 }}>
            <span style={{ color: COLORS.white, fontSize: 22, fontWeight: 700, letterSpacing: 1 }}>PC</span>
          </div>
          <Title level={3} style={{ margin: 0, color: COLORS.textDark }}>{t('login.title')}</Title>
        </div>
        <Form form={form} onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: t('login.usernameRequired') }]}>
            <Input prefix={<UserOutlined />} placeholder={t('login.usernamePlaceholder')} autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: t('login.passwordRequired') }]}>
            <Input.Password prefix={<LockOutlined />} placeholder={t('login.passwordPlaceholder')} autoComplete="current-password" />
          </Form.Item>
          <Form.Item><Button type="primary" htmlType="submit" loading={loading} block style={{ height: 42, fontSize: 15 }}>{t('login.loginBtn')}</Button></Form.Item>
        </Form>
      </Card>
    </div>
  );
};
export default Login;
