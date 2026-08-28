import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Row, Col, message, Input, Button, Space, Modal, Form, Popconfirm } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, ExportOutlined, ReloadOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

import {
  getOrgCodeMappingEntries,
  createOrgCodeMapping,
  updateOrgCodeMapping,
  deleteOrgCodeMapping,
  batchDeleteOrgCodeMapping,
  importOrgCodeMapping,
  exportOrgCodeMapping,
  downloadOrgCodeMappingTemplate,
} from '../api/import';
import { useAuthStore } from '../store/auth';

/**
 * 组织机构对照表页面 — 数据维护菜单
 * 列：机构代码、机构名称、成本中心代码、备注、编辑（修改、删除）
 * 功能：新增、导入、导出、搜索、下载模板
 */
const OrgCodeMappingPage: React.FC = () => {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);

  // ==================== Data state ====================
  const [entries, setEntries] = useState<Record<string, unknown>[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');

  // ==================== CRUD modal ====================
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  // ==================== Batch delete ====================
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [batchDeleting, setBatchDeleting] = useState(false);

  // ==================== Fetch data ====================
  const fetchData = useCallback(async (keyword = '', p = 0, size = 50) => {
    setLoading(true);
    try {
      const data = await getOrgCodeMappingEntries(keyword || undefined, p, size);
      setEntries(data.entries || []);
      setTotal(data.total);
      setPage(data.page);
      setPageSize(data.size);
    } catch {
      message.error(t('orgCodeMapping.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // ==================== Search ====================
  const handleSearch = () => {
    setAppliedSearch(search);
    fetchData(search, 0, pageSize);
  };

  // ==================== Add / Edit ====================
  const openAddModal = () => {
    setEditingId(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEditModal = (record: Record<string, unknown>) => {
    setEditingId(record.id as number);
    form.setFieldsValue({
      l1_branch: record.l1_branch,
      org_code: record.org_code,
      org_name: record.org_name,
      cost_center_code: record.cost_center_code,
      remark: record.remark,
    });
    setModalOpen(true);
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const payload = {
        l1_branch: values.l1_branch || '',
        org_code: values.org_code || '',
        org_name: values.org_name || '',
        cost_center_code: values.cost_center_code || '',
        remark: values.remark || '',
      };
      if (editingId) {
        await updateOrgCodeMapping(editingId, payload);
        message.success(t('orgCodeMapping.updateSuccess'));
      } else {
        await createOrgCodeMapping(payload);
        message.success(t('orgCodeMapping.createSuccess'));
      }
      setModalOpen(false);
      fetchData(appliedSearch, page, pageSize);
    } catch (err) {
      if (err instanceof Error) {
        message.error(t('orgCodeMapping.saveFailed'));
      }
    } finally {
      setSaving(false);
    }
  };

  // ==================== Delete ====================
  const handleDelete = async (id: number) => {
    try {
      await deleteOrgCodeMapping(id);
      message.success(t('orgCodeMapping.deleteSuccess'));
      fetchData(appliedSearch, page, pageSize);
    } catch {
      message.error(t('orgCodeMapping.deleteFailed'));
    }
  };

  // ==================== Batch Delete ====================
  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) {
      message.warning(t('orgCodeMapping.batchDeleteNoSelection'));
      return;
    }
    try {
      setBatchDeleting(true);
      const result = await batchDeleteOrgCodeMapping(selectedRowKeys as number[]);
      message.success(t('orgCodeMapping.batchDeleteSuccess', { count: result.deleted }));
      setSelectedRowKeys([]);
      fetchData(appliedSearch, page, pageSize);
    } catch {
      message.error(t('orgCodeMapping.batchDeleteFailed'));
    } finally {
      setBatchDeleting(false);
    }
  };

  // ==================== Import ====================
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleImportFile = async (file: File) => {
    try {
      const result = await importOrgCodeMapping(file);
      message.success(t('orgCodeMapping.importSuccess', { count: result.imported }));
      fetchData(appliedSearch, page, pageSize);
    } catch (err) {
      message.error(t('orgCodeMapping.importFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    }
  };

  // ==================== Table columns ====================
  const columns = [
    {
      title: t('orgCodeMapping.colL1Branch'),
      dataIndex: 'l1_branch',
      key: 'l1_branch',
      width: 160,
    },
    {
      title: t('orgCodeMapping.colOrgCode'),
      dataIndex: 'org_code',
      key: 'org_code',
      width: 140,
      align: 'center' as const,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('orgCodeMapping.colOrgName'),
      dataIndex: 'org_name',
      key: 'org_name',
      width: 240,
    },
    {
      title: t('orgCodeMapping.colCostCenterCode'),
      dataIndex: 'cost_center_code',
      key: 'cost_center_code',
      width: 140,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('orgCodeMapping.colRemark'),
      dataIndex: 'remark',
      key: 'remark',
      width: 220,
      ellipsis: true,
    },
    {
      title: t('orgCodeMapping.colAction'),
      key: 'action',
      width: 160,
      render: (_: unknown, record: Record<string, unknown>) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            {t('common.edit')}
          </Button>
          <Popconfirm
            title={t('orgCodeMapping.deleteConfirmTitle')}
            description={t('orgCodeMapping.deleteConfirmContent')}
            onConfirm={() => handleDelete(record.id as number)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button type="link" size="small" danger>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // ==================== Render ====================
  return (
    <div style={{ padding: 24 }}>
      <Card
        title={t('orgCodeMapping.title')}
        styles={{ header: { background: COLORS.sageLight } }}
        extra={
          <Space>
            {canEdit && (
              <>
                <Popconfirm
                  title={t('orgCodeMapping.batchDeleteConfirmTitle')}
                  description={t('orgCodeMapping.batchDeleteConfirmContent', { count: selectedRowKeys.length })}
                  onConfirm={handleBatchDelete}
                  okText={t('common.confirm')}
                  cancelText={t('common.cancel')}
                  disabled={selectedRowKeys.length === 0 || batchDeleting}
                >
                  <Button
                    icon={<DeleteOutlined />}
                    danger
                    disabled={selectedRowKeys.length === 0 || batchDeleting}
                    loading={batchDeleting}
                  >
                    {t('orgCodeMapping.batchDelete')}{selectedRowKeys.length > 0 ? `(${selectedRowKeys.length})` : ''}
                  </Button>
                </Popconfirm>
                <Button icon={<PlusOutlined />} type="primary" onClick={openAddModal}>
                  {t('orgCodeMapping.add')}
                </Button>
                <Button icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()}>
                  {t('orgCodeMapping.importLabel')}
                </Button>
                <Button icon={<DownloadOutlined />} onClick={() => downloadOrgCodeMappingTemplate()}>
                  {t('orgCodeMapping.downloadTemplate')}
                </Button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".xlsx,.xls"
                  style={{ display: 'none' }}
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) {
                      handleImportFile(file);
                      e.target.value = '';
                    }
                  }}
                />
              </>
            )}
            <Button icon={<ExportOutlined />} onClick={() => exportOrgCodeMapping()}>
              {t('orgCodeMapping.export')}
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => fetchData(appliedSearch, page, pageSize)}
            >
              {t('common.refresh')}
            </Button>
          </Space>
        }
      >
        {/* Search */}
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col flex="auto" />
          <Col>
            <Space>
              <Input
                placeholder={t('orgCodeMapping.searchPlaceholder')}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onPressEnter={handleSearch}
                prefix={<SearchOutlined />}
                style={{ width: 220 }}
                allowClear
              />
              <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
                {t('common.search')}
              </Button>
            </Space>
          </Col>
        </Row>

        <Table
          dataSource={entries}
          columns={columns}
          rowKey="id"
          loading={loading}
          size="small"
          scroll={{ x: 1000 }}
          rowSelection={{
            selectedRowKeys,
            onChange: (keys) => setSelectedRowKeys(keys),
          }}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (total: number) => t('common.totalCount', { count: total }),
            onChange: (p, s) => {
              fetchData(appliedSearch, p - 1, s);
            },
          }}
        />
      </Card>

      {/* Add / Edit modal */}
      <Modal
        title={editingId ? t('orgCodeMapping.editTitle') : t('orgCodeMapping.addTitle')}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="l1_branch"
            label={t('orgCodeMapping.colL1Branch')}
            rules={[{ required: true, message: t('orgCodeMapping.l1BranchRequired') }]}
          >
            <Input maxLength={256} />
          </Form.Item>
          <Form.Item
            name="org_code"
            label={t('orgCodeMapping.colOrgCode')}
            rules={[{ required: true, message: t('orgCodeMapping.orgCodeRequired') }]}
          >
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item
            name="org_name"
            label={t('orgCodeMapping.colOrgName')}
            rules={[{ required: true, message: t('orgCodeMapping.orgNameRequired') }]}
          >
            <Input maxLength={256} />
          </Form.Item>
          <Form.Item name="cost_center_code" label={t('orgCodeMapping.colCostCenterCode')}>
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item name="remark" label={t('orgCodeMapping.colRemark')}>
            <Input.TextArea maxLength={512} rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default OrgCodeMappingPage;