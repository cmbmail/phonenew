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
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();

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
      // Use a generic error since template-specific fetch error isn't defined
      message.error(t('template.activateFailed'));
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
        message.error(t('template.jsonFormatError'));
        return;
      }

      if (!Array.isArray(parsedJson) || parsedJson.length === 0) {
        message.error(t('template.jsonArrayError'));
        return;
      }

      setSaving(true);
      const body = {
        ...values,
        sheet_configs: JSON.stringify(parsedJson),
      };

      if (editingId != null) {
        await updateTemplate(editingId, body);
        message.success(t('template.saveSuccess'));
      } else {
        await createTemplate(body as Parameters<typeof createTemplate>[0]);
        message.success(t('template.createSuccess'));
      }

      setModalOpen(false);
      fetchTemplates();
    } catch (err: any) {
      if (err?.errorFields) return; // form validation error
      message.error(err?.response?.data?.message || t('common.failed'));
    } finally {
      setSaving(false);
    }
  };

  // ==================== Activate / Delete ====================

  const handleActivate = async (id: number) => {
    try {
      await activateTemplate(id);
      message.success(t('template.activateSuccess'));
      fetchTemplates();
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('template.activateFailed'));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteTemplate(id);
      message.success(t('template.deleteSuccess'));
      fetchTemplates();
    } catch (err: any) {
      message.error(err?.response?.data?.message || t('template.deleteFailed'));
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
      title: t('common.status'),
      key: 'active',
      width: 70,
      render: (_, r) =>
        r.is_active === 1 ? (
          <Tag color="green" icon={<CheckCircleOutlined />}>
            {t('template.activeTag')}
          </Tag>
        ) : (
          <Text type="secondary">-</Text>
        ),
    },
    { title: t('template.templateName'), dataIndex: 'name', key: 'name' },
    {
      title: t('template.operator'),
      dataIndex: 'operator',
      key: 'operator',
      width: 110,
      render: (v: string) => OPERATOR_LABELS[v] || v,
    },
    {
      title: t('template.sheetCount'),
      key: 'sheetCount',
      width: 80,
      align: 'center',
      render: (_, r) => parseSheetConfigs(r.sheet_configs).length,
    },
    {
      title: t('template.description'),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 260,
      fixed: 'right' as const,
      render: (_, record) => (
        <Space size="small">
          <Button size="small" icon={<EyeOutlined />} onClick={() => showDetail(record)}>
            {t('template.viewBtn')}
          </Button>
          <Button size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('template.editBtn')}
          </Button>
          <Button size="small" icon={<CopyOutlined />} onClick={() => handleDuplicate(record)}>
            {t('template.copyBtn')}
          </Button>
          {record.is_active !== 1 && (
            <Button size="small" type="primary" ghost onClick={() => handleActivate(record.id)}>
              {t('template.activateBtn')}
            </Button>
          )}
          {record.is_active !== 1 && (
            <Popconfirm
              title={t('template.deleteConfirm')}
              onConfirm={() => handleDelete(record.id)}
            >
              <Button size="small" danger icon={<DeleteOutlined />}>
                {t('template.deleteBtn')}
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
        title={t('template.title')}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            {t('template.addTemplate')}
          </Button>
        }
      >
        <Alert
          message={t('template.alertMessage')}
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
        title={editingId != null ? t('template.editTitle') : t('template.createTitle')}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        width={720}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="name" label={t('template.templateName')} rules={[{ required: true, message: t('template.templateNameRequired') }]} style={{ flex: 1 }}>
              <Input placeholder={t('template.templateNamePlaceholder')} />
            </Form.Item>
            <Form.Item name="operator" label={t('template.operator')} style={{ width: 180 }}>
              <Select options={[
                { value: 'CHINA_TELECOM', label: t('template.chinaTelecom') },
                { value: 'CHINA_MOBILE', label: t('template.chinaMobile') },
                { value: 'CHINA_UNICOM', label: t('template.chinaUnicom') },
              ]} />
            </Form.Item>
          </Space>

          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="month_pattern" label={t('template.monthPattern')} style={{ flex: 1 }}>
              <Input placeholder={t('template.monthPatternPlaceholder')} />
            </Form.Item>
            <Form.Item name="description" label={t('template.desc')} style={{ flex: 1 }}>
              <Input placeholder={t('template.descPlaceholder')} />
            </Form.Item>
          </Space>

          <Form.Item label={t('template.sheetConfigLabel')}>
            <Input.TextArea
              value={jsonText}
              onChange={(e) => setJsonText(e.target.value)}
              rows={16}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
              placeholder={t('template.sheetConfigPlaceholder')}
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* Detail Modal */}
      <Modal
        title={t('template.detailTitle', { name: detailTemplate?.name || '' })}
        open={detailOpen}
        onCancel={() => setDetailOpen(false)}
        footer={null}
        width={800}
      >
        {detailTemplate && (
          <div>
            <Descriptions bordered size="small" column={2} style={{ marginBottom: 16 }}>
              <Descriptions.Item label={t('template.idField')}>{detailTemplate.id}</Descriptions.Item>
              <Descriptions.Item label={t('template.operator')}>{OPERATOR_LABELS[detailTemplate.operator] || detailTemplate.operator}</Descriptions.Item>
              <Descriptions.Item label={t('template.monthPattern')}>{detailTemplate.month_pattern || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('template.statusField')}>
                {detailTemplate.is_active === 1 ? <Tag color="green">{t('template.statusActive')}</Tag> : <Tag>{t('template.statusInactive')}</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label={t('template.description')} span={2}>{detailTemplate.description || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('template.createdAtField')} span={2}>{new Date(detailTemplate.created_at).toLocaleString()}</Descriptions.Item>
            </Descriptions>

            <Typography.Title level={5}>{t('template.sheetConfigTitle')}</Typography.Title>
            {parseSheetConfigs(detailTemplate.sheet_configs).map((sheet, idx) => (
              <Card
                key={idx}
                size="small"
                title={
                  <Space>
                    <Tag color="blue">{SHEET_TYPE_LABELS[sheet.sheetType] || sheet.sheetType}</Tag>
                    <Text type="secondary">{t('template.matchLabel')} {sheet.sheetNamePattern}</Text>
                    {sheet.isQuarterly && <Tag color="orange">{t('template.quarterlyTag')}</Tag>}
                  </Space>
                }
                style={{ marginBottom: 8 }}
              >
                <Descriptions size="small" column={2}>
                  <Descriptions.Item label={t('template.phoneColumnLabel')}>{sheet.phoneColumn}</Descriptions.Item>
                  <Descriptions.Item label={t('template.extensionColumnLabel')}>{sheet.extensionColumn ?? '-'}</Descriptions.Item>
                  <Descriptions.Item label={t('template.skipRowsLabel')}>{sheet.skipRows}</Descriptions.Item>
                  <Descriptions.Item label={t('template.columnMappingCount')}>{sheet.columns?.length || 0}</Descriptions.Item>
                </Descriptions>
                {sheet.columns && sheet.columns.length > 0 && (
                  <div style={{ marginTop: 8 }}>
                    <Text strong>{t('template.columnDefTitle')}</Text>
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
                    <Text strong>{t('template.computedFieldsTitle')}</Text>
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
              <Empty description={t('template.noValidSheets')} />
            )}

            <Typography.Title level={5} style={{ marginTop: 16 }}>{t('template.rawJsonTitle')}</Typography.Title>
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
