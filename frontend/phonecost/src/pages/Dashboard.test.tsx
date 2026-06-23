import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'zh-CN' },
  }),
}));

vi.mock('../api/dashboard', () => ({
  getDashboardStats: vi.fn().mockResolvedValue({
    org_count: 930,
    user_count: 3,
    bill_batch_count: 5,
    total_amount: 123456.78,
    confirmed_count: 2,
    pending_count: 3,
  }),
}));

vi.mock('../stores/authStore', () => ({
  useAuthStore: () => ({
    user: { id: 1, username: 'admin', role: 1, org_id: 5 },
    token: 'test-token',
    logout: vi.fn(),
  }),
}));

import Dashboard from '../pages/Dashboard';

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('Dashboard', () => {
  it('renders dashboard title', () => {
    renderWithQuery(<Dashboard />);
    expect(screen.getByText('dashboard.title')).toBeInTheDocument();
  });

  it('shows skeleton while loading', () => {
    renderWithQuery(<Dashboard />);
    expect(document.querySelectorAll('.ant-skeleton').length).toBeGreaterThan(0);
  });

  it('renders stat cards with correct data after loading', async () => {
    renderWithQuery(<Dashboard />);
    // Wait for data to load and skeletons to be replaced by actual content
    await waitFor(() => {
      expect(screen.getByText('930')).toBeInTheDocument();
      expect(screen.getByText('dashboard.orgCount')).toBeInTheDocument();
      expect(screen.getByText('dashboard.userCount')).toBeInTheDocument();
      expect(screen.getByText('dashboard.billBatchCount')).toBeInTheDocument();
      expect(screen.getByText('dashboard.totalAmount')).toBeInTheDocument();
      expect(screen.getByText('dashboard.confirmedCount')).toBeInTheDocument();
      expect(screen.getByText('dashboard.pendingCount')).toBeInTheDocument();
    });
  });
});
