import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card,
  Tree,
  Table,
  Form,
  Modal,
  Upload,
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
  Descriptions,
} from 'antd';
import { getErrorMessage } from '../types/api';
import {
  UploadOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  RetweetOutlined,
  ApartmentOutlined,
  DownloadOutlined,
  MinusCircleOutlined,
} from '@ant-design/icons';
import type { UploadFile } from 'antd';
import type { TreeDataNode, DataNode } from 'antd';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS, ORG_TYPE_OPTIONS } from '../types/organization';
import { getOrgTree, createOrg, updateOrg, deleteOrg, importOrg, rebuildOrgPaths, downloadOrgTemplate } from '../api/org';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';

function buildTree(list: Organization[]): DataNode[] {
  const map = new Map<number, DataNode>();
  const roots: DataNode[] = [];
  list.forEach((org) => {
    map.set(org.id, {
      key: org.id,
      title: `${org.name} [${ORG_TYPE_LABELS[org.type] || '?'}]`,
      children: [],
      isLeaf: false,
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
  return roots;
}

export default function OrganizationPage() {
  const { t } = useTranslation();
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [treeData, setTreeData] = useState<TreeDataNode[]>([]);
  const [selectedOrg, setSelectedOrg] = useState<Organization | null>(null);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [addParentId, setAddParentId] = useState<number | null>(null);
  const [editForm] = Form.useForm();
  const [addForm] = Form.useForm();
  const [importFileList, setImportFileList] = useState<UploadFile[]>([]);
  const [importing, setImporting] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);

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

  const handleImport = async () => {
    if (importFileList.length === 0) {
      message.warning(t('org.selectFileFirst'));
      return;
    }
    setImporting(true);
    try {
      const file = importFileList[0].originFileObj;
      if (!file) { message.warning(t('org.selectFileFirst')); return; }
      const result = await importOrg(file);
      message.success(t('org.importSuccess', { total: result.total, created: result.created, skipped: result.skipped }));
      setImportFileList([]);
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
    { title: t('org.colName'), dataIndex: 'name', key: 'name' },
    { title: t('org.colType'), dataIndex: 'type', key: 'type', render: (v: number) => ORG_TYPE_LABELS[v] || '-' },
    { title: t('org.colCode'), dataIndex: 'code', key: 'code', align: 'center' as const, render: (v: string | null) => v || '-' },
    { title: t('org.colCostCenter'), dataIndex: 'cost_center', key: 'cost_center', align: 'center' as const, render: (v: string | null) => v || '-' },
    { title: t('org.colStatus'), dataIndex: 'is_active', key: 'is_active', render: (v: number) => v === 1 ? <Tag color="green">{t('org.statusEnabled')}</Tag> : <Tag color="red">{t('org.statusDisabled')}</Tag> },
    {
      title: t('org.colActions'), key: 'actions', width: 80,
      render: (_unused: unknown, record: Organization) => (
        <Popconfirm title={t('org.deleteConfirm')} onConfirm={() => handleDelete(record.id)}>
          <Button size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <style>{`
        .org-tree-node:hover .org-tree-actions { opacity: 1 !important; }
        .org-tree-actions .ant-btn-link { padding: 0 2px; }
      `}</style>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button icon={<DownloadOutlined />} onClick={handleDownloadTemplate}>
          {t('org.downloadTemplate')}
        </Button>
        <Upload
          accept=".xlsx,.xls"
          maxCount={1}
          fileList={importFileList}
          beforeUpload={() => false}
          onChange={({ fileList: fl }) => setImportFileList(fl)}
        >
          <Button icon={<UploadOutlined />}>{t('org.importOrg')}</Button>
        </Upload>
        <Button type="primary" onClick={handleImport} loading={importing} disabled={importFileList.length === 0}>
          {t('org.startImport')}
        </Button>
        <Popconfirm title={t('org.rebuildConfirm')} onConfirm={handleRebuild}>
          <Button icon={<RetweetOutlined />} loading={rebuilding}>{t('org.rebuildPath')}</Button>
        </Popconfirm>
      </Space>

      <Row gutter={16}>
        <Col span={8}>
          <Card
            title={<span><ApartmentOutlined /> {t('org.treeTitle')}</span>}
            size="small"
            extra={
              <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => { setAddParentId(null); setAddModalOpen(true); }}>
                {t('org.addBtn')}
              </Button>
            }
          >
            <Tree
              treeData={treeData}
              defaultExpandAll
              onSelect={handleSelect}
              showLine
              titleRender={(node: DataNode) => {
                const org = orgList.find((o) => o.id === (node.key as number));
                return (
                  <span
                    className="org-tree-node"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 4, width: '100%' }}
                  >
                    <span>{node.title as string}</span>
                    <span className="org-tree-actions" style={{ opacity: 0, transition: 'opacity 0.15s' }}>
                      {org && (
                        <>
                          <Button
                            type="link"
                            size="small"
                            icon={<PlusOutlined />}
                            onClick={(e) => { e.stopPropagation(); handleAddChild(org.id); }}
                            title={t('org.addChild')}
                          />
                          <Popconfirm
                            title={t('org.deleteConfirm')}
                            onConfirm={(e) => {
                              e?.stopPropagation();
                              handleDelete(org.id);
                            }}
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
                        </>
                      )}
                    </span>
                  </span>
                );
              }}
            />
          </Card>
        </Col>
        <Col span={16}>
          {selectedOrg ? (
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Card title={t('org.detailTitle')} size="small">
                <Descriptions size="small" column={2}>
                  <Descriptions.Item label={t('org.detailId')}>{selectedOrg.id}</Descriptions.Item>
                  <Descriptions.Item label={t('org.detailType')}>{ORG_TYPE_LABELS[selectedOrg.type] || '-'}</Descriptions.Item>
                  <Descriptions.Item label={t('org.detailPath')}>{selectedOrg.path}</Descriptions.Item>
                  <Descriptions.Item label={t('org.detailSortOrder')}>{selectedOrg.sort_order}</Descriptions.Item>
                  <Descriptions.Item label={t('org.detailCreatedAt')}>{dayjs(selectedOrg.created_at).format('YYYY-MM-DD HH:mm')}</Descriptions.Item>
                </Descriptions>
              </Card>
              <Card title={t('org.editTitle')} size="small">
                <Form form={editForm} layout="inline" onFinish={handleEdit}>
                  <Form.Item name="name" rules={[{ required: true, message: t('org.nameRequired') }]}>
                    <Input placeholder={t('org.name')} />
                  </Form.Item>
                  <Form.Item name="code">
                    <Input placeholder={t('org.code')} />
                  </Form.Item>
                  <Form.Item name="cost_center">
                    <Input placeholder={t('org.costCenter')} />
                  </Form.Item>
                  <Form.Item name="is_active" valuePropName="checked">
                    <Switch checkedChildren={t('org.statusEnabled')} unCheckedChildren={t('org.statusDisabled')} />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" htmlType="submit" icon={<EditOutlined />}>{t('org.saveBtn')}</Button>
                  </Form.Item>
                </Form>
              </Card>
              <Card title={t('org.childOrgsTitle', { count: childOrgs.length })} size="small">
                <Table
                  columns={childColumns}
                  dataSource={childOrgs}
                  rowKey="id"
                  size="small"
                  pagination={false}
                />
              </Card>
            </Space>
          ) : (
            <Card>
              <div style={{ textAlign: 'center', color: '#999', padding: 40 }}>
                {t('org.selectNodeHint')}
              </div>
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
