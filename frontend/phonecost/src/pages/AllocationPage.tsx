import { useState, useEffect, useMemo, useCallback } from 'react';
import { Card, Table, Tag, Button, Space, Modal, Input, Select, message, Descriptions, Tabs, Form, TreeSelect, Row, Col } from 'antd';
import { CheckOutlined, UndoOutlined, DownloadOutlined, SwapOutlined, HistoryOutlined, FileTextOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BillBatch } from '../types/bill';
import type { AllocationResult, AllocationAdjustment } from '../types/allocation';
import { CONFIRM_STATUS_MAP } from '../types/allocation';
import type { Organization } from '../types/organization';
import type { TreeNode } from '../types/api';
import { getErrorMessage } from '../types/api';
import {
  getBillBatches,
  getAllocationResults,
  confirmAllocation,
  confirmAllAllocation,
  withdrawAllocation,
  getExportSummaryUrl,
  getExportDetailUrl,
  getBranchBillUrl,
  adjustAllocation,
  getAdjustments,
} from '../api/allocation';
import { getOrgTree } from '../api/org';
import { useAuthStore } from '../store/auth';
import { exportCSV } from '../lib/export';
import dayjs from 'dayjs';

/** Build Ant Design TreeSelect data from flat org list */
function buildTreeData(orgs: Organization[]): TreeNode[] {
  const map = new Map<number, TreeNode>();
  const roots: TreeNode[] = [];
  for (const org of orgs) {
    map.set(org.id, { value: org.id, title: org.name, children: [] });
  }
  for (const org of orgs) {
    const node = map.get(org.id)!;
    if (org.parent_id && map.has(org.parent_id)) {
      map.get(org.parent_id)!.children!.push(node);
    } else {
      roots.push(node);
    }
  }
  // Remove empty children arrays to avoid leaf arrow
  function clean(nodes: TreeNode[]) {
    for (const n of nodes) {
      if (n.children && n.children.length === 0) delete n.children;
      else if (n.children) clean(n.children);
    }
  }
  clean(roots);
  return roots;
}

