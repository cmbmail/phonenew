import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Login from '../pages/Login';

// Mock react-router-dom
const mockedNavigate = vi.fn();
vi.mock('react-router-dom', () => ({
  useNavigate: () => mockedNavigate,
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
vi.mock('../api/auth', () => ({
  loginApi: vi.fn().mockResolvedValue({
    access_token: 'test-token',
    refresh_token: 'refresh-token',
    user: { id: 1, username: 'admin', role: 1, org_id: 5 },
  }),
}));

describe('Login', () => {
  it('renders login form with username and password fields', () => {
    render(<Login />);
    expect(screen.getByPlaceholderText(/username/i || /用户名/i)).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/password/i || /密码/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /login/i || /登录/i })).toBeInTheDocument();
  });

  it('shows title text', () => {
    render(<Login />);
    // The t() function returns the key as-is in our mock; Login page uses login.title
    const heading = screen.getByRole('heading');
    expect(heading).toBeInTheDocument();
    expect(heading.textContent).toContain('title');
  });

  it('allows typing username and password', async () => {
    const user = userEvent.setup();
    render(<Login />);

    const usernameInput = screen.getByPlaceholderText(/username/i || /用户名/i);
    const passwordInput = screen.getByPlaceholderText(/password/i || /密码/i);

    await user.type(usernameInput, 'admin');
    await user.type(passwordInput, 'password123');

    expect(usernameInput).toHaveValue('admin');
    expect(passwordInput).toHaveValue('password123');
  });
});
