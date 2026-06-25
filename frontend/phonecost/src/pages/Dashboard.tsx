import { useQuery } from '@tanstack/react-query';
import { Card, Table, Tag, Typography, Empty, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { getDashboardStats } from '../api/dashboard';
import type { BranchSummaryItem, FeeBreakdownItem } from '../api/dashboard';
import { COLORS } from '../theme/morandi';

const { Title } = Typography;

const formatMoney = (v: number) => {
  if (!v || v === 0) return '¥0';
  if (v >= 10000) return `¥${(v / 10000).toFixed(2)}万`;
  return `¥${v.toFixed(2)}`;
};

const formatNumber = (v: number) => v.toLocaleString();

// ============ Donut Chart (CSS conic-gradient) ============
function DonutChart({ data, size = 120 }: { data: FeeBreakdownItem[]; size?: number }) {
  const total = data.reduce((s, d) => s + (d.value || 0), 0);
  if (total === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;

  let cumulative = 0;
  const segments = data.map((d) => {
    const pct = ((d.value || 0) / total) * 100;
    const start = cumulative;
    cumulative += pct;
    return { color: d.color, start, end: cumulative, name: d.name, pct, value: d.value };
  });

  const gradient = segments
    .map((s) => `${s.color} ${s.start}% ${s.end}%`)
    .join(', ');

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
      <div
        style={{
          width: size,
          height: size,
          borderRadius: '50%',
          background: `conic-gradient(${gradient})`,
          position: 'relative',
          flexShrink: 0,
        }}
      >
        <div
          style={{
            position: 'absolute',
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            width: size * 0.6,
            height: size * 0.6,
            borderRadius: '50%',
            background: COLORS.white,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <span style={{ fontSize: 11, color: COLORS.textMuted }}>合计</span>
          <span style={{ fontSize: 14, fontWeight: 600, color: COLORS.textDark }}>
            {formatMoney(total)}
          </span>
        </div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {segments.map((s) => (
          <div key={s.name} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
            <div style={{ width: 8, height: 8, borderRadius: 2, background: s.color, flexShrink: 0 }} />
            <span style={{ color: COLORS.textMuted }}>{s.name}</span>
            <span style={{ fontWeight: 500, color: COLORS.textDark }}>{s.pct.toFixed(1)}%</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ============ Mini Bar Chart (for monthly trend) ============
function MonthlyBarChart({ data }: { data: { month: string; amount: number; count: number }[] }) {
  if (!data || data.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  const maxAmount = Math.max(...data.map((d) => d.amount || 0), 1);

  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-around', gap: 12, height: 200, padding: '0 8px' }}>
      {data.map((d) => {
        const heightPct = ((d.amount || 0) / maxAmount) * 100;
        return (
          <div key={d.month} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, flex: 1 }}>
            <span style={{ fontSize: 12, fontWeight: 500, color: COLORS.textDark }}>
              {formatMoney(d.amount)}
            </span>
            <div
              style={{
                width: '60%',
                maxWidth: 48,
                minWidth: 24,
                height: `${heightPct}%`,
                minHeight: 4,
                borderRadius: '6px 6px 0 0',
                background: `linear-gradient(180deg, ${COLORS.sage} 0%, ${COLORS.slate} 100%)`,
                transition: 'height 0.4s ease',
              }}
            />
            <span style={{ fontSize: 12, color: COLORS.textMuted }}>{d.month}</span>
            <span style={{ fontSize: 11, color: COLORS.textMuted }}>{d.count}条</span>
          </div>
        );
      })}
    </div>
  );
}

// ============ Stat Card (Morandi style) ============
function StatCard({
  title,
  value,
  subtitle,
  bgColor,
  textColor,
  children,
}: {
  title: string;
  value: string;
  subtitle?: string;
  bgColor: string;
  textColor: string;
  children?: React.ReactNode;
}) {
  return (
    <div
      style={{
        background: bgColor,
        borderRadius: 16,
        padding: '20px 24px',
        color: textColor,
        minHeight: 180,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
      }}
    >
      <div>
        <div style={{ fontSize: 13, opacity: 0.75, marginBottom: 8 }}>{title}</div>
        <div style={{ fontSize: 28, fontWeight: 700, lineHeight: 1.2 }}>{value}</div>
        {subtitle && <div style={{ fontSize: 12, opacity: 0.6, marginTop: 6 }}>{subtitle}</div>}
      </div>
      {children && <div style={{ marginTop: 12 }}>{children}</div>}
    </div>
  );
}

// ============ Progress Bar ============
function ProgressBar({ confirmed, pending, total }: { confirmed: number; pending: number; total: number }) {
  const confirmedPct = total > 0 ? (confirmed / total) * 100 : 0;
  const pendingPct = total > 0 ? (pending / total) * 100 : 0;

  return (
    <div>
      <div style={{ display: 'flex', height: 8, borderRadius: 4, overflow: 'hidden', background: 'rgba(255,255,255,0.2)' }}>
        <div style={{ width: `${confirmedPct}%`, background: COLORS.confirmed, transition: 'width 0.4s ease' }} />
        <div style={{ width: `${pendingPct}%`, background: COLORS.pending, transition: 'width 0.4s ease' }} />
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontSize: 12 }}>
        <span style={{ opacity: 0.8 }}>
          ● {COLORS.confirmed && '已确认'} {confirmed}
        </span>
        <span style={{ opacity: 0.8 }}>
          ● 待确认 {pending}
        </span>
      </div>
    </div>
  );
}

export default function Dashboard() {
  const { t } = useTranslation();
  const { data: stats, isLoading, isError } = useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: getDashboardStats,
  });

  if (isError) {
    return (
      <Card>
        <Empty description={t('dashboard.fetchFailed')} />
      </Card>
    );
  }

  if (isLoading || !stats) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  const totalAllocation = (stats.confirmed_count || 0) + (stats.pending_count || 0);
  const latestMonth = stats.latest_batch?.month || '-';
  const latestAmount = stats.latest_batch?.amount || 0;

  const branchColumns = [
    {
      title: '排名',
      key: 'rank',
      width: 60,
      render: (_: unknown, __: unknown, i: number) => (
        <span style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 24,
          height: 24,
          borderRadius: '50%',
          background: i < 3 ? [COLORS.sage, COLORS.taupe, COLORS.slate][i] : COLORS.cream,
          color: i < 3 ? COLORS.white : COLORS.textMuted,
          fontSize: 12,
          fontWeight: 600,
        }}>
          {i + 1}
        </span>
      ),
    },
    { title: '分行名称', dataIndex: 'name', key: 'name', width: 140 },
    {
      title: '费用金额',
      dataIndex: 'amount',
      key: 'amount',
      width: 140,
      align: 'right' as const,
      render: (v: number) => <strong>{formatMoney(v)}</strong>,
      sorter: (a: BranchSummaryItem, b: BranchSummaryItem) => a.amount - b.amount,
    },
    {
      title: '号码数',
      dataIndex: 'phone_count',
      key: 'phone_count',
      width: 100,
      align: 'right' as const,
      render: (v: number) => formatNumber(v),
    },
    {
      title: '确认状态',
      dataIndex: 'confirm_status',
      key: 'confirm_status',
      width: 100,
      render: (s: number) =>
        s === 1 ? <Tag color="green">{t('dashboard.confirmed')}</Tag> : <Tag color="orange">{t('dashboard.pending')}</Tag>,
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Page Title */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Title level={4} style={{ margin: 0 }}>{t('dashboard.title')}</Title>
        {stats.latest_batch && (
          <span style={{ color: COLORS.textMuted, fontSize: 13 }}>
            {t('dashboard.latestBatch')}：{latestMonth} · {formatMoney(latestAmount)} · {stats.latest_batch.count}条
          </span>
        )}
      </div>

      {/* Row 1: Stat Cards (Morandi style) */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
        <StatCard
          title={t('dashboard.totalAmount')}
          value={formatMoney(stats.total_amount)}
          subtitle={`${t('dashboard.billBatchCount')}：${stats.bill_batch_count} · ${t('dashboard.billDetailCount')}：${formatNumber(stats.bill_detail_count)}`}
          bgColor={COLORS.charcoal}
          textColor={COLORS.textLight}
        >
          <ProgressBar
            confirmed={stats.confirmed_count}
            pending={stats.pending_count}
            total={totalAllocation}
          />
        </StatCard>

        <StatCard
          title={t('dashboard.feeBreakdown')}
          value={formatMoney(latestAmount)}
          subtitle={`${latestMonth} ${t('dashboard.monthLabel')}`}
          bgColor={COLORS.cream}
          textColor={COLORS.textDark}
        >
          <DonutChart data={stats.fee_breakdown || []} size={80} />
        </StatCard>

        <StatCard
          title={t('dashboard.allocationResults')}
          value={formatNumber(stats.allocation_result_count)}
          subtitle={`${t('dashboard.confirmedCount')}：${stats.confirmed_count} · ${t('dashboard.pendingCount')}：${stats.pending_count}`}
          bgColor={COLORS.sage}
          textColor={COLORS.textLight}
        >
          <ProgressBar
            confirmed={stats.confirmed_count}
            pending={stats.pending_count}
            total={totalAllocation}
          />
        </StatCard>

        <StatCard
          title="组织规模"
          value={`${formatNumber(stats.org_count)}`}
          subtitle={`${t('dashboard.branchCount')}：${stats.branch_count} · ${t('dashboard.userCount')}：${stats.user_count}`}
          bgColor={COLORS.taupe}
          textColor={COLORS.textLight}
        >
          <div style={{ display: 'flex', gap: 16 }}>
            <div>
              <div style={{ fontSize: 20, fontWeight: 600 }}>{formatNumber(stats.branch_count)}</div>
              <div style={{ fontSize: 11, opacity: 0.7 }}>一级分行</div>
            </div>
            <div>
              <div style={{ fontSize: 20, fontWeight: 600 }}>{formatNumber(stats.user_count)}</div>
              <div style={{ fontSize: 11, opacity: 0.7 }}>系统用户</div>
            </div>
          </div>
        </StatCard>
      </div>

      {/* Row 2: Monthly Trend + Branch Ranking */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        {/* Monthly Trend */}
        <Card
          title={t('dashboard.monthlyTrend')}
          bordered={false}
          headStyle={{ borderBottom: `1px solid ${COLORS.border}` }}
          style={{ borderRadius: 16, boxShadow: '0 2px 8px rgba(0,0,0,0.04)' }}
        >
          <MonthlyBarChart data={stats.monthly_trend || []} />
        </Card>

        {/* Branch Ranking */}
        <Card
          title={t('dashboard.branchRanking')}
          bordered={false}
          headStyle={{ borderBottom: `1px solid ${COLORS.border}` }}
          style={{ borderRadius: 16, boxShadow: '0 2px 8px rgba(0,0,0,0.04)' }}
        >
          <Table
            columns={branchColumns}
            dataSource={(stats.branch_summary || []).map((b, i) => ({ ...b, key: i }))}
            size="small"
            pagination={false}
            scroll={{ y: 200 }}
            locale={{ emptyText: t('dashboard.noData') }}
          />
        </Card>
      </div>
    </div>
  );
}
