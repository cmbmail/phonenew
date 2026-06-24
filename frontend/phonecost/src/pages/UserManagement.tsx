import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Card,
  Table,
  Form,
  Modal,
  Input,
  Select,
  Button,
  Space,
  Tag,
  Badge,
  message,
  Popconfirm,
} from 'antd';
import { getErrorMessage } from '../types/api';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  KeyOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getUsers, createUser, updateUser, deleteUser, resetPassword } from '../api/user';
import type { UserItem } from '../api/user';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import { ROLE_LABELS, ROLE_OPTIONS } from '../types/organization';
import dayjs from 'dayjs';

export default function UserManagement() {
  const { t } = useTranslation();

  const [users, setUsers] = useState<UserItem[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [resetModalOpen, setResetModalOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<UserItem | null>(null);
  const [addForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [resetForm] = Form.useForm();

  const fetchUsers = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getUsers();
      setUsers(data);
    } catch {
      message.error(t('user.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  const fetchOrgs = useCallback(async () => {
    try {
      const data = await getOrgTree();
      setOrgList(data);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { fetchUsers(); fetchOrgs(); }, [fetchUsers, fetchOrgs]);

  const orgNameMap = useMemo(() => new Map(orgList.map((o) => [o.id, o.name])), [orgList]);

  const handleAdd = async () => {
    try {
      const values = await addForm.validateFields();
      await createUser(values);
      message.success(t('user.createSuccess'));
      setAddModalOpen(false);
      addForm.resetFields();
      fetchUsers();
    } catch (err) {
      message.error(getErrorMessage(err, t('common.failed')));
    }
  };

  const handleEdit = async () => {
    if (!editingUser) return;
    try {
      const values = await editForm.validateFields();
      await updateUser(editingUser.id, values);
      message.success(t('user.updateSuccess'));
      setEditModalOpen(false);
      setEditingUser(null);
      editForm.resetFields();
      fetchUsers();
    } catch (err) {
      message.error(getErrorMessage(err, t('common.failed')));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteUser(id);
      message.success(t('user.deleteSuccess'));
      fetchUsers();
    } catch (err) {
      message.error(getErrorMessage(err, t('user.deleteFailed')));
    }
  };

  const handleReset = async () => {
    if (!editingUser) return;
    try {
      const values = await resetForm.validateFields();
      await resetPassword(editingUser.id, values.new_password);
      message.success(t('user.resetSuccess'));
      setResetModalOpen(false);
      setEditingUser(null);
      resetForm.resetFields();
    } catch (err) {
      message.error(getErrorMessage(err, t('common.failed')));
    }
  };

  const openEdit = (user: UserItem) => {
    setEditingUser(user);
    editForm.setFieldsValue({
      real_name: user.real_name,
      role: user.role,
      org_id: user.org_id,
      status: user.status,
    });
    setEditModalOpen(true);
  };

  const openReset = (user: UserItem) => {
    setEditingUser(user);
    resetForm.resetFields();
    setResetModalOpen(true);
  };

  const columns = [
    { title: t('user.colUsername'), dataIndex: 'username', key: 'username', width: 120 },
    { title: t('user.colRealName'), dataIndex: 'real_name', key: 'real_name', width: 100 },
    {
      title: t('user.colRole'), dataIndex: 'role', key: 'role', width: 110,
      render: (r: number) => <Tag>{ROLE_LABELS[r] || t('allocation.unknown')}</Tag>,
    },
    {
      title: t('user.colOrg'), dataIndex: 'org_id', key: 'org_id', width: 150,
      render: (orgId: number | null) => orgId ? (orgNameMap.get(orgId) || '-') : '-',
    },
    {
      title: t('user.colStatus'), dataIndex: 'status', key: 'status', width: 80,
      render: (s: number) => s === 1 ? <Badge status="success" text={t('user.enabled')} /> : <Badge status="error" text={t('user.disabled')} />,
    },
    {
      title: t('user.colMustChangePwd'), dataIndex: 'must_change_pwd', key: 'must_change_pwd', width: 80,
      render: (v: number) => v === 1 ? <Tag color="orange">{t('user.yes')}</Tag> : <Tag color="default">{t('user.no')}</Tag>,
    },
    {
      title: t('user.colCreatedAt'), dataIndex: 'created_at', key: 'created_at', width: 150,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: t('user.colActions'), key: 'actions', width: 180,
      render: (_unused: unknown, record: UserItem) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>{t('user.editBtn')}</Button>
          <Button size="small" icon={<KeyOutlined />} onClick={() => openReset(record)}>{t('user.resetPwdBtn')}</Button>
          <Popconfirm title={t('user.deleteConfirm')} onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card
        title={t('user.title')}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setAddModalOpen(true)}>
            {t('user.addUser')}
          </Button>
        }
      >
        <Table
          columns={columns}
          dataSource={users}
          rowKey="id"
          size="small"
          loading={loading}
          pagination={{ pageSize: 20 }}
        />
      </Card>

      <Modal
        title={t('user.addModalTitle')}
        open={addModalOpen}
        onOk={handleAdd}
        onCancel={() => { setAddModalOpen(false); addForm.resetFields(); }}
        okText={t('user.createBtn')}
      >
        <Form form={addForm} layout="vertical">
          <Form.Item name="username" label={t('user.formUsername')} rules={[{ required: true, message: t('user.formUsernameRequired') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label={t('user.formPassword')} rules={[{ required: true, message: t('user.formPasswordRequired') }, { min: 6, message: t('user.formNewPwdMin6') }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="real_name" label={t('user.formRealName')} rules={[{ required: true, message: t('user.formRealNameRequired') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="role" label={t('user.formRole')} rules={[{ required: true, message: t('user.formRoleRequired') }]}>
            <Select options={ROLE_OPTIONS} placeholder={t('user.formRoleRequired')} />
          </Form.Item>
          <Form.Item name="org_id" label={t('user.formOrgId')}>
            <Select
              allowClear
              placeholder={t('user.formOrgIdPlaceholder')}
              options={orgList.map((o) => ({ value: o.id, label: o.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="status" label={t('user.formStatus')} initialValue={1}>
            <Select options={[{ value: 1, label: t('user.enabled') }, { value: 0, label: t('user.disabled') }]} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('user.editModalTitle')}
        open={editModalOpen}
        onOk={handleEdit}
        onCancel={() => { setEditModalOpen(false); setEditingUser(null); editForm.resetFields(); }}
        okText={t('user.saveBtn')}
      >
        <Form form={editForm} layout="vertical">
          <Form.Item name="real_name" label={t('user.formRealName')} rules={[{ required: true, message: t('user.formRealNameRequired') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="role" label={t('user.formRole')} rules={[{ required: true, message: t('user.formRoleRequired') }]}>
            <Select options={ROLE_OPTIONS} />
          </Form.Item>
          <Form.Item name="org_id" label={t('user.formOrgId')}>
            <Select
              allowClear
              placeholder={t('user.formOrgIdPlaceholder')}
              options={orgList.map((o) => ({ value: o.id, label: o.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="status" label={t('user.formStatus')} rules={[{ required: true }]}>
            <Select options={[{ value: 1, label: t('user.enabled') }, { value: 0, label: t('user.disabled') }]} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('user.resetModalTitle')}
        open={resetModalOpen}
        onOk={handleReset}
        onCancel={() => { setResetModalOpen(false); setEditingUser(null); resetForm.resetFields(); }}
        okText={t('user.resetOkBtn')}
      >
        <p>{t('user.resetDesc', { username: editingUser?.username || '' })}</p>
        <Form form={resetForm} layout="vertical">
          <Form.Item name="new_password" label={t('user.formNewPwd')} rules={[{ required: true, message: t('user.formNewPwdRequired') }, { min: 6, message: t('user.formNewPwdMin6') }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="confirm_password" label={t('user.formConfirmPwd')} dependencies={['new_password']} rules={[
            { required: true, message: t('user.formConfirmPwdRequired') },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('new_password') === value) return Promise.resolve();
                return Promise.reject(new Error(t('user.pwdMismatch')));
              },
            }),
          ]}>
            <Input.Password />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
