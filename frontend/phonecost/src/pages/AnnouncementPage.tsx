import { useState, useEffect, useCallback } from 'react';
import { COLORS } from '../theme/morandi';
import {
  Card,
  Table,
  Form,
  Modal,
  Input,
  Select,
  Button,
  Space,
  Tag,
  Badge,
  message,
  Popconfirm,
  Tooltip,
  Empty,
  Typography,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  SendOutlined,
  InboxOutlined,
  PushpinOutlined,
  NotificationOutlined,
  FileTextOutlined,
  SearchOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getAnnouncements,
  createAnnouncement,
  updateAnnouncement,
  publishAnnouncement,
  archiveAnnouncement,
  deleteAnnouncement,
} from '../api/announcement';
import type { AnnouncementItem } from '../api/announcement';
import { getErrorMessage } from '../types/api';
import dayjs from 'dayjs';

const { TextArea } = Input;
const { Paragraph } = Typography;

const STATUS_MAP: Record<number, { label: string; color: string; badge: 'default' | 'processing' | 'success' | 'warning' | 'error' }> = {
  0: { label: '草稿', color: COLORS.textMuted, badge: 'default' },
  1: { label: '已发布', color: COLORS.confirmed, badge: 'success' },
  2: { label: '已归档', color: COLORS.slate, badge: 'warning' },
};

const TYPE_MAP: Record<number, { label: string; color: string }> = {
  0: { label: '通知', color: COLORS.slate },
  1: { label: '公告', color: COLORS.sage },
};

const PRIORITY_MAP: Record<number, { label: string; color: string }> = {
  0: { label: '普通', color: 'default' },
  1: { label: '重要', color: COLORS.pending },
  2: { label: '紧急', color: COLORS.danger },
};

