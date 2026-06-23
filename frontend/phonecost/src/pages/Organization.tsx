import { useState, useEffect } from 'react';
import {
  Card,
  Tree,
  Table,
  Form,
  Modal,
  Upload,
  Button,
  Input,
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
import {
  UploadOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  RetweetOutlined,
  ApartmentOutlined,
} from '@ant-design/icons';
import type { UploadFile } from 'antd';
import type { TreeDataNode } from 'antd';
import type { Organization } from '../types/organization';
import { ORG_TYPE_LABELS, ORG_TYPE_OPTIONS } from '../types/organization';
import { getOrgTree, createOrg, updateOrg, deleteOrg, importOrg, rebuildOrgPaths } from '../api/org';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';

function buildTree(list: Organization[]): TreeDataNode[] {
  const map = new Map<number, TreeDataNode>();
  const roots: TreeDataNode[] = [];
  list.forEach((org) => {
    map.set(org.id, {
      key: org.id,
      title: `${org.name} [${ORG_TYPE_LABELS[org.type] || '?'}]`,
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
  return roots;
}

export default function OrganizationPage() {
  const { t } = useTranslation();
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [treeData, setTreeData] = useState<TreeDataNode[]>([]);
  const [selectedOrg, setSelectedOrg] = useState<Organization | null>(null);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [editForm] = Form.useForm();
  const [addForm] = Form.useForm();
  const [importFileList, setImportFileList] = useState<UploadFile[]>([]);
  const [importing, setImporting] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);

  const fetchOrgTree = async () => {
    try {
      const data = await getOrgTree();
      setOrgList(data);
      setTreeData(buildTree(data));
    } catch {
      message.error(t('org.fetchFailed'));
    }
  };

  useEffect(() => { fetchOrgTree(); }, []);

  const handleSelect = (_: any, info: any) => {
    const id = info.node.key as number;
    const org = orgList.find((o) => o.id === id) || null;
    setSelectedOrg(org);
    if (org) {
      editForm.setFieldsValue({
        name: org.name,
        code: org.code,
        is_active: org.is_active === 1,
      });
    }
  };

  const handleAdd = async () => {
    try {
      const values = await addForm.validateFields();
      await createOrg({
        name: values.name,
        type: values.type,
        code: values.code,
        parent_id: values.parent_id || null,
        sort_order: values.sort_order || 0,
      });
      message.success(t('org.createSuccess'));
      setAddModalOpen(false);
      addForm.resetFields();
      fetchOrgTree();
    } catch (err: any) {
      if (err?.response?.data?.message) message.error(err.response.data.message);
    }
  };

  const handleEdit = async () => {
    if (!selectedOrg) return;
    try {
      const values = await editForm.validateFields();
      await updateOrg(selectedOrg.id, {
        name: values.name,
        code: values.code,
        is_active: values.is_active ? 1 : 0,
      });
      message.success(t('org.updateSuccess'));
      fetchOrgTree();
    } catch (err: any) {
      if (err?.response?.data?.message) message.error(err.response.data.message);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteOrg(id);
      message.success(t('org.deleteSuccess'));
      if (selectedOrg?.id === id) setSelectedOrg(null);
      fetchOrgTree();
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('org.deleteFailed'));
    }
  };

  const handleImport = async () => {
    if (importFileList.length === 0) {
      message.warning(t('org.selectFileFirst'));
      return;
    }
    setImporting(true);
    try {
      const file = importFileList[0].originFileObj!;
      const result = await importOrg(file);
      message.success(t('org.importSuccess', { total: result.total, created: result.created, skipped: result.skipped }));
      setImportFileList([]);
      fetchOrgTree();
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('org.importFailed'));
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
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('org.rebuildFailed'));
    } finally {
      setRebuilding(false);
    }
  };

  const childOrgs = selectedOrg
    ? orgList.filter((o) => o.parent_id === selectedOrg.id)
    : [];

  const childColumns = [
    { title: t('org.colName'), dataIndex: 'name', key: 'name' },
    { title: t('org.colType'), dataIndex: 'type', key: 'type', render: (v: number) => ORG_TYPE_LABELS[v] || '-' },
    { title: t('org.colCode'), dataIndex: 'code', key: 'code' },
    { title: t('org.colStatus'), dataIndex: 'is_active', key: 'is_active', render: (v: number) => v === 1 ? <Tag color="green">{t('org.statusEnabled')}</Tag> : <Tag color="red">{t('org.statusDisabled')}</Tag> },
    {
      title: t('org.colActions'), key: 'actions', width: 80,
      render: (_: any, record: Organization) => (
        <Popconfirm title={t('org.deleteConfirm')} onConfirm={() => handleDelete(record.id)}>
          <Button size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }} wrap>
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
              <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setAddModalOpen(true)}>
                {t('org.addBtn')}
              </Button>
            }
          >
            <Tree
              treeData={treeData}
              defaultExpandAll
              onSelect={handleSelect}
              showLine
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
        onCancel={() => { setAddModalOpen(false); addForm.resetFields(); }}
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
            <Input type="number" placeholder="0" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
