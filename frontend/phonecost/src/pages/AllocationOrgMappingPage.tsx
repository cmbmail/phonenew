import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Row, Col, message, Input, Button, Space, Modal, Form, Popconfirm, Tooltip } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, ExportOutlined, ReloadOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

import {
  getAllocationOrgMappingEntries,
  createAllocationOrgMapping,
  updateAllocationOrgMapping,
  deleteAllocationOrgMapping,
  batchDeleteAllocationOrgMapping,
  importAllocationOrgMapping,
  exportAllocationOrgMapping,
  downloadAllocationOrgMappingTemplate,
} from '../api/import';
import { useAuthStore } from '../store/auth';

/**
 * 分摊机构对照表页面 — 基础数据菜单
 * 列：一级分行、机构名称、机构代码、成本中心代码、部门全路径（多个以、分隔）、备注、操作（编辑、删除）
 * 规则：机构名称、机构代码、成本中心代码均可重复（如不同分行均有「营业部」）
 *       唯一性仅体现在部门全路径：其值在同一一级分行下唯一；同一个部门全路径可对应多个不同一级分行
 */
const AllocationOrgMappingPage: React.FC = () => {
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
      const data = await getAllocationOrgMappingEntries(keyword || undefined, p, size);
      setEntries(data.entries || []);
      setTotal(data.total);
      setPage(data.page);
      setPageSize(data.size);
    } catch {
      message.error(t('allocationOrgMapping.fetchFailed'));
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
      org_name: record.org_name,
      org_code: record.org_code,
      cost_center_code: record.cost_center_code,
      dept_full_path: record.dept_full_path,
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
        org_name: values.org_name || '',
        org_code: values.org_code || '',
        cost_center_code: values.cost_center_code || '',
        dept_full_path: values.dept_full_path || '',
        remark: values.remark || '',
      };
      if (editingId) {
        await updateAllocationOrgMapping(editingId, payload);
        message.success(t('allocationOrgMapping.updateSuccess'));
      } else {
        await createAllocationOrgMapping(payload);
        message.success(t('allocationOrgMapping.createSuccess'));
      }
      setModalOpen(false);
      fetchData(appliedSearch, page, pageSize);
    } catch (err) {
      // 表单校验错误不提示，接口错误由 request 拦截器统一弹出
      if (err instanceof Error && !('errorFields' in (err as unknown as object))) {
        message.error(t('allocationOrgMapping.saveFailed'));
      }
    } finally {
      setSaving(false);
    }
  };

  // ==================== Delete ====================
  const handleDelete = async (id: number) => {
    try {
      await deleteAllocationOrgMapping(id);
      message.success(t('allocationOrgMapping.deleteSuccess'));
      fetchData(appliedSearch, page, pageSize);
    } catch {
      message.error(t('allocationOrgMapping.deleteFailed'));
    }
  };

  // ==================== Batch Delete ====================
  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) {
      message.warning(t('allocationOrgMapping.batchDeleteNoSelection'));
      return;
    }
    try {
      setBatchDeleting(true);
      const result = await batchDeleteAllocationOrgMapping(selectedRowKeys as number[]);
      message.success(t('allocationOrgMapping.batchDeleteSuccess', { count: result.deleted }));
      setSelectedRowKeys([]);
      fetchData(appliedSearch, page, pageSize);
    } catch {
      message.error(t('allocationOrgMapping.batchDeleteFailed'));
    } finally {
      setBatchDeleting(false);
    }
  };

  // ==================== Import ====================
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleImportFile = async (file: File) => {
    try {
      const result = await importAllocationOrgMapping(file);
      if (result.skipped > 0) {
        message.warning(t('allocationOrgMapping.importWithSkip', { count: result.imported, skipped: result.skipped }));
      } else {
        message.success(t('allocationOrgMapping.importSuccess', { count: result.imported }));
      }
      fetchData(appliedSearch, page, pageSize);
    } catch (err) {
      message.error(t('allocationOrgMapping.importFailed', {
        error: err instanceof Error ? err.message : t('common.unknown'),
      }));
    }
  };

  // ==================== Table columns ====================
  const columns = [
    {
      title: t('allocationOrgMapping.colL1Branch'),
      dataIndex: 'l1_branch',
      key: 'l1_branch',
      width: 140,
    },
    {
      title: t('allocationOrgMapping.colOrgName'),
      dataIndex: 'org_name',
      key: 'org_name',
      width: 200,
    },
    {
      title: t('allocationOrgMapping.colOrgCode'),
      dataIndex: 'org_code',
      key: 'org_code',
      width: 120,
      align: 'center' as const,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOrgMapping.colCostCenterCode'),
      dataIndex: 'cost_center_code',
      key: 'cost_center_code',
      width: 130,
      align: 'center' as const,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: t('allocationOrgMapping.colDeptFullPath'),
      dataIndex: 'dept_full_path',
      key: 'dept_full_path',
      width: 320,
      render: (v: string) => (
        <Tooltip title={v} placement="topLeft">
          <span style={{ display: 'inline-block', maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }}>
            {v}
          </span>
        </Tooltip>
      ),
    },
    {
      title: t('allocationOrgMapping.colRemark'),
      dataIndex: 'remark',
      key: 'remark',
      width: 180,
      ellipsis: true,
    },
    {
      title: t('allocationOrgMapping.colAction'),
      key: 'action',
      width: 130,
      render: (_: unknown, record: Record<string, unknown>) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            {t('common.edit')}
          </Button>
          <Popconfirm
            title={t('allocationOrgMapping.deleteConfirmTitle')}
            description={t('allocationOrgMapping.deleteConfirmContent')}
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
        title={t('allocationOrgMapping.title')}
        styles={{ header: { background: COLORS.sageLight } }}
        extra={
          <Space>
            {canEdit && (
              <>
                <Popconfirm
                  title={t('allocationOrgMapping.batchDeleteConfirmTitle')}
                  description={t('allocationOrgMapping.batchDeleteConfirmContent', { count: selectedRowKeys.length })}
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
                    {t('allocationOrgMapping.batchDelete')}{selectedRowKeys.length > 0 ? `(${selectedRowKeys.length})` : ''}
                  </Button>
                </Popconfirm>
                <Button icon={<PlusOutlined />} type="primary" onClick={openAddModal}>
                  {t('allocationOrgMapping.add')}
                </Button>
                <Button icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()}>
                  {t('allocationOrgMapping.importLabel')}
                </Button>
                <Button icon={<DownloadOutlined />} onClick={() => downloadAllocationOrgMappingTemplate()}>
                  {t('allocationOrgMapping.downloadTemplate')}
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
            <Button icon={<ExportOutlined />} onClick={() => exportAllocationOrgMapping()}>
              {t('allocationOrgMapping.export')}
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
                placeholder={t('allocationOrgMapping.searchPlaceholder')}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onPressEnter={handleSearch}
                prefix={<SearchOutlined />}
                style={{ width: 260 }}
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
          scroll={{ x: 1200 }}
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
        title={editingId ? t('allocationOrgMapping.editTitle') : t('allocationOrgMapping.addTitle')}
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
            label={t('allocationOrgMapping.colL1Branch')}
            rules={[{ required: true, message: t('allocationOrgMapping.l1BranchRequired') }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item
            name="org_name"
            label={t('allocationOrgMapping.colOrgName')}
            rules={[{ required: true, message: t('allocationOrgMapping.orgNameRequired') }]}
          >
            <Input maxLength={256} />
          </Form.Item>
          <Form.Item
            name="org_code"
            label={t('allocationOrgMapping.colOrgCode')}
            rules={[{ required: true, message: t('allocationOrgMapping.orgCodeRequired') }]}
          >
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item name="cost_center_code" label={t('allocationOrgMapping.colCostCenterCode')}>
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item
            name="dept_full_path"
            label={t('allocationOrgMapping.colDeptFullPath')}
            extra={t('allocationOrgMapping.deptHint')}
          >
            <Input.TextArea maxLength={2000} rows={3} />
          </Form.Item>
          <Form.Item name="remark" label={t('allocationOrgMapping.colRemark')}>
            <Input.TextArea maxLength={512} rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AllocationOrgMappingPage;
