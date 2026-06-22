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
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(false);
  const [treeData, setTreeData] = useState<TreeDataNode[]>([]);
  const [selectedOrg, setSelectedOrg] = useState<Organization | null>(null);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [editForm] = Form.useForm();
  const [addForm] = Form.useForm();
  const [importFileList, setImportFileList] = useState<UploadFile[]>([]);
  const [importing, setImporting] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);

  const fetchOrgTree = async () => {
    setLoading(true);
    try {
      const data = await getOrgTree();
      setOrgList(data);
      setTreeData(buildTree(data));
    } catch {
      message.error('获取组织架构失败');
    } finally {
      setLoading(false);
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
      message.success('创建成功');
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
      message.success('更新成功');
      fetchOrgTree();
    } catch (err: any) {
      if (err?.response?.data?.message) message.error(err.response.data.message);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteOrg(id);
      message.success('删除成功');
      if (selectedOrg?.id === id) setSelectedOrg(null);
      fetchOrgTree();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '删除失败');
    }
  };

  const handleImport = async () => {
    if (importFileList.length === 0) {
      message.warning('请先选择文件');
      return;
    }
    setImporting(true);
    try {
      const file = importFileList[0].originFileObj!;
      const result = await importOrg(file);
      message.success(`导入完成：总计 ${result.total}，新建 ${result.created}，跳过 ${result.skipped}`);
      setImportFileList([]);
      fetchOrgTree();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '导入失败');
    } finally {
      setImporting(false);
    }
  };

  const handleRebuild = async () => {
    setRebuilding(true);
    try {
      await rebuildOrgPaths();
      message.success('路径重建完成');
      fetchOrgTree();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '重建失败');
    } finally {
      setRebuilding(false);
    }
  };

  const childOrgs = selectedOrg
    ? orgList.filter((o) => o.parent_id === selectedOrg.id)
    : [];

  const childColumns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '类型', dataIndex: 'type', key: 'type', render: (t: number) => ORG_TYPE_LABELS[t] || '-' },
    { title: '编码', dataIndex: 'code', key: 'code' },
    { title: '状态', dataIndex: 'is_active', key: 'is_active', render: (v: number) => v === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag> },
    {
      title: '操作', key: 'actions', width: 80,
      render: (_: any, record: Organization) => (
        <Popconfirm title="确定删除？" onConfirm={() => handleDelete(record.id)}>
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
          <Button icon={<UploadOutlined />}>导入组织架构</Button>
        </Upload>
        <Button type="primary" onClick={handleImport} loading={importing} disabled={importFileList.length === 0}>
          开始导入
        </Button>
        <Popconfirm title="确定重建所有组织路径？" onConfirm={handleRebuild}>
          <Button icon={<RetweetOutlined />} loading={rebuilding}>重建路径</Button>
        </Popconfirm>
      </Space>

      <Row gutter={16}>
        <Col span={8}>
          <Card
            title={<span><ApartmentOutlined /> 组织架构</span>}
            size="small"
            extra={
              <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setAddModalOpen(true)}>
                新增
              </Button>
            }
          >
            <Tree
              treeData={treeData}
              defaultExpandAll
              onSelect={handleSelect}
              loading={loading}
              showLine
            />
          </Card>
        </Col>
        <Col span={16}>
          {selectedOrg ? (
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Card title="组织详情" size="small">
                <Descriptions size="small" column={2}>
                  <Descriptions.Item label="ID">{selectedOrg.id}</Descriptions.Item>
                  <Descriptions.Item label="类型">{ORG_TYPE_LABELS[selectedOrg.type] || '-'}</Descriptions.Item>
                  <Descriptions.Item label="路径">{selectedOrg.path}</Descriptions.Item>
                  <Descriptions.Item label="排序">{selectedOrg.sort_order}</Descriptions.Item>
                  <Descriptions.Item label="创建时间">{dayjs(selectedOrg.created_at).format('YYYY-MM-DD HH:mm')}</Descriptions.Item>
                </Descriptions>
              </Card>
              <Card title="编辑" size="small">
                <Form form={editForm} layout="inline" onFinish={handleEdit}>
                  <Form.Item name="name" rules={[{ required: true, message: '请输入名称' }]}>
                    <Input placeholder="名称" />
                  </Form.Item>
                  <Form.Item name="code">
                    <Input placeholder="编码" />
                  </Form.Item>
                  <Form.Item name="is_active" valuePropName="checked">
                    <Switch checkedChildren="启用" unCheckedChildren="停用" />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" htmlType="submit" icon={<EditOutlined />}>保存</Button>
                  </Form.Item>
                </Form>
              </Card>
              <Card title={`下级组织 (${childOrgs.length})`} size="small">
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
                请在左侧选择一个组织节点
              </div>
            </Card>
          )}
        </Col>
      </Row>

      <Modal
        title="新增组织"
        open={addModalOpen}
        onOk={handleAdd}
        onCancel={() => { setAddModalOpen(false); addForm.resetFields(); }}
        okText="创建"
      >
        <Form form={addForm} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select options={ORG_TYPE_OPTIONS} placeholder="选择类型" />
          </Form.Item>
          <Form.Item name="code" label="编码">
            <Input placeholder="成本中心编码" />
          </Form.Item>
          <Form.Item name="parent_id" label="上级组织">
            <Select
              allowClear
              placeholder="选择上级（空为顶级）"
              options={orgList.map((o) => ({ value: o.id, label: o.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="sort_order" label="排序">
            <Input type="number" placeholder="0" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
