import { useState, useEffect, useCallback, useRef } from 'react';
import { COLORS } from '../theme/morandi';
import { Card, Table, Tag, Row, Col, message, Input, Button, Dropdown, Space, Modal, Form, Popconfirm, DatePicker } from 'antd';
import { SearchOutlined, UploadOutlined, DownloadOutlined, ExportOutlined, EditOutlined, DeleteOutlined, PlusOutlined, WarningOutlined } from '@ant-design/icons';
import {
  getAllExceptionEntries,
  addExceptionEntry,
  updateExceptionEntry,
  deleteExceptionEntry,
  exportAllExceptions,
  downloadExceptionTemplate,
  importExceptions,
} from '../api/import';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../store/auth';
import dayjs from 'dayjs';

interface ExceptionEntry {
  id: number;
  phone_number: string;
  extension: string;
  full_path: string;
  description: string;
  match_level: string;
  matched_branch: string;
  matched_dept: string;
  exception_reason: string;
}

const ExceptionNumberPage: React.FC = () => {
  const { t } = useTranslation();
  const canEdit = useAuthStore((s) => s.role === 1 || s.role === 2);

  // ==================== Data state ====================
  const [entries, setEntries] = useState<ExceptionEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [filteredCount, setFilteredCount] = useState(0);

  // Import
  const [uploading, setUploading] = useState(false);
  const [importMonthModal, setImportMonthModal] = useState<{ open: boolean; file: File | null }>({ open: false, file: null });
  const [importBillingMonth, setImportBillingMonth] = useState<string>(dayjs().format('YYYY-MM'));
  const [importProgress, setImportProgress] = useState<string>('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Add/Edit modal
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [addLoading, setAddLoading] = useState(false);
  const [addForm] = Form.useForm();

  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editLoading, setEditLoading] = useState(false);
  const [editForm] = Form.useForm();
  const [editingEntry, setEditingEntry] = useState<ExceptionEntry | null>(null);

  // ==================== Data fetching ====================

  const fetchData = useCallback(async (keyword = '', p = 0, size = 50) => {
    setLoading(true);
    try {
      const data = await getAllExceptionEntries(keyword || undefined, p, size);
      setEntries(data.entries);
      setTotal(data.total);
      setFilteredCount(data.filtered ?? data.total);
      setPage(data.page);
      setPageSize(data.size);
    } catch {
      message.error(t('exceptionNumber.fetchFailed'));
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
      await addExceptionEntry({
        billing_month: values.billing_month?.format ? values.billing_month.format('YYYY-MM') : values.billing_month,
        phone_number: values.phone_number,
        extension: values.extension || '',
        full_path: values.full_path || '',
        l1_branch: values.l1_branch || '',
        l2_branch: values.l2_branch || '',
        description: values.description || '',
      });
      message.success(t('exceptionNumber.addSuccess'));
      setAddModalOpen(false);
      addForm.resetFields();
      fetchData(appliedSearch, 0, pageSize);
    } catch (err) {
      if (err instanceof Error) message.error(t('exceptionNumber.addFailed', { error: err.message }));
    } finally {
      setAddLoading(false);
    }
  };

  // Edit
  const handleEdit = (record: ExceptionEntry) => {
    setEditingEntry(record);
      editForm.setFieldsValue({
        phone_number: record.phone_number,
        extension: record.extension,
        full_path: record.full_path,
        l1_branch: record.matched_branch || '',
        l2_branch: record.matched_dept || '',
        description: record.exception_reason || record.description,
      });
    setEditModalOpen(true);
  };

  const handleEditOk = async () => {
    if (!editingEntry) return;
    try {
      const values = await editForm.validateFields();
      setEditLoading(true);
      await updateExceptionEntry(editingEntry.id, {
        phone_number: values.phone_number,
        extension: values.extension || '',
        full_path: values.full_path || '',
        l1_branch: values.l1_branch || '',
        l2_branch: values.l2_branch || '',
        description: values.description || '',
      });
      message.success(t('exceptionNumber.editSuccess'));
      setEditModalOpen(false);
      editForm.resetFields();
      setEditingEntry(null);
      fetchData(appliedSearch, page, pageSize);
    } catch (err) {
      if (err instanceof Error) message.error(t('exceptionNumber.editFailed', { error: err.message }));
    } finally {
      setEditLoading(false);
    }
  };

  // Delete
  const handleDelete = async (id: number) => {
    try {
      await deleteExceptionEntry(id);
      message.success(t('exceptionNumber.deleteSuccess'));
      fetchData(appliedSearch, page, pageSize);
    } catch (err) {
      message.error(t('exceptionNumber.deleteFailed', { error: err instanceof Error ? err.message : '' }));
    }
  };

  // Import
  const handleFileSelected = (file: File) => {
    setImportMonthModal({ open: true, file });
  };

  const handleConfirmImport = async () => {
    if (!importMonthModal.file || !importBillingMonth) {
      message.warning(t('exceptionNumber.selectMonthFirst'));
      return;
    }
    const file = importMonthModal.file;
    const month = importBillingMonth;
    setImportMonthModal({ open: false, file: null });
    setUploading(true);
    setImportProgress(t('exceptionNumber.importing'));
    try {
      const result = await importExceptions(file, month);
      message.success(`${t('exceptionNumber.importSuccess')}（${result.imported} ${t('exceptionNumber.importCount')}）`);
      fetchData(appliedSearch, 0, pageSize);
    } catch (err) {
      message.error(`${t('exceptionNumber.importFailed')}：${err instanceof Error ? err.message : ''}`);
    } finally {
      setUploading(false);
      setImportProgress('');
    }
  };

  // Export
  const handleExport = () => {
    exportAllExceptions();
    message.info(t('exceptionNumber.exportStarted'));
  };

  // ==================== Columns ====================

  const columns = [
    { title: t('phoneOwnership.phoneCol2'), dataIndex: 'phone_number', key: 'phone_number', width: 130, fixed: 'left' as const },
    { title: t('phoneOwnership.extensionCol'), dataIndex: 'extension', key: 'extension', width: 100,
      render: (v: string) => v || '-' },
    { title: t('phoneOwnership.fullPathCol'), dataIndex: 'full_path', key: 'full_path', width: 220,
      render: (v: string) => v || '-' },
    { title: t('phoneOwnership.branchCol'), dataIndex: 'matched_branch', key: 'matched_branch', width: 140,
      render: (v: string) => v || '-' },
    { title: t('phoneOwnership.deptCol'), dataIndex: 'matched_dept', key: 'matched_dept', width: 140,
      render: (v: string) => v || '-' },
    { title: t('exceptionNumber.exceptionReason'), dataIndex: 'exception_reason', key: 'exception_reason', width: 160,
      render: (v: string) => v ? <Tag color={COLORS.danger}>{v}</Tag> : '-' },
    ...(canEdit ? [{
      title: t('exceptionNumber.actions'), key: 'actions', width: 100, fixed: 'right' as const,
      render: (_: unknown, record: ExceptionEntry) => (
        <Space size={0}>
          <Button type="text" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} />
          <Popconfirm
            title={t('exceptionNumber.deleteConfirmTitle')}
            description={record.phone_number}
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
              placeholder={t('exceptionNumber.searchPlaceholder')}
              allowClear
              size="small"
              style={{ width: 220 }}
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
              <Button icon={<PlusOutlined />} onClick={() => { addForm.resetFields(); addForm.setFieldValue('billing_month', dayjs()); setAddModalOpen(true); }}>
                {t('exceptionNumber.add')}
              </Button>
            )}
            <Dropdown menu={{ items: [
              { key: 'import', icon: <UploadOutlined />, label: t('exceptionNumber.importShortLabel'), disabled: uploading },
              { key: 'template', icon: <DownloadOutlined />, label: t('exceptionNumber.downloadTemplate') },
            ], onClick: ({ key }) => {
              if (key === 'import') fileInputRef.current?.click();
              if (key === 'template') downloadExceptionTemplate();
            } }}>
              <Button icon={<UploadOutlined />} loading={uploading}>
                {t('exceptionNumber.importShortLabel')}
              </Button>
            </Dropdown>
            <input type="file" accept=".xlsx,.xls" ref={fileInputRef} style={{ display: 'none' }}
              onChange={(e) => { const f = e.target.files?.[0]; if (f) { handleFileSelected(f); e.target.value = ''; } }} />
            {uploading && importProgress && (
              <span style={{ color: COLORS.textMuted, fontSize: 12 }}>{importProgress}</span>
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
          scroll={{ x: canEdit ? 1100 : 990 }}
          title={() => (
            <span style={{ fontWeight: 500 }}>
              <WarningOutlined style={{ color: COLORS.danger, marginRight: 6 }} />
              {t('exceptionNumber.totalCountLabel', { total })}
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
        title={t('exceptionNumber.addTitle')}
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
          <Form.Item name="billing_month" label={t('exceptionNumber.billingMonth')} rules={[{ required: true, message: t('exceptionNumber.monthRequired') }]}>
            <DatePicker picker="month" style={{ width: '100%' }} format="YYYY-MM" />
          </Form.Item>
          <Form.Item name="phone_number" label={t('exceptionNumber.phoneNumber')} rules={[{ required: true, message: t('exceptionNumber.phoneRequired') }]}>
            <Input placeholder="01064771766" />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="extension" label={t('exceptionNumber.extensionLabel')}>
                <Input placeholder="8001" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="full_path" label={t('exceptionNumber.fullPathLabel')}>
                <Input placeholder="/北京分行/科技部" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="l1_branch" label={t('exceptionNumber.branchLabel')}>
                <Input placeholder={t('exceptionNumber.branchPlaceholder')} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="l2_branch" label={t('exceptionNumber.deptLabel')}>
                <Input placeholder={t('exceptionNumber.deptPlaceholder')} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label={t('exceptionNumber.descriptionLabel')}>
            <Input.TextArea rows={2} placeholder={t('exceptionNumber.descriptionPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit modal */}
      <Modal
        title={t('exceptionNumber.editTitle')}
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
          <Form.Item name="phone_number" label={t('exceptionNumber.phoneNumber')} rules={[{ required: true, message: t('exceptionNumber.phoneRequired') }]}>
            <Input />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="extension" label={t('exceptionNumber.extensionLabel')}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="full_path" label={t('exceptionNumber.fullPathLabel')}>
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="l1_branch" label={t('exceptionNumber.branchLabel')}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="l2_branch" label={t('exceptionNumber.deptLabel')}>
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label={t('exceptionNumber.descriptionLabel')}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Import month picker modal */}
      <Modal
        title={t('exceptionNumber.importMonthTitle')}
        open={importMonthModal.open}
        onOk={handleConfirmImport}
        onCancel={() => setImportMonthModal({ open: false, file: null })}
        okText={t('exceptionNumber.confirmImport')}
        okButtonProps={{ disabled: !importBillingMonth }}
      >
        <p style={{ marginBottom: 12 }}>{t('exceptionNumber.importMonthHint')}</p>
        <DatePicker
          picker="month"
          style={{ width: '100%' }}
          value={importBillingMonth ? dayjs(importBillingMonth, 'YYYY-MM') : null}
          onChange={(_, dateString) => setImportBillingMonth(dateString as string)}
          allowClear={false}
        />
        {importMonthModal.file && (
          <p style={{ marginTop: 8, color: COLORS.textMuted, fontSize: 12 }}>
            {t('exceptionNumber.importFile')}{importMonthModal.file.name}
          </p>
        )}
      </Modal>
    </div>
  );
};

export default ExceptionNumberPage;
