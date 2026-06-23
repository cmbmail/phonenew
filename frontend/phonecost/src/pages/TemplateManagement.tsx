import { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Tag,
  Modal,
  Form,
  Input,
  Select,
  message,
  Popconfirm,
  Typography,
  Descriptions,
  Alert,
  Empty,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  CheckCircleOutlined,
  CopyOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  getTemplates,
  createTemplate,
  updateTemplate,
  deleteTemplate,
  activateTemplate,
} from '../api/template';
import type { BillTemplate, SheetConfigItem } from '../types/template';
import { SHEET_TYPE_LABELS, OPERATOR_LABELS } from '../types/template';

const { Text, Paragraph } = Typography;

const DEFAULT_TEMPLATE_JSON = JSON.stringify(
  [
    {
      sheetNamePattern: '按号码费用',
      sheetType: 'CALL',
      phoneColumn: 0,
      skipRows: 1,
      isQuarterly: false,
      columns: [
        { index: 0, field: 'phoneNumber', type: 'STRING' },
        { index: 1, field: 'platformFee', type: 'DECIMAL' },
        { index: 2, field: 'monthlyRentCode', type: 'DECIMAL' },
        { index: 5, field: 'domesticFee', type: 'DECIMAL' },
        { index: 7, field: 'internationalFee', type: 'DECIMAL' },
        { index: 8, field: 'totalFee', type: 'DECIMAL' },
      ],
      computedFields: {
        monthlyRent: ['platformFee', 'monthlyRentCode'],
        callFee: ['domesticFee', 'internationalFee'],
      },
    },
    {
      sheetNamePattern: '录音',
      sheetType: 'RECORDING',
      phoneColumn: 1,
      extensionColumn: 0,
      skipRows: 1,
      isQuarterly: false,
      columns: [
        { index: 0, field: 'extension', type: 'STRING' },
        { index: 1, field: 'phoneNumber', type: 'STRING' },
        { index: 3, field: 'recordingFee', type: 'DECIMAL' },
      ],
    },
    {
      sheetNamePattern: '彩铃',
      sheetType: 'CRBT',
      phoneColumn: 1,
      extensionColumn: 0,
      skipRows: 1,
      isQuarterly: false,
      columns: [
        { index: 0, field: 'extension', type: 'STRING' },
        { index: 1, field: 'phoneNumber', type: 'STRING' },
        { index: 2, field: 'crbtFee', type: 'DECIMAL' },
      ],
    },
    {
      sheetNamePattern: '闪信',
      sheetType: 'FLASH_MSG',
      phoneColumn: 0,
      skipRows: 1,
      isQuarterly: true,
      columns: [
        { index: 0, field: 'phoneNumber', type: 'STRING' },
        { index: 1, field: 'flashMonth', type: 'STRING' },
        { index: 3, field: 'flashMsgFee', type: 'DECIMAL' },
      ],
    },
  ],
  null,
  2
);