export default function AllocationPage() {
  const { t } = useTranslation();
  const role = useAuthStore((s) => s.role);
  const isAdminOrFinance = role === 1 || role === 4;

  const [batches, setBatches] = useState<BillBatch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState<number | null>(null);
  const [results, setResults] = useState<AllocationResult[]>([]);
  const [adjustments, setAdjustments] = useState<AllocationAdjustment[]>([]);
  const [loading, setLoading] = useState(false);
  const [resultsLoading, setResultsLoading] = useState(false);
  const [adjustmentsLoading, setAdjustmentsLoading] = useState(false);
  const [withdrawModal, setWithdrawModal] = useState<{ open: boolean; result?: AllocationResult }>({ open: false });
  const [withdrawReason, setWithdrawReason] = useState('');

  // Adjust modal state
  const [adjustModalOpen, setAdjustModalOpen] = useState(false);
  const [adjustSubmitting, setAdjustSubmitting] = useState(false);
  const [adjustForm] = Form.useForm();
  const [orgList, setOrgList] = useState<Organization[]>([]);
  const [activeTab, setActiveTab] = useState('results');

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getBillBatches();
      setBatches(data);
    } catch {
      message.error(t('allocation.fetchBatchesFailed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  const fetchResults = useCallback(async (batchId: number) => {
    setResultsLoading(true);
    try {
      const data = await getAllocationResults(batchId);
      setResults(data);
    } catch {
      message.error(t('allocation.fetchResultsFailed'));
    } finally {
      setResultsLoading(false);
    }
  }, [t]);

  const fetchAdjustments = useCallback(async (batchId: number) => {
    setAdjustmentsLoading(true);
    try {
      const data = await getAdjustments(batchId);
      setAdjustments(data);
    } catch {
      message.error(t('allocation.fetchAdjustmentsFailed'));
    } finally {
      setAdjustmentsLoading(false);
    }
  }, [t]);

  const fetchOrgTree = useCallback(async () => {
    try {
      const data = await getOrgTree();
      setOrgList(data);
    } catch {
      // silently fail, org tree is optional for adjust
    }
  }, []);

  useEffect(() => { fetchBatches(); fetchOrgTree(); }, [fetchBatches, fetchOrgTree]);

  useEffect(() => {
    if (selectedBatchId) {
      fetchResults(selectedBatchId);
      if (activeTab === 'adjustments') fetchAdjustments(selectedBatchId);
    }
  }, [selectedBatchId, fetchResults, fetchAdjustments, activeTab]);

  const treeData = useMemo(() => buildTreeData(orgList), [orgList]);

  const handleConfirm = async (batchId: number, orgId: number) => {
    try {
      await confirmAllocation(batchId, orgId);
      message.success(t('allocation.confirmSuccessMsg'));
      fetchResults(batchId);
    } catch (err) {
      message.error(getErrorMessage(err, t('allocation.confirmFailedMsg')));
    }
  };

  const handleConfirmAll = async () => {
    if (!selectedBatchId) return;
    try {
      const res = await confirmAllAllocation(selectedBatchId);
      message.success(t('allocation.confirmAllSuccessMsg', { count: res.confirmed_count }));
      fetchResults(selectedBatchId);
    } catch (err) {
      message.error(getErrorMessage(err, t('allocation.confirmAllFailedMsg')));
    }
  };

  const handleWithdraw = async () => {
    if (!withdrawModal.result || !withdrawReason.trim()) {
      message.warning(t('allocation.withdrawReasonRequiredMsg'));
      return;
    }
    try {
      await withdrawAllocation(withdrawModal.result.batch_id, withdrawModal.result.org_id, withdrawReason);
      message.success(t('allocation.withdrawSuccessMsg'));
      setWithdrawModal({ open: false });
      setWithdrawReason('');
      fetchResults(withdrawModal.result.batch_id);
    } catch (err) {
      message.error(getErrorMessage(err, t('allocation.withdrawFailedMsg')));
    }
  };

  const handleExport = (url: string) => {
    window.open(url, '_blank', 'noopener,noreferrer');
  };

  const handleAdjust = async () => {
    try {
      const values = await adjustForm.validateFields();
      setAdjustSubmitting(true);
      await adjustAllocation(
        selectedBatchId!,
        values.phone_number,
        values.from_org_id,
        values.to_org_id,
        values.reason,
      );
      message.success(t('allocation.adjustSuccess'));
      setAdjustModalOpen(false);
      adjustForm.resetFields();
      fetchResults(selectedBatchId!);
      if (activeTab === 'adjustments') fetchAdjustments(selectedBatchId!);
    } catch (err) {
      if (typeof err === 'object' && err !== null && 'errorFields' in err) return; // form validation error
      message.error(getErrorMessage(err, t('allocation.adjustFailed')));
    } finally {
      setAdjustSubmitting(false);
    }
  };

  const openAdjustModal = () => {
    adjustForm.resetFields();
    setAdjustModalOpen(true);
  };

  // ========== 报销单数据 ==========
  const reimbursementData = useMemo(() => {
    const orgCodeMap = new Map<number, string>();
    orgList.forEach(o => { if (o.code) orgCodeMap.set(o.id, o.code); });
    const m = new Map<string, number>();
    results.forEach(r => {
      if (r.org_id === -1) return;
      const code = orgCodeMap.get(r.org_id);
      if (!code) return;
      m.set(code, (m.get(code) || 0) + (r.total_fee || 0));
    });
    return [...m.entries()]
      .map(([code, total], i) => ({ key: i, cost_center: code, fee_subtotal: total }))
      .sort((a, b) => a.cost_center.localeCompare(b.cost_center));
  }, [results, orgList]);

  const reimbursementTotal = reimbursementData.reduce((s, r) => s + r.fee_subtotal, 0);

  const reimbursementColumns = [
    { title: t('allocation.reimbursementCostCenter'), dataIndex: 'cost_center', key: 'cost_center', width: 200 },
    {
      title: t('allocation.reimbursementFeeSubtotal'), dataIndex: 'fee_subtotal', key: 'fee_subtotal', width: 150, align: 'right' as const,
      render: (v: number) => <strong>¥{v.toFixed(2)}</strong>,
    },
  ];

  const resultColumns = [
    {
      title: t('allocation.orgName'), dataIndex: 'org_name', key: 'org_name', width: 180,
      render: (name: string, r: AllocationResult) =>
        r.org_id === -1 ? <Tag color="red">{t('bill.unassigned')}</Tag> : name,
    },
    {
      title: t('allocation.monthlyRentFee'), dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('allocation.callFeeCol'), dataIndex: 'call_fee', key: 'call_fee', width: 100,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('allocation.recordingFeeCol'), dataIndex: 'recording_fee', key: 'recording_fee', width: 100,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('allocation.crbtFeeCol'), dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('allocation.flashMsgFeeCol'), dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100,
      render: (v: number) => v != null && v !== 0 ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: t('allocation.totalFeeCol'), dataIndex: 'total_fee', key: 'total_fee', width: 110,
      render: (v: number) => <strong>{v != null ? `¥${v.toFixed(2)}` : '-'}</strong>,
    },
    {
      title: t('allocation.phoneCountCol'), dataIndex: 'phone_count', key: 'phone_count', width: 70,
    },
    {
      title: t('allocation.confirmStatusCol'), dataIndex: 'confirm_status', key: 'confirm_status', width: 90,
      render: (s: number) => {
        const info = CONFIRM_STATUS_MAP[s] || { label: t('allocation.unknown'), color: 'default' };
        return <Tag color={info.color}>{info.label}</Tag>;
      },
    },
    {
      title: t('common.actions'), key: 'actions', width: 140,
      render: (_unused: unknown, record: AllocationResult) => (
        <Space size="small">
          {record.confirm_status === 0 && (
            <Button size="small" type="primary" icon={<CheckOutlined />}
              onClick={() => handleConfirm(record.batch_id, record.org_id)}>
              {t('bill.confirmBtn')}
            </Button>
          )}
          {record.confirm_status === 1 && (
            <Button size="small" danger icon={<UndoOutlined />}
              onClick={() => setWithdrawModal({ open: true, result: record })}>
              {t('bill.withdrawBtn')}
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const adjustmentColumns = [
    {
      title: t('allocation.phoneNum'), dataIndex: 'phone_number', key: 'phone_number', width: 120,
    },
    {
      title: t('allocation.fromOrg'), dataIndex: 'from_org_name', key: 'from_org_name', width: 160,
    },
    {
      title: t('allocation.toOrg'), dataIndex: 'to_org_name', key: 'to_org_name', width: 160,
    },
    {
      title: t('allocation.adjustAmount'), dataIndex: 'amount', key: 'amount', width: 110,
      render: (v: number) => <strong>{v != null ? `¥${v.toFixed(2)}` : '-'}</strong>,
    },
    {
      title: t('allocation.adjustReason'), dataIndex: 'reason', key: 'reason', width: 200,
      ellipsis: true,
    },
    {
      title: t('allocation.adjustTime'), dataIndex: 'created_at', key: 'created_at', width: 160,
      render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-',
    },
  ];

  const totalFee = results.reduce((sum, r) => sum + (r.total_fee || 0), 0);
  const confirmedCount = results.filter(r => r.confirm_status === 1).length;
  const pendingCount = results.filter(r => r.confirm_status === 0).length;

  return (
    <div>
      <Card>
        <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
          <Col>
            <Space>
              <span>{t('allocation.selectBatch')}</span>
              <Select
                style={{ width: 300 }}
                placeholder={t('allocation.selectBatchPlaceholder')}
                loading={loading}
                value={selectedBatchId}
                onChange={setSelectedBatchId}
                options={batches.map(b => ({ label: `${b.batch_no} (${b.billing_month})`, value: b.id }))}
              />
            </Space>
          </Col>
          <Col>
            <Space>
              {selectedBatchId && results.length > 0 && (
                <>
                  <Button type="primary" icon={<CheckOutlined />} onClick={handleConfirmAll}>
                    {t('allocation.batchConfirmAll')}
                  </Button>
                  {isAdminOrFinance && (
                    <Button icon={<SwapOutlined />} onClick={openAdjustModal}>
                      {t('allocation.feeAdjustment')}
                    </Button>
                  )}
                  <Button type="primary" icon={<DownloadOutlined />}
                    onClick={() => handleExport(getBranchBillUrl(selectedBatchId))}>
                    {t('allocation.branchBill')}
                  </Button>
                </>
              )}
            </Space>
          </Col>
        </Row>

        {selectedBatchId && (
          <Tabs type="card" activeKey={activeTab} onChange={setActiveTab} items={[
            {
              key: 'results',
              label: t('allocation.resultsTab'),
              children: (
                <>
                  <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
                    <Descriptions.Item label={t('allocation.statsOrgs')}>{results.length}</Descriptions.Item>
                    <Descriptions.Item label={t('allocation.statsTotalFee')}>¥{totalFee.toFixed(2)}</Descriptions.Item>
                    <Descriptions.Item label={t('allocation.statsConfirmed')}>{confirmedCount}</Descriptions.Item>
                    <Descriptions.Item label={t('allocation.statsPending')}>{pendingCount}</Descriptions.Item>
                  </Descriptions>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                    <Space>
                      <Button icon={<DownloadOutlined />}
                        onClick={() => handleExport(getExportSummaryUrl(selectedBatchId))}>
                        {t('allocation.exportSummary')}
                      </Button>
                      <Button icon={<DownloadOutlined />}
                        onClick={() => handleExport(getExportDetailUrl(selectedBatchId))}>
                        {t('allocation.exportDetail')}
                      </Button>
                    </Space>
                    <Button icon={<DownloadOutlined />} onClick={() => {
                      const batch = batches.find(b => b.id === selectedBatchId);
                      exportCSV(
                        `分摊结果_${batch?.billing_month || ''}`,
                        [
                          { title: t('allocation.orgName'), dataIndex: 'org_name' },
                          { title: t('allocation.monthlyRentFee'), dataIndex: 'monthly_rent', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                          { title: t('allocation.callFeeCol'), dataIndex: 'call_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                          { title: t('allocation.recordingFeeCol'), dataIndex: 'recording_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                          { title: t('allocation.crbtFeeCol'), dataIndex: 'crbt_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                          { title: t('allocation.flashMsgFeeCol'), dataIndex: 'flash_msg_fee', render: (v: number) => v != null && v !== 0 ? v.toFixed(2) : '' },
                          { title: t('allocation.totalFeeCol'), dataIndex: 'total_fee', render: (v: number) => v != null ? v.toFixed(2) : '' },
                          { title: t('allocation.phoneCountCol'), dataIndex: 'phone_count' },
                          { title: t('allocation.confirmStatusCol'), dataIndex: 'confirm_status', render: (v: number) => CONFIRM_STATUS_MAP[v]?.label || '' },
                        ],
                        results as unknown as Record<string, unknown>[],
                      );
                    }}>{t('allocation.exportCurrentTab')}</Button>
                  </div>
                  <Table
                    columns={resultColumns}
                    dataSource={results}
                    rowKey="id"
                    size="small"
                    loading={resultsLoading}
                    pagination={{ pageSize: 20 }}
                  />
                </>
              ),
            },
            {
              key: 'adjustments',
              label: <span><HistoryOutlined /> {t('allocation.adjustmentsTab')}</span>,
              children: (
                <>
                  <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
                    <Button icon={<DownloadOutlined />} onClick={() => {
                      const batch = batches.find(b => b.id === selectedBatchId);
                      exportCSV(
                        `调整记录_${batch?.billing_month || ''}`,
                        [
                          { title: t('allocation.phoneNum'), dataIndex: 'phone_number' },
                          { title: t('allocation.fromOrg'), dataIndex: 'from_org_name' },
                          { title: t('allocation.toOrg'), dataIndex: 'to_org_name' },
                          { title: t('allocation.adjustAmount'), dataIndex: 'amount', render: (v: number) => v != null ? v.toFixed(2) : '' },
                          { title: t('allocation.adjustReason'), dataIndex: 'reason' },
                          { title: t('allocation.adjustTime'), dataIndex: 'created_at', render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '' },
                        ],
                        adjustments as unknown as Record<string, unknown>[],
                      );
                    }}>{t('allocation.exportCurrentTab')}</Button>
                  </div>
                  <Table
                    columns={adjustmentColumns}
                    dataSource={adjustments}
                    rowKey="id"
                    size="small"
                    loading={adjustmentsLoading}
                    pagination={{ pageSize: 20 }}
                    locale={{ emptyText: t('allocation.noAdjustments') }}
                  />
                </>
              ),
            },
            {
              key: 'reimbursement',
              label: <span><FileTextOutlined /> {t('allocation.reimbursementTab')}</span>,
              children: (
                <>
                  <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
                    <Button icon={<DownloadOutlined />} onClick={() => {
                      const batch = batches.find(b => b.id === selectedBatchId);
                      const data = [...reimbursementData, { key: reimbursementData.length, cost_center: t('allocation.reimbursementTotal'), fee_subtotal: reimbursementTotal }];
                      exportCSV(
                        `报销单_${batch?.billing_month || ''}`,
                        [
                          { title: t('allocation.reimbursementCostCenter'), dataIndex: 'cost_center' },
                          { title: t('allocation.reimbursementFeeSubtotal'), dataIndex: 'fee_subtotal', render: (v: number) => v.toFixed(2) },
                        ],
                        data,
                      );
                    }}>{t('allocation.exportCurrentTab')}</Button>
                  </div>
                  <Table
                    columns={reimbursementColumns}
                    dataSource={reimbursementData}
                    rowKey="key"
                    size="small"
                    pagination={false}
                    summary={() => (
                      <Table.Summary.Row>
                        <Table.Summary.Cell index={0}><strong>{t('allocation.reimbursementTotal')}</strong></Table.Summary.Cell>
                        <Table.Summary.Cell index={1} align="right"><strong>¥{reimbursementTotal.toFixed(2)}</strong></Table.Summary.Cell>
                      </Table.Summary.Row>
                    )}
                  />
                </>
              ),
            },
          ]} />
        )}
      </Card>

      {/* 撤回弹窗 */}
      <Modal
        title={t('allocation.withdrawTitle')}
        open={withdrawModal.open}
        onOk={handleWithdraw}
        onCancel={() => { setWithdrawModal({ open: false }); setWithdrawReason(''); }}
        okText={t('allocation.withdrawOkText')}
        okButtonProps={{ danger: true }}
      >
        <p>{t('allocation.withdrawDesc')}</p>
        <Input.TextArea
          rows={3}
          placeholder={t('allocation.withdrawReasonPlaceholder')}
          value={withdrawReason}
          onChange={(e) => setWithdrawReason(e.target.value)}
        />
      </Modal>

      {/* 费用调整弹窗 */}
      <Modal
        title={t('allocation.adjustTitle')}
        open={adjustModalOpen}
        onOk={handleAdjust}
        onCancel={() => { setAdjustModalOpen(false); adjustForm.resetFields(); }}
        okText={t('allocation.adjustOkText')}
        confirmLoading={adjustSubmitting}
        width={520}
      >
        <Form form={adjustForm} layout="vertical">
          <Form.Item
            name="phone_number"
            label={t('allocation.phoneNumberLabel')}
            rules={[{ required: true, message: t('allocation.phoneNumberRequired') }]}
          >
            <Input placeholder={t('allocation.phoneNumberPlaceholder')} />
          </Form.Item>
          <Form.Item
            name="from_org_id"
            label={t('allocation.fromOrgLabel')}
            rules={[{ required: true, message: t('allocation.fromOrgRequired') }]}
          >
            <TreeSelect
              treeData={treeData}
              placeholder={t('allocation.fromOrgPlaceholder')}
              showSearch
              treeNodeFilterProp="title"
              style={{ width: '100%' }}
              allowClear
            />
          </Form.Item>
          <Form.Item
            name="to_org_id"
            label={t('allocation.toOrgLabel')}
            rules={[{ required: true, message: t('allocation.toOrgRequired') }]}
          >
            <TreeSelect
              treeData={treeData}
              placeholder={t('allocation.toOrgPlaceholder')}
              showSearch
              treeNodeFilterProp="title"
              style={{ width: '100%' }}
              allowClear
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label={t('allocation.reasonLabel')}
            rules={[{ required: true, message: t('allocation.reasonRequired') }]}
          >
            <Input.TextArea rows={3} placeholder={t('allocation.reasonPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