export default function AnnouncementPage() {
  const { t } = useTranslation();

  const [data, setData] = useState<AnnouncementItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [filterStatus, setFilterStatus] = useState<number | undefined>();
  const [filterType, setFilterType] = useState<number | undefined>();
  const [keyword, setKeyword] = useState('');

  // Modals
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [detailModalOpen, setDetailModalOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<AnnouncementItem | null>(null);
  const [viewingItem, setViewingItem] = useState<AnnouncementItem | null>(null);
  const [isCreate, setIsCreate] = useState(false);
  const [form] = Form.useForm();

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getAnnouncements({
        page,
        size: pageSize,
        status: filterStatus,
        type: filterType,
        keyword: keyword || undefined,
      });
      setData(result.content);
      setTotal(result.totalElements);
    } catch {
      message.error(t('announcement.fetchFailed'));
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, filterStatus, filterType, keyword, t]);

  useEffect(() => { fetchData(); }, [fetchData]);

  // ---- Create / Edit ----
  const openCreate = () => {
    setIsCreate(true);
    setEditingItem(null);
    form.resetFields();
    form.setFieldsValue({ type: 0, priority: 0, pinned: 0 });
    setEditModalOpen(true);
  };

  const openEdit = (item: AnnouncementItem) => {
    setIsCreate(false);
    setEditingItem(item);
    form.setFieldsValue({
      title: item.title,
      content: item.content,
      type: item.type,
      priority: item.priority,
      pinned: item.pinned,
    });
    setEditModalOpen(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (isCreate) {
        await createAnnouncement(values);
        message.success(t('announcement.createSuccess'));
      } else {
        await updateAnnouncement(editingItem!.id, values);
        message.success(t('announcement.updateSuccess'));
      }
      setEditModalOpen(false);
      form.resetFields();
      fetchData();
    } catch (err) {
      if (err && typeof err === 'object' && 'errorFields' in err) return; // form validation
      message.error(getErrorMessage(err, t('common.failed')));
    }
  };

  // ---- Actions ----
  const handlePublish = async (id: number) => {
    try {
      await publishAnnouncement(id);
      message.success(t('announcement.publishSuccess'));
      fetchData();
    } catch (err) {
      message.error(getErrorMessage(err, t('common.failed')));
    }
  };

  const handleArchive = async (id: number) => {
    try {
      await archiveAnnouncement(id);
      message.success(t('announcement.archiveSuccess'));
      fetchData();
    } catch (err) {
      message.error(getErrorMessage(err, t('common.failed')));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteAnnouncement(id);
      message.success(t('announcement.deleteSuccess'));
      fetchData();
    } catch (err) {
      message.error(getErrorMessage(err, t('common.failed')));
    }
  };

  // ---- Detail View ----
  const openDetail = (item: AnnouncementItem) => {
    setViewingItem(item);
    setDetailModalOpen(true);
  };

  // ---- Table Columns ----
  const columns = [
    {
      title: t('announcement.colTitle'),
      dataIndex: 'title',
      key: 'title',
      width: 280,
      render: (text: string, record: AnnouncementItem) => (
        <Space>
          {record.pinned === 1 && (
            <PushpinOutlined style={{ color: COLORS.pending, fontSize: 13 }} />
          )}
          <a onClick={() => openDetail(record)} style={{ color: COLORS.charcoal, fontWeight: record.pinned === 1 ? 600 : 400 }}>
            {text}
          </a>
        </Space>
      ),
    },
    {
      title: t('announcement.colType'),
      dataIndex: 'type',
      key: 'type',
      width: 80,
      align: 'center' as const,
      render: (v: number) => {
        const m = TYPE_MAP[v] || TYPE_MAP[0];
        return <Tag color={m.color} style={{ fontSize: 12 }}>{m.label}</Tag>;
      },
    },
    {
      title: t('announcement.colPriority'),
      dataIndex: 'priority',
      key: 'priority',
      width: 80,
      align: 'center' as const,
      render: (v: number) => {
        const m = PRIORITY_MAP[v] || PRIORITY_MAP[0];
        return <Tag color={m.color} style={{ fontSize: 12 }}>{m.label}</Tag>;
      },
    },
    {
      title: t('announcement.colStatus'),
      dataIndex: 'status',
      key: 'status',
      width: 90,
      align: 'center' as const,
      render: (v: number) => {
        const m = STATUS_MAP[v] || STATUS_MAP[0];
        return <Badge status={m.badge} text={<span style={{ fontSize: 12 }}>{m.label}</span>} />;
      },
    },
    {
      title: t('announcement.colAuthor'),
      dataIndex: 'author_name',
      key: 'author_name',
      width: 100,
      render: (v: string) => v || '-',
    },
    {
      title: t('announcement.colPublishedAt'),
      dataIndex: 'published_at',
      key: 'published_at',
      width: 140,
      render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : <span style={{ color: COLORS.textMuted }}>-</span>,
    },
    {
      title: t('announcement.colCreatedAt'),
      dataIndex: 'created_at',
      key: 'created_at',
      width: 140,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 220,
      render: (_: unknown, record: AnnouncementItem) => (
        <Space size="small">
          <Tooltip title={t('announcement.viewDetail')}>
            <Button size="small" icon={<EyeOutlined />} onClick={() => openDetail(record)} />
          </Tooltip>
          {record.status === 0 && (
            <>
              <Tooltip title={t('announcement.editBtn')}>
                <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)} />
              </Tooltip>
              <Tooltip title={t('announcement.publishBtn')}>
                <Button size="small" type="primary" icon={<SendOutlined />} onClick={() => handlePublish(record.id)} />
              </Tooltip>
            </>
          )}
          {record.status === 1 && (
            <Tooltip title={t('announcement.archiveBtn')}>
              <Button size="small" icon={<InboxOutlined />} onClick={() => handleArchive(record.id)} />
            </Tooltip>
          )}
          <Popconfirm title={t('announcement.deleteConfirm')} onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // ---- Status filter tabs ----
  const statusTabs = [
    { key: 'all', label: t('announcement.allStatus') },
    { key: '0', label: t('announcement.statusDraft') },
    { key: '1', label: t('announcement.statusPublished') },
    { key: '2', label: t('announcement.statusArchived') },
  ];

  const handleStatusTabChange = (key: string) => {
    setPage(0);
    if (key === 'all') {
      setFilterStatus(undefined);
    } else {
      setFilterStatus(Number(key));
    }
  };

  return (
    <div>
      <Card
        title={
          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <NotificationOutlined style={{ fontSize: 16, color: COLORS.sage }} />
            <span>{t('announcement.title')}</span>
            <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
              {t('announcement.totalCount', { count: total })}
            </Typography.Text>
          </span>
        }
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            {t('announcement.createBtn')}
          </Button>
        }
      >
        {/* Filter bar */}
        <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap', alignItems: 'center' }}>
          <Space>
            {statusTabs.map(tab => (
              <Button
                key={tab.key}
                size="small"
                type={(tab.key === 'all' && filterStatus === undefined) || tab.key === String(filterStatus) ? 'primary' : 'default'}
                onClick={() => handleStatusTabChange(tab.key)}
              >
                {tab.label}
              </Button>
            ))}
          </Space>
          <Select
            allowClear
            placeholder={t('announcement.filterType')}
            style={{ width: 120 }}
            size="small"
            value={filterType}
            onChange={(v) => { setFilterType(v); setPage(0); }}
            options={[
              { value: 0, label: t('announcement.typeNotice') },
              { value: 1, label: t('announcement.typeAnnounce') },
            ]}
          />
          <Input
            allowClear
            placeholder={t('announcement.searchPlaceholder')}
            size="small"
            style={{ width: 200 }}
            prefix={<SearchOutlined style={{ color: COLORS.textMuted }} />}
            value={keyword}
            onChange={(e) => { setKeyword(e.target.value); setPage(0); }}
            onPressEnter={fetchData}
          />
        </div>

        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          size="small"
          loading={loading}
          locale={{ emptyText: <Empty description={t('announcement.noData')} /> }}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            pageSizeOptions: ['20', '50', '100'],
            showTotal: (tot) => t('common.paginationTotal', { total: tot }),
            onChange: (p, s) => { setPage(p - 1); setPageSize(s); },
          }}
        />
      </Card>

      {/* Create / Edit Modal */}
      <Modal
        title={isCreate ? t('announcement.createTitle') : t('announcement.editTitle')}
        open={editModalOpen}
        onOk={handleSubmit}
        onCancel={() => { setEditModalOpen(false); form.resetFields(); }}
        okText={isCreate ? t('announcement.createBtn') : t('announcement.saveBtn')}
        width={680}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="title" label={t('announcement.formTitle')} rules={[{ required: true, message: t('announcement.formTitleRequired') }]}>
            <Input maxLength={200} placeholder={t('announcement.formTitlePlaceholder')} />
          </Form.Item>
          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item name="type" label={t('announcement.formType')} style={{ flex: 1 }}>
              <Select options={[
                { value: 0, label: t('announcement.typeNotice') },
                { value: 1, label: t('announcement.typeAnnounce') },
              ]} />
            </Form.Item>
            <Form.Item name="priority" label={t('announcement.formPriority')} style={{ flex: 1 }}>
              <Select options={[
                { value: 0, label: t('announcement.priorityNormal') },
                { value: 1, label: t('announcement.priorityImportant') },
                { value: 2, label: t('announcement.priorityUrgent') },
              ]} />
            </Form.Item>
            <Form.Item name="pinned" label={t('announcement.formPinned')} style={{ flex: 1 }}>
              <Select options={[
                { value: 0, label: t('announcement.pinnedNo') },
                { value: 1, label: t('announcement.pinnedYes') },
              ]} />
            </Form.Item>
          </div>
          <Form.Item name="content" label={t('announcement.formContent')} rules={[{ required: true, message: t('announcement.formContentRequired') }]}>
            <TextArea rows={8} placeholder={t('announcement.formContentPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Detail Modal */}
      <Modal
        title={
          <Space>
            <FileTextOutlined style={{ color: COLORS.sage }} />
            <span>{viewingItem?.title}</span>
            {viewingItem && (
              <>
                <Tag color={TYPE_MAP[viewingItem.type]?.color} style={{ fontSize: 11 }}>{TYPE_MAP[viewingItem.type]?.label}</Tag>
                <Tag color={PRIORITY_MAP[viewingItem.priority]?.color} style={{ fontSize: 11 }}>{PRIORITY_MAP[viewingItem.priority]?.label}</Tag>
                {viewingItem.pinned === 1 && <Tag color={COLORS.pending} style={{ fontSize: 11 }}><PushpinOutlined /> {t('announcement.pinnedYes')}</Tag>}
              </>
            )}
          </Space>
        }
        open={detailModalOpen}
        onCancel={() => setDetailModalOpen(false)}
        footer={null}
        width={720}
      >
        {viewingItem && (
          <div style={{ padding: '8px 0' }}>
            <div style={{ display: 'flex', gap: 24, marginBottom: 12, color: COLORS.textMuted, fontSize: 13 }}>
              <span>{t('announcement.colAuthor')}: {viewingItem.author_name || '-'}</span>
              <span>{t('announcement.colStatus')}: {STATUS_MAP[viewingItem.status]?.label}</span>
              <span>{t('announcement.colCreatedAt')}: {dayjs(viewingItem.created_at).format('YYYY-MM-DD HH:mm')}</span>
              {viewingItem.published_at && (
                <span>{t('announcement.colPublishedAt')}: {dayjs(viewingItem.published_at).format('YYYY-MM-DD HH:mm')}</span>
              )}
            </div>
            <div style={{ borderTop: `1px solid ${COLORS.border}`, paddingTop: 16, lineHeight: 1.8, whiteSpace: 'pre-wrap', color: COLORS.textDark }}>
              <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{viewingItem.content}</Paragraph>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
