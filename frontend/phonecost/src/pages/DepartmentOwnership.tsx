import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Row, Col, message, Input, Button, Dropdown, Space, Modal, Form, Popconfirm, Progress } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, ExportOutlined, EditOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import {
  getAllDirectoryEntries,
  addDirectoryEntry,
  deleteDirectoryEntry,
  exportCostCenterEntries,
  downloadCostCenterTemplate,
  importDirectory,
  getDirectoryProgress,
  updateDirectoryEntry,
} from '../api/import';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../store/auth';
import { useImportProgress } from '../hooks/useImportProgress';
import type { ImportProgress } from '../types/import';

interface DirectoryEntryItem {
  id: number;
  batch_id: number;
  dept_path: string;
  username: string;
  extension: string;
  phone_number: string;
  alloc_dept: string;
  org_code: string;
  cost_center: string;
  remark: string;
  org_id: number | null;
  is_seconded: number;
  actual_org_id: number | null;
  seconded_keyword: string;
}

export default function DepartmentOwnership() {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);

  // ==================== Data state ====================
  const [entries, setEntries] = useState<DirectoryEntryItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [filteredCount, setFilteredCount] = useState(0);

  // Import
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const { progress: importProgress, polling: importPolling, startPolling, percent: importPercent } = useImportProgress({
    onComplete: (p: ImportProgress) => {
      message.success(t('deptOwnership.importSuccess', { total: p.total }));
      setUploading(false);
      fetchData(appliedSearch, 0, pageSize);
    },
    onError: (p: ImportProgress) => {
      message.error(t('deptOwnership.importFailed', { error: p.message || t('common.unknown') }));
      setUploading(false);
    },
  });

  // Add modal
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [addLoading, setAddLoading] = useState(false);
  const [addForm] = Form.useForm();

  // Edit modal
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editLoading, setEditLoading] = useState(false);
  const [editForm] = Form.useForm();
  const [editingEntry, setEditingEntry] = useState<DirectoryEntryItem | null>(null);

  // ==================== Data fetching ====================

  const fetchData = useCallback(async (keyword = '', p = 0, size = 50) => {
    setLoading(true);
    try {
      const data = await getAllDirectoryEntries(keyword || undefined, p, size);
      setEntries(data.entries);
      setTotal(data.total);
      setFilteredCount(data.filtered ?? data.total);
      setPage(data.page);
      setPageSize(data.size);
    } catch {
      message.error(t('deptOwnership.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => { fetchData(); }, [fetchData]);

  // ==================== Handlers ====================

  const handleSearch = () => {
    setAppliedSearch(search);
    fetchData(search, 0, pageSize);
  };

  // Add
  const handleAddOk = async () => {
    try {
      const values = await addForm.validateFields();
      setAddLoading(true);
      await addDirectoryEntry({
        dept_path: values.dept_path,
        username: values.username || '',
        extension: values.extension || '',
        phone_number: '',
        alloc_dept: values.alloc_dept || '',
        org_code: values.org_code || '',
        cost_center: values.cost_center || '',
        remark: values.remark || '',
      });
      message.success(t('deptOwnership.editSuccess'));
      setAddModalOpen(false);
      addForm.resetFields();
      fetchData(appliedSearch, 0, pageSize);
    } catch (err) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setAddLoading(false);
    }
  };

  // Edit
  const handleEdit = (record: DirectoryEntryItem) => {
    setEditingEntry(record);
    editForm.setFieldsValue({
      dept_path: record.dept_path,
      alloc_dept: record.alloc_dept,
      org_code: record.org_code,
      cost_center: record.cost_center,
      remark: record.remark,
    });
    setEditModalOpen(true);
  };

  const handleEditOk = async () => {
    if (!editingEntry) return;
    try {
      const values = await editForm.validateFields();
      setEditLoading(true);
      await updateDirectoryEntry(editingEntry.id, {
        dept_path: values.dept_path,
        alloc_dept: values.alloc_dept || '',
        org_code: values.org_code || '',
        cost_center: values.cost_center || '',
        remark: values.remark || '',
      });
      message.success(t('deptOwnership.editSuccess'));
      setEditModalOpen(false);
      editForm.resetFields();
      setEditingEntry(null);
      fetchData(appliedSearch, page, pageSize);
    } catch (err) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setEditLoading(false);
    }
  };

  // Delete
  const handleDelete = async (id: number) => {
    try {
      await deleteDirectoryEntry(id);
      message.success(t('deptOwnership.deleteSuccess'));
      fetchData(appliedSearch, page, pageSize);
    } catch (err) {
      message.error(t('deptOwnership.deleteFailed', { error: err instanceof Error ? err.message : '' }));
    }
  };

  // Import
  const handleUpload = async (file: File) => {
    setUploading(true);
    try {
      const result = await importDirectory(file);
      startPolling(result.batch_id, getDirectoryProgress);
    } catch (err) {
      message.error(t('deptOwnership.importFailed', { error: err instanceof Error ? err.message : t('common.unknown') }));
      setUploading(false);
    }
  };

  // Export
  const handleExport = () => {
    exportCostCenterEntries();
    message.info(t('exceptionNumber.exportStarted'));
  };

  // ==================== Columns ====================

  const columns = [
    { title: t('deptOwnership.l1BranchCol'), key: 'l1_branch', width: 120, fixed: 'left' as const,
      render: (_: unknown, record: DirectoryEntryItem) => {
        if (!record.dept_path) return '-';
        const parts = record.dept_path.split('-');
        return parts.length >= 2 ? parts[1] || '-' : '-';
      } },
    { title: t('deptOwnership.deptPathCol'), dataIndex: 'dept_path', key: 'dept_path', width: 200, ellipsis: true },
    { title: t('deptOwnership.allocDeptCol'), dataIndex: 'alloc_dept', key: 'alloc_dept', width: 120,
      render: (v: string) => v || '-' },
    { title: t('deptOwnership.orgCodeCol'), dataIndex: 'org_code', key: 'org_code', width: 100,
      render: (v: string) => v || '-' },
    { title: t('deptOwnership.costCenterCol'), dataIndex: 'cost_center', key: 'cost_center', width: 100,
      render: (v: string) => v || '-' },
    { title: t('deptOwnership.remarkCol'), dataIndex: 'remark', key: 'remark', width: 150, ellipsis: true,
      render: (v: string) => v || '-' },
    ...(canEdit ? [{
      title: t('deptOwnership.editCol'), key: 'actions', width: 100, fixed: 'right' as const,
      render: (_: unknown, record: DirectoryEntryItem) => (
        <Space size={0}>
          <Button type="text" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} />
          <Popconfirm
            title={t('deptOwnership.deleteConfirm')}
            description={record.dept_path}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
          >
            <Button type="text" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    }] : []),
  ];

  // ==================== Render ====================

  return (
    <div>
      {/* Top toolbar: search on the left, action buttons on the right */}
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col>
          <Space>
            <Input
              prefix={<SearchOutlined />}
              placeholder={t('deptOwnership.searchPlaceholder')}
              allowClear
              size="small"
              style={{ width: 260 }}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onPressEnter={handleSearch}
            />
            <Button size="small" type="primary" onClick={handleSearch} icon={<SearchOutlined />}>
              {t('common.search')}
            </Button>
            {appliedSearch && (
              <span style={{ color: COLORS.textMuted, fontSize: 12 }}>
                {t('exceptionNumber.searchResult', { count: filteredCount })}
              </span>
            )}
          </Space>
        </Col>
        <Col flex="auto" />
        <Col>
          <Space>
            {canEdit && (
              <Button icon={<PlusOutlined />} onClick={() => { addForm.resetFields(); setAddModalOpen(true); }}>
                {t('exceptionNumber.add')}
              </Button>
            )}
            <Dropdown menu={{ items: [
              { key: 'import', icon: <UploadOutlined />, label: t('deptOwnership.importLabel'), disabled: uploading },
              { key: 'template', icon: <DownloadOutlined />, label: t('deptOwnership.downloadTemplate') },
            ], onClick: ({ key }) => {
              if (key === 'import') fileInputRef.current?.click();
              if (key === 'template') downloadCostCenterTemplate();
            } }}>
              <Button icon={<UploadOutlined />} loading={uploading}>
                {t('deptOwnership.importLabel')}
              </Button>
            </Dropdown>
            <input type="file" accept=".xlsx,.xls" ref={fileInputRef} style={{ display: 'none' }}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleUpload(f); e.target.value = ''; } }} />
            {uploading && importPolling && importProgress && (
              <Progress
                percent={importPercent}
                size="small"
                style={{ width: 160, display: 'inline-block', verticalAlign: 'middle' }}
                format={() => importProgress.message || `${importProgress.processed}/${importProgress.total}`}
              />
            )}
            <Button icon={<ExportOutlined />} onClick={handleExport}>
              {t('exceptionNumber.export')}
            </Button>
          </Space>
        </Col>
      </Row>

      {/* Main table */}
      <Card>
        <Table
          columns={columns}
          dataSource={entries}
          rowKey="id"
          size="small"
          loading={loading}
          scroll={{ x: canEdit ? 1000 : 890 }}
          title={() => (
            <span style={{ fontWeight: 500 }}>
              {t('deptOwnership.totalCount', { total })}
            </span>
          )}
          pagination={{
            current: page + 1,
            pageSize,
            total: filteredCount,
            showSizeChanger: true,
            pageSizeOptions: ['20', '50', '100'],
            showTotal: (total) => t('common.paginationTotal', { total }),
            onChange: (p, s) => {
              fetchData(appliedSearch, p - 1, s);
            },
          }}
        />
      </Card>

      {/* Add modal */}
      <Modal
        title={t('exceptionNumber.add')}
        open={addModalOpen}
        onOk={handleAddOk}
        onCancel={() => { setAddModalOpen(false); addForm.resetFields(); }}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={addLoading}
        width={560}
        destroyOnClose
      >
        <Form form={addForm} layout="vertical" preserve={false} style={{ marginTop: 16 }}>
          <Form.Item name="dept_path" label={t('deptOwnership.deptPathCol')} rules={[{ required: true, message: t('deptOwnership.deptPathRequired') }]}>
            <Input placeholder="/北京分行/科技部" />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="username" label={t('deptOwnership.usernameCol')}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="extension" label={t('phoneOwnership.extensionCol')}>
                <Input placeholder="8001" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="alloc_dept" label={t('deptOwnership.allocDeptCol')}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="org_code" label={t('deptOwnership.orgCodeCol')}>
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="cost_center" label={t('deptOwnership.costCenterCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="remark" label={t('deptOwnership.remarkCol')}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit modal */}
      <Modal
        title={t('deptOwnership.editTitle')}
        open={editModalOpen}
        onOk={handleEditOk}
        onCancel={() => { setEditModalOpen(false); editForm.resetFields(); setEditingEntry(null); }}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={editLoading}
        width={560}
        destroyOnClose
      >
        <Form form={editForm} layout="vertical" preserve={false} style={{ marginTop: 16 }}>
          <Form.Item name="dept_path" label={t('deptOwnership.deptPathCol')} rules={[{ required: true, message: t('deptOwnership.deptPathRequired') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="alloc_dept" label={t('deptOwnership.allocDeptCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="org_code" label={t('deptOwnership.orgCodeCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="cost_center" label={t('deptOwnership.costCenterCol')}>
            <Input />
          </Form.Item>
          <Form.Item name="remark" label={t('deptOwnership.remarkCol')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
