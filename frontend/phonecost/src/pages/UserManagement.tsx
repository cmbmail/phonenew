import { useState, useEffect, useCallback, useMemo } from 'react';
import { COLORS } from '../theme/morandi';
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
  Tree,
  Row,
  Col,
  TreeSelect,
  Typography,
} from 'antd';
import { getErrorMessage } from '../types/api';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  KeyOutlined,
  ApartmentOutlined,
} from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import { useTranslation } from 'react-i18next';
import { getUsers, createUser, updateUser, deleteUser, resetPassword } from '../api/user';
import type { UserItem } from '../api/user';
import { getOrgTree } from '../api/org';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS, ROLE_LABELS, ROLE_OPTIONS } from '../types/organization';
import dayjs from 'dayjs';

const ORG_TYPE_COLORS: Record<number, string> = {
  1: COLORS.danger,
  2: COLORS.pending,
  3: COLORS.slate,
  4: COLORS.confirmed,
  5: COLORS.mauve,
  6: COLORS.sage,
};

function buildTree(list: Organization[]): DataNode[] {
  const map = new Map<number, DataNode>();
  const roots: DataNode[] = [];
  list.forEach((org) => {
    map.set(org.id, { key: org.id, title: org.name, children: [] });
  });
  list.forEach((org) => {
    const node = map.get(org.id)!;
    if (org.parent_id && map.has(org.parent_id)) {
      map.get(org.parent_id)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  const markLeaf = (nodes: DataNode[]) => {
    nodes.forEach((n) => {
      if (!n.children || n.children.length === 0) n.isLeaf = true;
      else markLeaf(n.children);
    });
  };
  markLeaf(roots);
  return roots;
}

function buildTreeSelectData(list: Organization[]): DataNode[] {
  const map = new Map<number, DataNode>();
  const roots: DataNode[] = [];
  list.forEach((org) => {
    map.set(org.id, {
      key: org.id,
      value: org.id,
      title: org.name,
      children: [],
    });
  });
  list.forEach((org) => {
    const node = map.get(org.id)!;
    if (org.parent_id && map.has(org.parent_id)) {
      map.get(org.parent_id)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  const markLeaf = (nodes: DataNode[]) => {
    nodes.forEach((n) => {
      if (!n.children || n.children.length === 0) n.isLeaf = true;
      else markLeaf(n.children);
    });
  };
  markLeaf(roots);
  return roots;
}

export default function UserManagement() {
  const { t } = useTranslation();

  const [users, setUsers] = useState<UserItem[]>([]);
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [treeData, setTreeData] = useState<DataNode[]>([]);
  const [treeSelectData, setTreeSelectData] = useState<DataNode[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const [selectedOrgId, setSelectedOrgId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [resetModalOpen, setResetModalOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<UserItem | null>(null);
  const [addForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [resetForm] = Form.useForm();

  const fetchUsers = useCallback(async (orgId?: number) => {
    setLoading(true);
    try {
      const data = await getUsers(orgId);
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
      const tree = buildTree(data);
      setTreeData(tree);
      setTreeSelectData(buildTreeSelectData(data));
      // Default expand root level
      const rootKeys = tree.map(n => n.key);
      setExpandedKeys([...rootKeys]);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { fetchOrgs(); fetchUsers(); }, [fetchOrgs, fetchUsers]);

  const orgNameMap = useMemo(() => new Map(orgList.map((o) => [o.id, o.name])), [orgList]);

  const selectedOrg = useMemo(() => {
    if (selectedOrgId == null) return null;
    return orgList.find(o => o.id === selectedOrgId) || null;
  }, [selectedOrgId, orgList]);

  // Count users per org
  const orgUserCount = useMemo(() => {
    const counts = new Map<number, number>();
    users.forEach(u => {
      if (u.org_id != null) counts.set(u.org_id, (counts.get(u.org_id) || 0) + 1);
    });
    return counts;
  }, [users]);

  const handleTreeSelect = (_selectedKeys: React.Key[], info: { node: { key: React.Key } }) => {
    const orgId = info.node.key as number;
    setSelectedOrgId(orgId);
    fetchUsers(orgId);
  };

  const handleShowAll = () => {
    setSelectedOrgId(null);
    fetchUsers();
  };

  const handleAdd = async () => {
    try {
      const values = await addForm.validateFields();
      await createUser(values);
      message.success(t('user.createSuccess'));
      setAddModalOpen(false);
      addForm.resetFields();
      fetchUsers(selectedOrgId ?? undefined);
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
      fetchUsers(selectedOrgId ?? undefined);
    } catch (err) {
      message.error(getErrorMessage(err, t('common.failed')));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteUser(id);
      message.success(t('user.deleteSuccess'));
      fetchUsers(selectedOrgId ?? undefined);
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
    { title: '用户名', dataIndex: 'username', key: 'username', width: 120 },
    { title: '姓名', dataIndex: 'real_name', key: 'real_name', width: 100 },
    {
      title: '角色', dataIndex: 'role', key: 'role', width: 110,
      render: (r: number) => <Tag color={r === 1 ? COLORS.sage : r === 2 ? COLORS.slate : r === 3 ? COLORS.taupe : COLORS.mauve}>{ROLE_LABELS[r] || '未知'}</Tag>,
    },
    {
      title: '所属组织', dataIndex: 'org_id', key: 'org_id', width: 180,
      render: (orgId: number | null) => orgId ? (orgNameMap.get(orgId) || '-') : '-',
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 70, align: 'center' as const,
      render: (s: number) => s === 1 ? <Badge status="success" text="启用" /> : <Badge status="error" text="停用" />,
    },
    {
      title: '改密', dataIndex: 'must_change_pwd', key: 'must_change_pwd', width: 60, align: 'center' as const,
      render: (v: number) => v === 1 ? <Tag color={COLORS.pending} style={{ fontSize: 11 }}>是</Tag> : <span style={{ color: '#ddd' }}>-</span>,
    },
    {
      title: '创建时间', dataIndex: 'created_at', key: 'created_at', width: 140,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作', key: 'actions', width: 180,
      render: (_unused: unknown, record: UserItem) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>
          <Button size="small" icon={<KeyOutlined />} onClick={() => openReset(record)}>重置密码</Button>
          <Popconfirm title="确认删除该用户？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <style>{`
        .user-org-tree-node { display: inline-flex; align-items: center; gap: 6px; width: 100%; padding: 2px 0; }
        .user-org-tree-count { font-size: 11px; color: ${COLORS.textMuted}; margin-left: auto; }
      `}</style>

      <Row gutter={16}>
        {/* Left: Organization tree */}
        <Col span={6}>
          <Card
            title={
              <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <ApartmentOutlined style={{ fontSize: 16 }} />
                <span>组织架构</span>
                <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
                  ({orgList.length})
                </Typography.Text>
              </span>
            }
            size="small"
            extra={
              <Button size="small" type={selectedOrgId == null ? 'primary' : 'default'} onClick={handleShowAll}>
                全部
              </Button>
            }
            styles={{ body: { padding: '8px 12px', maxHeight: 'calc(100vh - 180px)', overflowY: 'auto' } }}
          >
            <Tree
              treeData={treeData}
              expandedKeys={expandedKeys}
              onExpand={setExpandedKeys}
              onSelect={handleTreeSelect}
              showLine
              selectedKeys={selectedOrgId != null ? [selectedOrgId] : []}
              titleRender={(node: DataNode) => {
                const org = orgList.find((o) => o.id === (node.key as number));
                if (!org) return <span>{node.title as string}</span>;
                const count = orgUserCount.get(org.id) || 0;
                return (
                  <span className="user-org-tree-node">
                    <span>{org.name}</span>
                    <Tag color={ORG_TYPE_COLORS[org.type] || 'default'} style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', marginRight: 0 }}>
                      {ORG_TYPE_LABELS[org.type] || '?'}
                    </Tag>
                    {count > 0 && <span className="user-org-tree-count">{count}人</span>}
                  </span>
                );
              }}
            />
          </Card>
        </Col>

        {/* Right: User list */}
        <Col span={18}>
          <Card
            title={
              <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span>人员管理</span>
                {selectedOrg && (
                  <Tag color={ORG_TYPE_COLORS[selectedOrg.type] || 'default'}>
                    {selectedOrg.name}
                  </Tag>
                )}
                <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
                  共 {users.length} 人
                </Typography.Text>
              </span>
            }
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
        </Col>
      </Row>

      {/* Add Modal */}
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
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }, { min: 6, message: '密码至少6位' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="real_name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select options={ROLE_OPTIONS} placeholder="请选择角色" />
          </Form.Item>
          <Form.Item name="org_id" label="所属组织">
            <TreeSelect
              allowClear
              placeholder="请选择所属组织"
              treeData={treeSelectData}
              showSearch
              treeNodeFilterProp="title"
              treeDefaultExpandAll={false}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item name="status" label="状态" initialValue={1}>
            <Select options={[{ value: 1, label: '启用' }, { value: 0, label: '停用' }]} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit Modal */}
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
            <TreeSelect
              allowClear
              placeholder="请选择所属组织"
              treeData={treeSelectData}
              showSearch
              treeNodeFilterProp="title"
              treeDefaultExpandAll={false}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select options={[{ value: 1, label: '启用' }, { value: 0, label: '停用' }]} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Reset Password Modal */}
      <Modal
        title="重置密码"
        open={resetModalOpen}
        onOk={handleReset}
        onCancel={() => { setResetModalOpen(false); setEditingUser(null); resetForm.resetFields(); }}
        okText="重置"
      >
        <p>为用户 <strong>{editingUser?.username}</strong> 设置新密码</p>
        <Form form={resetForm} layout="vertical">
          <Form.Item name="new_password" label="新密码" rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '密码至少6位' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="confirm_password" label="确认密码" dependencies={['new_password']} rules={[
            { required: true, message: '请确认新密码' },
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