export default function TemplateManagement() {
  const [templates, setTemplates] = useState<BillTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailTemplate, setDetailTemplate] = useState<BillTemplate | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  const [jsonText, setJsonText] = useState(DEFAULT_TEMPLATE_JSON);

  const fetchTemplates = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getTemplates();
      setTemplates(data);
    } catch {
      message.error('获取模板列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchTemplates();
  }, [fetchTemplates]);

  // ==================== Create / Edit ====================

  const handleCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({ operator: 'CHINA_TELECOM' });
    setJsonText(DEFAULT_TEMPLATE_JSON);
    setModalOpen(true);
  };

  const handleEdit = (record: BillTemplate) => {
    setEditingId(record.id);
    form.setFieldsValue({
      name: record.name,
      operator: record.operator || 'CHINA_TELECOM',
      month_pattern: record.month_pattern || '',
      description: record.description || '',
    });
    try {
      const parsed = JSON.parse(record.sheet_configs);
      setJsonText(JSON.stringify(parsed, null, 2));
    } catch {
      setJsonText(record.sheet_configs);
    }
    setModalOpen(true);
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();

      // Validate JSON
      let parsedJson;
      try {
        parsedJson = JSON.parse(jsonText);
      } catch {
        message.error('Sheet配置JSON格式错误，请检查');
        return;
      }

      if (!Array.isArray(parsedJson) || parsedJson.length === 0) {
        message.error('Sheet配置必须是非空数组');
        return;
      }

      setSaving(true);
      const body = {
        ...values,
        sheet_configs: JSON.stringify(parsedJson),
      };

      if (editingId != null) {
        await updateTemplate(editingId, body);
        message.success('模板更新成功');
      } else {
        await createTemplate(body as Parameters<typeof createTemplate>[0]);
        message.success('模板创建成功');
      }

      setModalOpen(false);
      fetchTemplates();
    } catch (err: any) {
      if (err?.errorFields) return; // form validation error
      message.error(err?.response?.data?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  // ==================== Activate / Delete ====================

  const handleActivate = async (id: number) => {
    try {
      await activateTemplate(id);
      message.success('模板已切换为活跃状态');
      fetchTemplates();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '切换失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteTemplate(id);
      message.success('模板已删除');
      fetchTemplates();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '删除失败');
    }
  };

  // ==================== Detail View ====================

  const showDetail = (record: BillTemplate) => {
    setDetailTemplate(record);
    setDetailOpen(true);
  };

  const parseSheetConfigs = (jsonStr: string): SheetConfigItem[] => {
    try {
      return JSON.parse(jsonStr);
    } catch {
      return [];
    }
  };

  // ==================== Duplicate ====================

  const handleDuplicate = (record: BillTemplate) => {
    setEditingId(null);
    form.setFieldsValue({
      name: `${record.name} (副本)`,
      operator: record.operator || 'CHINA_TELECOM',
      month_pattern: record.month_pattern || '',
      description: record.description || '',
    });
    setJsonText(record.sheet_configs);
    setModalOpen(true);
  };

  // ==================== Table Config ====================

  const columns: ColumnsType<BillTemplate> = [
    {
      title: '状态',
      key: 'active',
      width: 70,
      render: (_, r) =>
        r.is_active === 1 ? (
          <Tag color="green" icon={<CheckCircleOutlined />}>
            活跃
          </Tag>
        ) : (
          <Text type="secondary">-</Text>
        ),
    },
    { title: '名称', dataIndex: 'name', key: 'name' },
    {
      title: '运营商',
      dataIndex: 'operator',
      key: 'operator',
      width: 110,
      render: (v: string) => OPERATOR_LABELS[v] || v,
    },
    {
      title: 'Sheet数',
      key: 'sheetCount',
      width: 80,
      align: 'center',
      render: (_, r) => parseSheetConfigs(r.sheet_configs).length,
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: '操作',
      key: 'actions',
      width: 260,
      fixed: 'right' as const,
      render: (_, record) => (
        <Space size="small">
          <Button size="small" icon={<EyeOutlined />} onClick={() => showDetail(record)}>
            查看
          </Button>
          <Button size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            编辑
          </Button>
          <Button size="small" icon={<CopyOutlined />} onClick={() => handleDuplicate(record)}>
            复制
          </Button>
          {record.is_active !== 1 && (
            <Button size="small" type="primary" ghost onClick={() => handleActivate(record.id)}>
              激活
            </Button>
          )}
          {record.is_active !== 1 && (
            <Popconfirm
              title="确定删除此模板？"
              onConfirm={() => handleDelete(record.id)}
            >
              <Button size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card
        title="账单模板管理"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            新建模板
          </Button>
        }
      >
        <Alert
          message="模板用于驱动账单Excel的解析逻辑。修改模板后，下次导入账单时将使用新配置。"
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />

        <Table
          columns={columns}
          dataSource={templates}
          rowKey="id"
          loading={loading}
          pagination={{ pageSize: 10 }}
          size="small"
        />
      </Card>

      {/* Create/Edit Modal */}
      <Modal
        title={editingId != null ? '编辑模板' : '新建模板'}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        width={720}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="name" label="模板名称" rules={[{ required: true, message: '请输入模板名称' }]} style={{ flex: 1 }}>
              <Input placeholder="如：中国电信标准模板" />
            </Form.Item>
            <Form.Item name="operator" label="运营商" style={{ width: 180 }}>
              <Select options={[
                { value: 'CHINA_TELECOM', label: '中国电信' },
                { value: 'CHINA_MOBILE', label: '中国移动' },
                { value: 'CHINA_UNICOM', label: '中国联通' },
              ]} />
            </Form.Item>
          </Space>

          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="month_pattern" label="账期正则" style={{ flex: 1 }}>
              <Input placeholder="(\\d{4})年(\\d{1,2})月" />
            </Form.Item>
            <Form.Item name="description" label="描述" style={{ flex: 1 }}>
              <Input placeholder="可选，模板用途说明" />
            </Form.Item>
          </Space>

          <Form.Item label="Sheet 配置 (JSON)">
            <Input.TextArea
              value={jsonText}
              onChange={(e) => setJsonText(e.target.value)}
              rows={16}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
              placeholder="输入 Sheet 解析配置 JSON 数组"
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* Detail Modal */}
      <Modal
        title={`模板详情 - ${detailTemplate?.name || ''}`}
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        footer={null}
        width={800}
      >
        {detailTemplate && (
          <div>
            <Descriptions bordered size="small" column={2} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="ID">{detailTemplate.id}</Descriptions.Item>
              <Descriptions.Item label="运营商">{OPERATOR_LABELS[detailTemplate.operator] || detailTemplate.operator}</Descriptions.Item>
              <Descriptions.Item label="账期正则">{detailTemplate.month_pattern || '-'}</Descriptions.Item>
              <Descriptions.Item label="状态">
                {detailTemplate.is_active === 1 ? <Tag color="green">活跃</Tag> : <Tag>未激活</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="描述" span={2}>{detailTemplate.description || '-'}</Descriptions.Item>
              <Descriptions.Item label="创建时间" span={2}>{new Date(detailTemplate.created_at).toLocaleString()}</Descriptions.Item>
            </Descriptions>

            <Typography.Title level={5}>Sheet 配置</Typography.Title>
            {parseSheetConfigs(detailTemplate.sheet_configs).map((sheet, idx) => (
              <Card
                key={idx}
                size="small"
                title={
                  <Space>
                    <Tag color="blue">{SHEET_TYPE_LABELS[sheet.sheetType] || sheet.sheetType}</Tag>
                    <Text type="secondary">匹配: {sheet.sheetNamePattern}</Text>
                    {sheet.isQuarterly && <Tag color="orange">季度结算</Tag>}
                  </Space>
                }
                style={{ marginBottom: 8 }}
              >
                <Descriptions size="small" column={2}>
                  <Descriptions.Item label="号码列">{sheet.phoneColumn}</Descriptions.Item>
                  <Descriptions.Item label="分机列">{sheet.extensionColumn ?? '-'}</Descriptions.Item>
                  <Descriptions.Item label="跳过行数">{sheet.skipRows}</Descriptions.Item>
                  <Descriptions.Item label="列映射数">{sheet.columns?.length || 0}</Descriptions.Item>
                </Descriptions>
                {sheet.columns && sheet.columns.length > 0 && (
                  <div style={{ marginTop: 8 }}>
                    <Text strong>列定义：</Text>
                    <pre style={{
                      background: '#f5f5f5', padding: 8, borderRadius: 4,
                      fontSize: 11, marginTop: 4, overflow: 'auto', maxHeight: 150,
                    }}>
                      {JSON.stringify(sheet.columns, null, 2)}
                    </pre>
                  </div>
                )}
                {sheet.computedFields && Object.keys(sheet.computedFields).length > 0 && (
                  <div style={{ marginTop: 8 }}>
                    <Text strong>计算字段：</Text>
                    <pre style={{
                      background: '#f5f5f5', padding: 8, borderRadius: 4,
                      fontSize: 11, marginTop: 4, overflow: 'auto', maxHeight: 100,
                    }}>
                      {JSON.stringify(sheet.computedFields, null, 2)}
                    </pre>
                  </div>
                )}
              </Card>
            ))}

            {parseSheetConfigs(detailTemplate.sheet_configs).length === 0 && (
              <Empty description="无有效 Sheet 配置" />
            )}

            <Typography.Title level={5} style={{ marginTop: 16 }}>原始 JSON</Typography.Title>
            <Paragraph>
              <pre style={{
                background: '#f5f5f5', padding: 12, borderRadius: 6,
                fontSize: 11, overflow: 'auto', maxHeight: 400,
              }}>
                {(() => {
                  try { return JSON.stringify(JSON.parse(detailTemplate.sheet_configs), null, 2); }
                  catch { return detailTemplate.sheet_configs; }
                })()}
              </pre>
            </Paragraph>
          </div>
        )}
      </Modal>
    </div>
  );
}
