import React, { Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, Spin } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import enUS from 'antd/locale/en_US';
import { useTranslation } from 'react-i18next';
import { morandiTheme } from './theme/morandi';
import { useAuthStore } from './store/auth';
import ErrorBoundary from './components/ErrorBoundary';
import AppLayout from './components/AppLayout';
import Login from './pages/Login';

// 路由级懒加载
const Dashboard = React.lazy(() => import('./pages/Dashboard'));
const DataImport = React.lazy(() => import('./pages/DataImport'));
const BillManagement = React.lazy(() => import('./pages/BillManagement'));
const L1SummaryPage = React.lazy(() => import('./pages/L1SummaryPage'));
const L2BranchPage = React.lazy(() => import('./pages/L2BranchPage'));
const L3SubBranchPage = React.lazy(() => import('./pages/L3SubBranchPage'));
const FeeAnalysisPage = React.lazy(() => import('./pages/FeeAnalysisPage'));
const Organization = React.lazy(() => import('./pages/Organization'));
const PhoneNumberOwnership = React.lazy(() => import('./pages/PhoneNumberOwnership'));
const DepartmentOwnership = React.lazy(() => import('./pages/DepartmentOwnership'));
const DirectoryPage = React.lazy(() => import('./pages/DirectoryPage'));
const UserManagement = React.lazy(() => import('./pages/UserManagement'));
const TemplateManagement = React.lazy(() => import('./pages/TemplateManagement'));
const RoleManagement = React.lazy(() => import('./pages/RoleManagement'));
const AuditLogPage = React.lazy(() => import('./pages/AuditLogPage'));
const DataMaintenancePage = React.lazy(() => import('./pages/DataMaintenancePage'));

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 30000 } } });

const PageLoading = () => (
  <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
    <Spin size="large" />
  </div>
);

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
        <ErrorBoundary>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/" element={<PrivateRoute><AppLayout /></PrivateRoute>}>
              <Route index element={<Suspense fallback={<PageLoading />}><Dashboard /></Suspense>} />
              <Route path="import" element={<Suspense fallback={<PageLoading />}><DataImport /></Suspense>} />
              <Route path="bill" element={<Suspense fallback={<PageLoading />}><BillManagement /></Suspense>} />
              <Route path="allocation" element={<Suspense fallback={<PageLoading />}><L1SummaryPage /></Suspense>} />
              <Route path="allocation/branch" element={<Suspense fallback={<PageLoading />}><L2BranchPage /></Suspense>} />
              <Route path="allocation/sub-branch" element={<Suspense fallback={<PageLoading />}><L3SubBranchPage /></Suspense>} />
              <Route path="allocation/analysis" element={<Suspense fallback={<PageLoading />}><FeeAnalysisPage /></Suspense>} />
              <Route path="org" element={<Suspense fallback={<PageLoading />}><Organization /></Suspense>} />
              <Route path="base/phone-ownership" element={<Suspense fallback={<PageLoading />}><PhoneNumberOwnership /></Suspense>} />
              <Route path="base/dept-ownership" element={<Suspense fallback={<PageLoading />}><DepartmentOwnership /></Suspense>} />
              <Route path="base/directory" element={<Suspense fallback={<PageLoading />}><DirectoryPage /></Suspense>} />
              <Route path="settings/users" element={<Suspense fallback={<PageLoading />}><UserManagement /></Suspense>} />
              <Route path="settings/roles" element={<Suspense fallback={<PageLoading />}><RoleManagement /></Suspense>} />
              <Route path="settings/audit-log" element={<Suspense fallback={<PageLoading />}><AuditLogPage /></Suspense>} />
              <Route path="settings/data-maintenance" element={<Suspense fallback={<PageLoading />}><DataMaintenancePage /></Suspense>} />
              <Route path="templates" element={<Suspense fallback={<PageLoading />}><TemplateManagement /></Suspense>} />
              {/* Redirect old paths */}
              <Route path="settings" element={<Navigate to="/settings/users" replace />} />
              <Route path="audit-log" element={<Navigate to="/settings/audit-log" replace />} />
              {/* 404 catch-all */}
              <Route path="*" element={<Navigate to="/" replace />} />
            </Route>
          </Routes>
        </ErrorBoundary>
      </BrowserRouter>
    </QueryClientProvider>
  </AntdLocaleWrapper>
);
export default App;
