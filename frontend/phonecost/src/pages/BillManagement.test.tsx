import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Mock react-router-dom
vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

// Mock i18n
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'zh-CN' },
  }),
}));

// Mock API
vi.mock('../api/allocation', () => ({
  getBillBatches: vi.fn().mockResolvedValue([{
    id: 1,
    batch_no: '202603001',
    billing_month: '2026-03',
    file_name: '账单.xlsx',
    total_count: 929,
    total_amount: 123456.78,
    status: 2,
    created_at: '2026-03-15T10:30:00',
  }]),
  getAllocationResults: vi.fn().mockResolvedValue([{
    id: 1, batch_id: 1, org_id: 6, org_name: '北京分行',
    phone_count: 150, monthly_rent: 4500, call_fee: 12000,
    recording_fee: 3000, crbt_fee: 1500, flash_msg_fee: 0, total_fee: 21000,
    confirm_status: 0,
  }]),
  calculateAllocation: vi.fn().mockResolvedValue({ org_count: 30 }),
  confirmAllocation: vi.fn().mockResolvedValue(null),
  confirmAllAllocation: vi.fn().mockResolvedValue({ confirmed_count: 28 }),
  withdrawAllocation: vi.fn().mockResolvedValue(null),
  getExportSummaryUrl: (id: number) => `/api/export/summary/${id}`,
  getExportDetailUrl: (id: number) => `/api/export/detail/${id}`,
}));

import BillManagement from '../pages/BillManagement';

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
  );
}

describe('BillManagement', () => {
  it('renders bill management table headers', async () => {
    renderWithQuery(<BillManagement />);
    expect(screen.getByText('bill.batchNo')).toBeInTheDocument();
    expect(screen.getByText('bill.month')).toBeInTheDocument();
    expect(screen.getByText('bill.fileName')).toBeInTheDocument();
    expect(screen.getByText('bill.status')).toBeInTheDocument();
  });

  it('shows batch data after loading', async () => {
    renderWithQuery(<BillManagement />);
    // Wait for async data to load
    await waitFor(() => {
      expect(screen.getByText('202603001')).toBeInTheDocument();
    });
    expect(screen.getByText('2026-03')).toBeInTheDocument();
  });

  it('renders empty state initially before data loads', () => {
    renderWithQuery(<BillManagement />);
    // Table headers should be present immediately
    expect(screen.getByText('bill.batchNo')).toBeInTheDocument();
    // "No data" may appear while loading
  });
});
