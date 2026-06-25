import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card,
  Tree,
  Table,
  Form,
  Modal,
  Button,
  Input,
  InputNumber,
  Select,
  Space,
  Row,
  Col,
  message,
  Popconfirm,
  Switch,
  Tag,
  Dropdown,
  Typography,
  Divider,
} from 'antd';
import { getErrorMessage } from '../types/api';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  RetweetOutlined,
  ApartmentOutlined,
  DownloadOutlined,
  MinusCircleOutlined,
  UploadOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS, ORG_TYPE_OPTIONS } from '../types/organization';
import { getOrgTree, createOrg, updateOrg, deleteOrg, importOrg, rebuildOrgPaths, downloadOrgTemplate } from '../api/org';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';

const ORG_TYPE_COLORS: Record<number, string> = {
  1: 'red',
  2: 'orange',
  3: 'blue',
  4: 'green',
  5: 'purple',
  6: 'cyan',
};

function buildTree(list: Organization[]): DataNode[] {
  const map = new Map<number, DataNode>();
  const roots: DataNode[] = [];
  list.forEach((org) => {
    map.set(org.id, {
      key: org.id,
      title: `${org.name}`,
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
  // Mark leaf nodes
  const markLeaf = (nodes: DataNode[]) => {
    nodes.forEach((n) => {
      if (!n.children || n.children.length === 0) {
        n.isLeaf = true;
      } else {
        markLeaf(n.children);
      }
    });
  };
  markLeaf(roots);
  return roots;
}

export default function OrganizationPage() {
  const { t } = useTranslation();
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [treeData, setTreeData] = useState<DataNode[]>([]);
  const [selectedOrg, setSelectedOrg] = useState<Organization | null>(null);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [addParentId, setAddParentId] = useState<number | null>(null);
  const [editForm] = Form.useForm();
  const [addForm] = Form.useForm();
  const [importing, setImporting] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);
  const importRef = useRef<HTMLInputElement>(null);

  const fetchOrgTree = useCallback(async () => {
    try {
      const data = await getOrgTree();
      setOrgList(data);
      setTreeData(buildTree(data));
    } catch {
      message.error(t('org.fetchFailed'));
    }
  }, [t]);

  useEffect(() => { fetchOrgTree(); }, [fetchOrgTree]);

  const handleSelect = (_unused: unknown, info: { node: { key: unknown } }) => {
    const id = info.node.key as number;
    const org = orgList.find((o) => o.id === id) || null;
    setSelectedOrg(org);
    if (org) {
      editForm.setFieldsValue({
        name: org.name,
        type: org.type,
        code: org.code || '',
        cost_center: org.cost_center || '',
        is_active: org.is_active === 1,
      });
    }
  };

  const handleAddChild = (parentId: number) => {
    setAddParentId(parentId);
    addForm.resetFields();
    addForm.setFieldsValue({ parent_id: parentId });
    setAddModalOpen(true);
  };

  const handleAdd = async () => {
    try {
      const values = await addForm.validateFields();
      await createOrg({
        name: values.name,
        type: values.type,
        code: values.code,
        cost_center: values.cost_center,
        parent_id: addParentId || values.parent_id || null,
        sort_order: values.sort_order || 0,
      });
      message.success(t('org.createSuccess'));
      setAddModalOpen(false);
      setAddParentId(null);
      addForm.resetFields();
      fetchOrgTree();
    } catch (err) {
      const msg = getErrorMessage(err, t('common.failed'));
      message.error(msg);
    }
  };

  const handleEdit = async () => {
    if (!selectedOrg) return;
    try {
      const values = await editForm.validateFields();
      await updateOrg(selectedOrg.id, {
        name: values.name,
        type: values.type,
        code: values.code,
        cost_center: values.cost_center,
        is_active: values.is_active ? 1 : 0,
      });
      message.success(t('org.updateSuccess'));
      fetchOrgTree();
    } catch (err) {
      const msg = getErrorMessage(err, t('common.failed'));
      message.error(msg);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteOrg(id);
      message.success(t('org.deleteSuccess'));
      if (selectedOrg?.id === id) setSelectedOrg(null);
      fetchOrgTree();
    } catch (err) {
      message.error(getErrorMessage(err, t('org.deleteFailed')));
    }
  };

  const handleImportFile = async (file: File) => {
    setImporting(true);
    try {
      const result = await importOrg(file);
      message.success(t('org.importSuccess', { total: result.total, created: result.created, skipped: result.skipped }));
      fetchOrgTree();
    } catch (err) {
      message.error(getErrorMessage(err, t('org.importFailed')));
    } finally {
      setImporting(false);
    }
  };

  const handleRebuild = async () => {
    setRebuilding(true);
    try {
      await rebuildOrgPaths();
      message.success(t('org.rebuildSuccess'));
      fetchOrgTree();
    } catch (err) {
      message.error(getErrorMessage(err, t('org.rebuildFailed')));
    } finally {
      setRebuilding(false);
    }
  };

  const handleDownloadTemplate = async () => {
    try {
      await downloadOrgTemplate();
      message.success(t('org.templateDownloaded'));
    } catch (err) {
      message.error(getErrorMessage(err, t('org.templateDownloadFailed')));
    }
  };

  const childOrgs = selectedOrg
    ? orgList.filter((o) => o.parent_id === selectedOrg.id)
    : [];

  const childColumns = [
    {
      title: t('org.colName'), dataIndex: 'name', key: 'name',
      render: (v: string, record: Organization) => (
        <span>{v} <Tag color={ORG_TYPE_COLORS[record.type] || 'default'} style={{ marginLeft: 4, fontSize: 11 }}>{ORG_TYPE_LABELS[record.type] || '-'}</Tag></span>
      ),
    },
    { title: t('org.colCode'), dataIndex: 'code', key: 'code', align: 'center' as const, render: (v: string | null) => v || '-' },
    { title: t('org.colCostCenter'), dataIndex: 'cost_center', key: 'cost_center', align: 'center' as const, render: (v: string | null) => v || '-' },
    {
      title: t('org.colStatus'), dataIndex: 'is_active', key: 'is_active', align: 'center' as const, width: 80,
      render: (v: number) => v === 1 ? <Tag color="green">{t('org.statusEnabled')}</Tag> : <Tag color="red">{t('org.statusDisabled')}</Tag>,
    },
  ];

  return (
    <div>
      <style>{`
        .org-tree-node { display: inline-flex; align-items: center; gap: 6px; width: 100%; padding: 2px 0; }
        .org-tree-node:hover .org-tree-actions { opacity: 1 !important; }
        .org-tree-actions { opacity: 0; transition: opacity 0.2s; display: inline-flex; align-items: center; gap: 2px; margin-left: auto; }
        .org-tree-actions .ant-btn-link { padding: 0 2px; height: 20px; font-size: 13px; }
        .org-tree-title { display: flex; align-items: center; gap: 6px; }
        .org-detail-row { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
        .org-detail-item { display: flex; align-items: center; gap: 4px; }
        .org-detail-label { color: #999; font-size: 12px; white-space: nowrap; }
        .org-detail-value { font-size: 13px; font-weight: 500; }
      `}</style>
      <input
        ref={importRef}
        type="file"
        accept=".xlsx,.xls"
        style={{ display: 'none' }}
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) handleImportFile(file);
          e.target.value = '';
        }}
      />

      <Row gutter={16}>
        <Col span={10}>
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
              <Space size={4}>
                <Dropdown
                  menu={{
                    items: [
                      { key: 'download', label: t('org.downloadTemplate'), icon: <DownloadOutlined />, onClick: handleDownloadTemplate },
                      { key: 'import', label: t('org.importOrg'), icon: <UploadOutlined />, onClick: () => importRef.current?.click() },
                    ],
                  }}
                >
                  <Button size="small" type="primary" icon={<UploadOutlined />}>
                    {t('org.importMenu')}
                  </Button>
                </Dropdown>
                <Popconfirm title={t('org.rebuildConfirm')} onConfirm={handleRebuild}>
                  <Button size="small" icon={<RetweetOutlined />} loading={rebuilding} title={t('org.rebuildPath')} />
                </Popconfirm>
              </Space>
            }
            styles={{ body: { padding: '8px 12px', maxHeight: 'calc(100vh - 180px)', overflowY: 'auto' } }}
          >
            <Tree
              treeData={treeData}
              defaultExpandAll
              onSelect={handleSelect}
              showLine
              selectedKeys={selectedOrg ? [selectedOrg.id] : []}
              titleRender={(node: DataNode) => {
                const org = orgList.find((o) => o.id === (node.key as number));
                if (!org) return <span>{node.title as string}</span>;
                return (
                  <span className="org-tree-node">
                    <span className="org-tree-title">
                      <span>{org.name}</span>
                      <Tag color={ORG_TYPE_COLORS[org.type] || 'default'} style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px', marginRight: 0 }}>
                        {ORG_TYPE_LABELS[org.type] || '?'}
                      </Tag>
                    </span>
                    <span className="org-tree-actions">
                      {!node.isLeaf && (
                        <Button
                          type="link"
                          size="small"
                          icon={<PlusOutlined />}
                          onClick={(e) => { e.stopPropagation(); handleAddChild(org.id); }}
                          title={t('org.addChild')}
                        />
                      )}
                      <Popconfirm
                        title={t('org.deleteConfirm')}
                        onConfirm={(e) => { e?.stopPropagation(); handleDelete(org.id); }}
                      >
                        <Button
                          type="link"
                          size="small"
                          danger
                          icon={<MinusCircleOutlined />}
                          onClick={(e) => e.stopPropagation()}
                          title={t('org.deleteOrg')}
                        />
                      </Popconfirm>
                    </span>
                  </span>
                );
              }}
            />
          </Card>
        </Col>
        <Col span={14}>
          {selectedOrg ? (
            <Card
              size="small"
              title={
                <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Tag color={ORG_TYPE_COLORS[selectedOrg.type] || 'default'}>{ORG_TYPE_LABELS[selectedOrg.type] || '-'}</Tag>
                  <span style={{ fontSize: 15 }}>{selectedOrg.name}</span>
                </span>
              }
              extra={
                selectedOrg.is_active === 1
                  ? <Tag color="green">{t('org.statusEnabled')}</Tag>
                  : <Tag color="red">{t('org.statusDisabled')}</Tag>
              }
            >
              {/* Info row */}
              <div className="org-detail-row">
                <span className="org-detail-item">
                  <span className="org-detail-label">ID:</span>
                  <span className="org-detail-value">{selectedOrg.id}</span>
                </span>
                <span className="org-detail-item">
                  <span className="org-detail-label">{t('org.code')}:</span>
                  <span className="org-detail-value">{selectedOrg.code || '-'}</span>
                </span>
                <span className="org-detail-item">
                  <span className="org-detail-label">{t('org.costCenter')}:</span>
                  <span className="org-detail-value">{selectedOrg.cost_center || '-'}</span>
                </span>
                <span className="org-detail-item">
                  <span className="org-detail-label">{t('org.detailCreatedAt')}:</span>
                  <span className="org-detail-value">{dayjs(selectedOrg.created_at).format('YYYY-MM-DD HH:mm')}</span>
                </span>
              </div>

              <Divider style={{ margin: '8px 0' }} />

              {/* Edit form */}
              <Form form={editForm} layout="inline" onFinish={handleEdit} style={{ flexWrap: 'wrap', gap: '8px' }}>
                <Form.Item name="name" rules={[{ required: true, message: t('org.nameRequired') }]}>
                  <Input placeholder={t('org.name')} style={{ width: 160 }} />
                </Form.Item>
                <Form.Item name="type" rules={[{ required: true, message: t('org.typeRequired') }]}>
                  <Select options={ORG_TYPE_OPTIONS} style={{ width: 120 }} />
                </Form.Item>
                <Form.Item name="code">
                  <Input placeholder={t('org.code')} style={{ width: 120 }} />
                </Form.Item>
                <Form.Item name="cost_center">
                  <Input placeholder={t('org.costCenter')} style={{ width: 120 }} />
                </Form.Item>
                <Form.Item name="is_active" valuePropName="checked">
                  <Switch checkedChildren={t('org.statusEnabled')} unCheckedChildren={t('org.statusDisabled')} />
                </Form.Item>
                <Form.Item>
                  <Button type="primary" htmlType="submit" icon={<EditOutlined />}>{t('org.saveBtn')}</Button>
                </Form.Item>
              </Form>

              {/* Child orgs table */}
              {childOrgs.length > 0 && (
                <>
                  <Divider orientation="left" style={{ margin: '16px 0 8px', fontSize: 13 }}>
                    {t('org.childOrgsTitle', { count: childOrgs.length })}
                  </Divider>
                  <Table
                    columns={childColumns}
                    dataSource={childOrgs}
                    rowKey="id"
                    size="small"
                    pagination={false}
                  />
                </>
              )}
            </Card>
          ) : (
            <Card styles={{ body: { display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: 400 } }}>
              <ApartmentOutlined style={{ fontSize: 48, color: '#d9d9d9', marginBottom: 16 }} />
              <Typography.Text type="secondary" style={{ fontSize: 14 }}>{t('org.selectNodeHint')}</Typography.Text>
            </Card>
          )}
        </Col>
      </Row>

      <Modal
        title={t('org.addModalTitle')}
        open={addModalOpen}
        onOk={handleAdd}
        onCancel={() => { setAddModalOpen(false); setAddParentId(null); addForm.resetFields(); }}
        okText={t('org.createBtn')}
      >
        <Form form={addForm} layout="vertical">
          <Form.Item name="name" label={t('org.name')} rules={[{ required: true, message: t('org.nameRequired') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="type" label={t('org.type')} rules={[{ required: true, message: t('org.typeRequired') }]}>
            <Select options={ORG_TYPE_OPTIONS} placeholder={t('org.type')} />
          </Form.Item>
          <Form.Item name="code" label={t('org.code')}>
            <Input placeholder={t('org.codePlaceholder')} />
          </Form.Item>
          <Form.Item name="cost_center" label={t('org.costCenter')}>
            <Input placeholder={t('org.costCenterPlaceholder')} />
          </Form.Item>
          <Form.Item name="parent_id" label={t('org.parentOrg')}>
            <Select
              allowClear
              placeholder={t('org.parentOrgPlaceholder')}
              options={orgList.map((o) => ({ value: o.id, label: o.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="sort_order" label={t('org.sortOrder')}>
            <InputNumber placeholder={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
