import { useState, useEffect, useMemo } from 'react';
import { Card, Table, Tag, Button, Space, Modal, Input, Select, message, Descriptions, Tabs, Form, TreeSelect } from 'antd';
import { CheckOutlined, UndoOutlined, DownloadOutlined, SwapOutlined, HistoryOutlined } from '@ant-design/icons';
import type { BillBatch } from '../types/bill';
import type { AllocationResult, AllocationAdjustment } from '../types/allocation';
import { CONFIRM_STATUS_MAP } from '../types/allocation';
import type { Organization } from '../types/organization';
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

/** Build Ant Design TreeSelect data from flat org list */
function buildTreeData(orgs: Organization[]) {
  const map = new Map<number, { value: number; title: string; children: any[] }>();
  const roots: any[] = [];
  for (const org of orgs) {
    map.set(org.id, { value: org.id, title: org.name, children: [] });
  }
  for (const org of orgs) {
    const node = map.get(org.id)!;
    if (org.parent_id && map.has(org.parent_id)) {
      map.get(org.parent_id)!.children.push(node);
    } else {
      roots.push(node);
    }
  }
  // Remove empty children arrays to avoid leaf arrow
  function clean(nodes: any[]) {
    for (const n of nodes) {
      if (n.children.length === 0) delete n.children;
      else clean(n.children);
    }
  }
  clean(roots);
  return roots;
}

