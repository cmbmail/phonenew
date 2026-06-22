import { useState, useEffect } from 'react';
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
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  KeyOutlined,
} from '@ant-design/icons';
import { getUsers, createUser, updateUser, deleteUser, resetPassword } from '../api/user';
import type { UserItem } from '../api/user';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import { ROLE_LABELS, ROLE_OPTIONS } from '../types/organization';
import dayjs from 'dayjs';

export default function UserManagement() {
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

  const fetchUsers = async () => {
    setLoading(true);
    try {
      const data = await getUsers();
      setUsers(data);
    } catch {
      message.error('获取用户列表失败');
    } finally {
      setLoading(false);
    }
  };

  const fetchOrgs = async () => {
    try {
      const data = await getOrgTree();
      setOrgList(data);
    } catch { /* ignore */ }
  };

  useEffect(() => { fetchUsers(); fetchOrgs(); }, []);

  const orgNameMap = new Map(orgList.map((o) => [o.id, o.name]));

  const handleAdd = async () => {
    try {
      const values = await addForm.validateFields();
      await createUser(values);
      message.success('用户创建成功');
      setAddModalOpen(false);
      addForm.resetFields();
      fetchUsers();
    } catch (err: any) {
      if (err?.response?.data?.message) message.error(err.response.data.message);
    }
  };

  const handleEdit = async () => {
    if (!editingUser) return;
    try {
      const values = await editForm.validateFields();
      await updateUser(editingUser.id, values);
      message.success('用户更新成功');
      setEditModalOpen(false);
      setEditingUser(null);
      editForm.resetFields();
      fetchUsers();
    } catch (err: any) {
      if (err?.response?.data?.message) message.error(err.response.data.message);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteUser(id);
      message.success('删除成功');
      fetchUsers();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '删除失败');
    }
  };

  const handleReset = async () => {
    if (!editingUser) return;
    try {
      const values = await resetForm.validateFields();
      await resetPassword(editingUser.id, values.new_password);
      message.success('密码重置成功');
      setResetModalOpen(false);
      setEditingUser(null);
      resetForm.resetFields();
    } catch (err: any) {
      if (err?.response?.data?.message) message.error(err.response.data.message);
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
    { title: '用户名', dataIndex: 'username', key: 'username', width: 120 },
    { title: '姓名', dataIndex: 'real_name', key: 'real_name', width: 100 },
    {
      title: '角色', dataIndex: 'role', key: 'role', width: 110,
      render: (r: number) => <Tag>{ROLE_LABELS[r] || '未知'}</Tag>,
    },
    {
      title: '所属组织', dataIndex: 'org_id', key: 'org_id', width: 150,
      render: (orgId: number | null) => orgId ? (orgNameMap.get(orgId) || '-') : '-',
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (s: number) => s === 1 ? <Badge status="success" text="启用" /> : <Badge status="error" text="停用" />,
    },
    {
      title: '需改密', dataIndex: 'must_change_pwd', key: 'must_change_pwd', width: 80,
      render: (v: number) => v === 1 ? <Tag color="orange">是</Tag> : <Tag color="default">否</Tag>,
    },
    {
      title: '创建时间', dataIndex: 'created_at', key: 'created_at', width: 150,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作', key: 'actions', width: 180,
      render: (_: any, record: UserItem) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>
          <Button size="small" icon={<KeyOutlined />} onClick={() => openReset(record)}>重置密码</Button>
          <Popconfirm title="确定删除该用户？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card
        title="用户管理"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setAddModalOpen(true)}>
            新增用户
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
        title="新增用户"
        open={addModalOpen}
        onOk={handleAdd}
        onCancel={() => { setAddModalOpen(false); addForm.resetFields(); }}
        okText="创建"
      >
        <Form form={addForm} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="real_name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select options={ROLE_OPTIONS} placeholder="选择角色" />
          </Form.Item>
          <Form.Item name="org_id" label="所属组织">
            <Select
              allowClear
              placeholder="选择组织"
              options={orgList.map((o) => ({ value: o.id, label: o.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue={1}>
            <Select options={[{ value: 1, label: '启用' }, { value: 0, label: '停用' }]} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编辑用户"
        open={editModalOpen}
        onOk={handleEdit}
        onCancel={() => { setEditModalOpen(false); setEditingUser(null); editForm.resetFields(); }}
        okText="保存"
      >
        <Form form={editForm} layout="vertical">
          <Form.Item name="real_name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select options={ROLE_OPTIONS} />
          </Form.Item>
          <Form.Item name="org_id" label="所属组织">
            <Select
              allowClear
              placeholder="选择组织"
              options={orgList.map((o) => ({ value: o.id, label: o.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select options={[{ value: 1, label: '启用' }, { value: 0, label: '停用' }]} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="重置密码"
        open={resetModalOpen}
        onOk={handleReset}
        onCancel={() => { setResetModalOpen(false); setEditingUser(null); resetForm.resetFields(); }}
        okText="确认重置"
      >
        <p>为用户 <strong>{editingUser?.username}</strong> 重置密码：</p>
        <Form form={resetForm} layout="vertical">
          <Form.Item name="new_password" label="新密码" rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '密码至少6位' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="confirm_password" label="确认密码" dependencies={['new_password']} rules={[
            { required: true, message: '请确认密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('new_password') === value) return Promise.resolve();
                return Promise.reject(new Error('两次密码不一致'));
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
