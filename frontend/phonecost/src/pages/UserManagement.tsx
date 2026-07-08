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
import { buildOrgTree, buildOrgTreeSelectData } from '../utils/orgTree';
import dayjs from 'dayjs';

const ORG_TYPE_COLORS: Record<number, string> = {
  1: COLORS.danger,
  2: COLORS.pending,
  3: COLORS.slate,
  4: COLORS.confirmed,
  5: COLORS.mauve,
  6: COLORS.sage,
};

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
  const [pageSize, setPageSize] = useState(20);
  const [addForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [resetForm] = Form.useForm();

  const fetchUsers = useCallback(async (orgId?: number) => {
    setLoading(true);
    try {
      const result = await getUsers(orgId);
      setUsers(result.content);
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
      const tree = buildOrgTree(data);
      setTreeData(tree);
      setTreeSelectData(buildOrgTreeSelectData(data));
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

  const statusOptions = [
    { value: 1, label: t('user.enabled') },
    { value: 0, label: t('user.disabled') },
  ];

  const columns = [
    { title: t('user.colUsername'), dataIndex: 'username', key: 'username', width: 120 },
    { title: t('user.colRealName'), dataIndex: 'real_name', key: 'real_name', width: 100 },
    {
      title: t('user.colRole'), dataIndex: 'role', key: 'role', width: 110,
      render: (r: number) => <Tag color={r === 1 ? COLORS.sage : r === 2 ? COLORS.slate : r === 3 ? COLORS.taupe : COLORS.mauve}>{ROLE_LABELS[r] || t('common.unknown')}</Tag>,
    },
    {
      title: t('user.colOrg'), dataIndex: 'org_id', key: 'org_id', width: 180,
      render: (orgId: number | null) => orgId ? (orgNameMap.get(orgId) || '-') : '-',
    },
    {
      title: t('user.colStatus'), dataIndex: 'status', key: 'status', width: 70, align: 'center' as const,
      render: (s: number) => s === 1 ? <Badge status="success" text={t('user.enabled')} /> : <Badge status="error" text={t('user.disabled')} />,
    },
    {
      title: t('user.colMustChangePwd'), dataIndex: 'must_change_pwd', key: 'must_change_pwd', width: 60, align: 'center' as const,
      render: (v: number) => v === 1 ? <Tag color={COLORS.pending} style={{ fontSize: 11 }}>{t('user.yes')}</Tag> : <span style={{ color: '#ddd' }}>-</span>,
    },
    {
      title: t('user.colCreatedAt'), dataIndex: 'created_at', key: 'created_at', width: 140,
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
                <span>{t('org.treeTitle')}</span>
                <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
                  ({orgList.length})
                </Typography.Text>
              </span>
            }
            size="small"
            extra={
              <Button size="small" type={selectedOrgId == null ? 'primary' : 'default'} onClick={handleShowAll}>
                {t('user.allBtn')}
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
                    {count > 0 && <span className="user-org-tree-count">{t('user.userCount', { count })}</span>}
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
                <span>{t('user.title')}</span>
                {selectedOrg && (
                  <Tag color={ORG_TYPE_COLORS[selectedOrg.type] || 'default'}>
                    {selectedOrg.name}
                  </Tag>
                )}
                <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
                  {t('user.totalUsers', { count: users.length })}
                </Typography.Text>
              </span>
            }
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
              pagination={{ pageSize, showSizeChanger: true, pageSizeOptions: ['20', '50', '100'], showTotal: (total) => `共 ${total} 条`, onChange: (_p, s) => setPageSize(s) }}
            />
          </Card>
        </Col>
      </Row>

      {/* Add Modal */}
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
          <Form.Item name="password" label={t('user.formPassword')} rules={[{ required: true, message: t('user.formPasswordRequired') }, { min: 8, message: t('user.pwdMin8') }, { pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]).{8,}$/, message: t('user.pwdComplexity') }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="real_name" label={t('user.formRealName')} rules={[{ required: true, message: t('user.formRealNameRequired') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="role" label={t('user.formRole')} rules={[{ required: true, message: t('user.formRoleRequired') }]}>
            <Select options={ROLE_OPTIONS} placeholder={t('user.formRoleRequired')} />
          </Form.Item>
          <Form.Item name="org_id" label={t('user.formOrgId')}>
            <TreeSelect
              allowClear
              placeholder={t('user.formOrgIdPlaceholder')}
              treeData={treeSelectData}
              showSearch
              treeNodeFilterProp="title"
              treeDefaultExpandAll={false}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item name="status" label={t('user.formStatus')} initialValue={1}>
            <Select options={statusOptions} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit Modal */}
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
            <TreeSelect
              allowClear
              placeholder={t('user.formOrgIdPlaceholder')}
              treeData={treeSelectData}
              showSearch
              treeNodeFilterProp="title"
              treeDefaultExpandAll={false}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item name="status" label={t('user.formStatus')} rules={[{ required: true }]}>
            <Select options={statusOptions} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Reset Password Modal */}
      <Modal
        title={t('user.resetModalTitle')}
        open={resetModalOpen}
        onOk={handleReset}
        onCancel={() => { setResetModalOpen(false); setEditingUser(null); resetForm.resetFields(); }}
        okText={t('user.resetOkBtn')}
      >
        <p>{t('user.resetDesc', { username: editingUser?.username })}</p>
        <Form form={resetForm} layout="vertical">
          <Form.Item name="new_password" label={t('user.formNewPwd')} rules={[{ required: true, message: t('user.formNewPwdRequired') }, { min: 8, message: t('user.pwdMin8') }, { pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]).{8,}$/, message: t('user.pwdComplexity') }]}>
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