export default function AllocationPage() {
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

  const fetchBatches = async () => {
    setLoading(true);
    try {
      const data = await getBillBatches();
      setBatches(data);
    } catch {
      message.error('获取账单列表失败');
    } finally {
      setLoading(false);
    }
  };

  const fetchResults = async (batchId: number) => {
    setResultsLoading(true);
    try {
      const data = await getAllocationResults(batchId);
      setResults(data);
    } catch {
      message.error('获取分摊结果失败');
    } finally {
      setResultsLoading(false);
    }
  };

  const fetchAdjustments = async (batchId: number) => {
    setAdjustmentsLoading(true);
    try {
      const data = await getAdjustments(batchId);
      setAdjustments(data);
    } catch {
      message.error('获取调整记录失败');
    } finally {
      setAdjustmentsLoading(false);
    }
  };

  const fetchOrgTree = async () => {
    try {
      const data = await getOrgTree();
      setOrgList(data);
    } catch {
      // silently fail, org tree is optional for adjust
    }
  };

  useEffect(() => { fetchBatches(); fetchOrgTree(); }, []);

  useEffect(() => {
    if (selectedBatchId) {
      fetchResults(selectedBatchId);
      if (activeTab === 'adjustments') fetchAdjustments(selectedBatchId);
    }
  }, [selectedBatchId]);

  useEffect(() => {
    if (selectedBatchId && activeTab === 'adjustments') {
      fetchAdjustments(selectedBatchId);
    }
  }, [activeTab]);

  const treeData = useMemo(() => buildTreeData(orgList), [orgList]);

  const handleConfirm = async (batchId: number, orgId: number) => {
    try {
      await confirmAllocation(batchId, orgId);
      message.success('确认成功');
      fetchResults(batchId);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '确认失败');
    }
  };

  const handleConfirmAll = async () => {
    if (!selectedBatchId) return;
    try {
      const res = await confirmAllAllocation(selectedBatchId);
      message.success(`批量确认完成：${res.confirmed_count} 条`);
      fetchResults(selectedBatchId);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '批量确认失败');
    }
  };

  const handleWithdraw = async () => {
    if (!withdrawModal.result || !withdrawReason.trim()) {
      message.warning('请输入撤回原因');
      return;
    }
    try {
      await withdrawAllocation(withdrawModal.result.batch_id, withdrawModal.result.org_id, withdrawReason);
      message.success('撤回成功');
      setWithdrawModal({ open: false });
      setWithdrawReason('');
      fetchResults(withdrawModal.result.batch_id);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '撤回失败');
    }
  };

  const handleExport = (url: string) => {
    window.open(url, '_blank');
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
      message.success('费用调整成功');
      setAdjustModalOpen(false);
      adjustForm.resetFields();
      fetchResults(selectedBatchId!);
      if (activeTab === 'adjustments') fetchAdjustments(selectedBatchId!);
    } catch (err: any) {
      if (err?.errorFields) return; // form validation error
      message.error(err?.response?.data?.message || '调整失败');
    } finally {
      setAdjustSubmitting(false);
    }
  };

  const openAdjustModal = () => {
    adjustForm.resetFields();
    setAdjustModalOpen(true);
  };

  const resultColumns = [
    {
      title: '组织名称', dataIndex: 'org_name', key: 'org_name', width: 180,
      render: (name: string, r: AllocationResult) =>
        r.org_id === -1 ? <Tag color="red">未归属</Tag> : name,
    },
    {
      title: '月租费', dataIndex: 'monthly_rent', key: 'monthly_rent', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '通话费', dataIndex: 'call_fee', key: 'call_fee', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '录音费', dataIndex: 'recording_fee', key: 'recording_fee', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '彩铃费', dataIndex: 'crbt_fee', key: 'crbt_fee', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '闪信费', dataIndex: 'flash_msg_fee', key: 'flash_msg_fee', width: 100,
      render: (v: number) => v ? `¥${v.toFixed(2)}` : '-',
    },
    {
      title: '合计', dataIndex: 'total_fee', key: 'total_fee', width: 110,
      render: (v: number) => <strong>¥{v?.toFixed(2)}</strong>,
    },
    {
      title: '号码数', dataIndex: 'phone_count', key: 'phone_count', width: 70,
    },
    {
      title: '确认状态', dataIndex: 'confirm_status', key: 'confirm_status', width: 90,
      render: (s: number) => {
        const info = CONFIRM_STATUS_MAP[s] || { label: '未知', color: 'default' };
        return <Tag color={info.color}>{info.label}</Tag>;
      },
    },
    {
      title: '操作', key: 'actions', width: 140,
      render: (_: any, record: AllocationResult) => (
        <Space size="small">
          {record.confirm_status === 0 && (
            <Button size="small" type="primary" icon={<CheckOutlined />}
              onClick={() => handleConfirm(record.batch_id, record.org_id)}>
              确认
            </Button>
          )}
          {record.confirm_status === 1 && (
            <Button size="small" danger icon={<UndoOutlined />}
              onClick={() => setWithdrawModal({ open: true, result: record })}>
              撤回
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const adjustmentColumns = [
    {
      title: '号码', dataIndex: 'phone_number', key: 'phone_number', width: 120,
    },
    {
      title: '原组织', dataIndex: 'from_org_name', key: 'from_org_name', width: 160,
    },
    {
      title: '目标组织', dataIndex: 'to_org_name', key: 'to_org_name', width: 160,
    },
    {
      title: '调整金额', dataIndex: 'amount', key: 'amount', width: 110,
      render: (v: number) => <strong>¥{v?.toFixed(2)}</strong>,
    },
    {
      title: '原因', dataIndex: 'reason', key: 'reason', width: 200,
      ellipsis: true,
    },
    {
      title: '调整时间', dataIndex: 'created_at', key: 'created_at', width: 160,
    },
  ];

  const totalFee = results.reduce((sum, r) => sum + (r.total_fee || 0), 0);
  const confirmedCount = results.filter(r => r.confirm_status === 1).length;
  const pendingCount = results.filter(r => r.confirm_status === 0).length;

  return (
    <div>
      <Card>
        <Space style={{ marginBottom: 16 }}>
          <span>选择账单批次：</span>
          <Select
            style={{ width: 300 }}
            placeholder="请选择批次"
            loading={loading}
            value={selectedBatchId}
            onChange={setSelectedBatchId}
            options={batches.map(b => ({ label: `${b.batch_no} (${b.billing_month})`, value: b.id }))}
          />
          {selectedBatchId && results.length > 0 && (
            <>
              <Button type="primary" icon={<CheckOutlined />} onClick={handleConfirmAll}>
                批量确认
              </Button>
              {isAdminOrFinance && (
                <Button icon={<SwapOutlined />} onClick={openAdjustModal}>
                  费用调整
                </Button>
              )}
              <Button icon={<DownloadOutlined />}
                onClick={() => handleExport(getExportSummaryUrl(selectedBatchId))}>
                导出汇总
              </Button>
              <Button icon={<DownloadOutlined />}
                onClick={() => handleExport(getExportDetailUrl(selectedBatchId))}>
                导出明细
              </Button>
              <Button type="primary" icon={<DownloadOutlined />}
                onClick={() => handleExport(getBranchBillUrl(selectedBatchId))}>
                分行账单
              </Button>
            </>
          )}
        </Space>

        {selectedBatchId && (
          <>
            <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="组织数">{results.length}</Descriptions.Item>
              <Descriptions.Item label="总费用">¥{totalFee.toFixed(2)}</Descriptions.Item>
              <Descriptions.Item label="已确认">{confirmedCount}</Descriptions.Item>
              <Descriptions.Item label="待确认">{pendingCount}</Descriptions.Item>
            </Descriptions>

            <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
              {
                key: 'results',
                label: '分摊结果',
                children: (
                  <Table
                    columns={resultColumns}
                    dataSource={results}
                    rowKey="id"
                    size="small"
                    loading={resultsLoading}
                    pagination={{ pageSize: 20 }}
                  />
                ),
              },
              {
                key: 'adjustments',
                label: <span><HistoryOutlined /> 调整记录</span>,
                children: (
                  <Table
                    columns={adjustmentColumns}
                    dataSource={adjustments}
                    rowKey="id"
                    size="small"
                    loading={adjustmentsLoading}
                    pagination={{ pageSize: 20 }}
                    locale={{ emptyText: '暂无调整记录' }}
                  />
                ),
              },
            ]} />
          </>
        )}
      </Card>

      {/* 撤回弹窗 */}
      <Modal
        title="撤回确认"
        open={withdrawModal.open}
        onOk={handleWithdraw}
        onCancel={() => { setWithdrawModal({ open: false }); setWithdrawReason(''); }}
        okText="确认撤回"
        okButtonProps={{ danger: true }}
      >
        <p>撤回后该组织的分摊结果将变为"已撤回"状态，如需重新确认请再次操作。</p>
        <Input.TextArea
          rows={3}
          placeholder="请输入撤回原因（必填）"
          value={withdrawReason}
          onChange={(e) => setWithdrawReason(e.target.value)}
        />
      </Modal>

      {/* 费用调整弹窗 */}
      <Modal
        title="费用调整"
        open={adjustModalOpen}
        onOk={handleAdjust}
        onCancel={() => { setAdjustModalOpen(false); adjustForm.resetFields(); }}
        okText="确认调整"
        confirmLoading={adjustSubmitting}
        width={520}
      >
        <Form form={adjustForm} layout="vertical">
          <Form.Item
            name="phone_number"
            label="号码"
            rules={[{ required: true, message: '请输入要调整的号码' }]}
          >
            <Input placeholder="输入外线号码，如 01088881234" />
          </Form.Item>
          <Form.Item
            name="from_org_id"
            label="原组织"
            rules={[{ required: true, message: '请选择原组织' }]}
          >
            <TreeSelect
              treeData={treeData}
              placeholder="选择号码当前归属的组织"
              showSearch
              treeNodeFilterProp="title"
              style={{ width: '100%' }}
              allowClear
            />
          </Form.Item>
          <Form.Item
            name="to_org_id"
            label="目标组织"
            rules={[{ required: true, message: '请选择目标组织' }]}
          >
            <TreeSelect
              treeData={treeData}
              placeholder="选择将号码调整到的目标组织"
              showSearch
              treeNodeFilterProp="title"
              style={{ width: '100%' }}
              allowClear
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label="调整原因"
            rules={[{ required: true, message: '请输入调整原因' }]}
          >
            <Input.TextArea rows={3} placeholder="说明调整原因，如：该号码已调拨至XX支行" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
