import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import enUS from 'antd/locale/en_US';
import { useTranslation } from 'react-i18next';
import { morandiTheme } from './theme/morandi';
import { useAuthStore } from './store/auth';
import AppLayout from './components/AppLayout';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import DataImport from './pages/DataImport';
import BillManagement from './pages/BillManagement';
import L1SummaryPage from './pages/L1SummaryPage';
import L2BranchPage from './pages/L2BranchPage';
import L3SubBranchPage from './pages/L3SubBranchPage';
import FeeAnalysisPage from './pages/FeeAnalysisPage';
import Organization from './pages/Organization';
import PhoneNumberOwnership from './pages/PhoneNumberOwnership';
import DepartmentOwnership from './pages/DepartmentOwnership';
import DirectoryPage from './pages/DirectoryPage';
import UserManagement from './pages/UserManagement';
import TemplateManagement from './pages/TemplateManagement';
import AuditLogPage from './pages/AuditLogPage';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 30000 } } });

const PrivateRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const token = useAuthStore((s) => s.token);
  return token ? <>{children}</> : <Navigate to="/login" replace />;
};

const AntdLocaleWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { i18n } = useTranslation();
  const locale = i18n.language?.startsWith('en') ? enUS : zhCN;
  return <ConfigProvider locale={locale} theme={morandiTheme}>{children}</ConfigProvider>;
};

const App: React.FC = () => (
  <AntdLocaleWrapper>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<PrivateRoute><AppLayout /></PrivateRoute>}>
            <Route index element={<Dashboard />} />
            <Route path="import" element={<DataImport />} />
            <Route path="bill" element={<BillManagement />} />
            <Route path="allocation" element={<L1SummaryPage />} />
            <Route path="allocation/branch" element={<L2BranchPage />} />
            <Route path="allocation/sub-branch" element={<L3SubBranchPage />} />
            <Route path="allocation/analysis" element={<FeeAnalysisPage />} />
            <Route path="org" element={<Organization />} />
            <Route path="base/phone-ownership" element={<PhoneNumberOwnership />} />
            <Route path="base/dept-ownership" element={<DepartmentOwnership />} />
            <Route path="base/directory" element={<DirectoryPage />} />
            <Route path="settings/users" element={<UserManagement />} />
            <Route path="settings/audit-log" element={<AuditLogPage />} />
            <Route path="templates" element={<TemplateManagement />} />
            {/* Redirect old paths */}
            <Route path="settings" element={<Navigate to="/settings/users" replace />} />
            <Route path="audit-log" element={<Navigate to="/settings/audit-log" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </AntdLocaleWrapper>
);
export default App;
