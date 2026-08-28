import React, { Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, Spin, Result, Button } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { morandiTheme } from './theme/morandi';
import { useAuthStore } from './store/auth';
import ErrorBoundary from './components/ErrorBoundary';
import AppLayout from './components/AppLayout';
import Login from './pages/Login';

// 路由级懒加载
const Dashboard = React.lazy(() => import('./pages/Dashboard'));
const BillManagement = React.lazy(() => import('./pages/BillManagement'));
const L1SummaryPage = React.lazy(() => import('./pages/L1SummaryPage'));
const L2BranchPage = React.lazy(() => import('./pages/L2BranchPage'));
const L3SubBranchPage = React.lazy(() => import('./pages/L3SubBranchPage'));
const FeeAnalysisPage = React.lazy(() => import('./pages/FeeAnalysisPage'));
const Organization = React.lazy(() => import('./pages/Organization'));
const UserManagement = React.lazy(() => import('./pages/UserManagement'));
const TemplateManagement = React.lazy(() => import('./pages/TemplateManagement'));
const RoleManagement = React.lazy(() => import('./pages/RoleManagement'));
const AuditLogPage = React.lazy(() => import('./pages/AuditLogPage'));
const DataMaintenancePage = React.lazy(() => import('./pages/DataMaintenancePage'));
const AnnouncementPage = React.lazy(() => import('./pages/AnnouncementPage'));
const DataComparisonPage = React.lazy(() => import('./pages/DataComparisonPage'));
const AllocationPhoneOwnership = React.lazy(() => import('./pages/AllocationPhoneOwnership'));
const BranchNumberPage = React.lazy(() => import('./pages/BranchNumberPage'));
const AllocationOrgPage = React.lazy(() => import('./pages/AllocationOrgPage'));
const OrgCodeMappingPage = React.lazy(() => import('./pages/OrgCodeMappingPage'));

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 30000 } } });

const PageLoading = () => (
  <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
    <Spin size="large" />
  </div>
);

// ============ 路由级 ErrorBoundary + Suspense 统一包裹 ============
const LazyRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <ErrorBoundary>
    <Suspense fallback={<PageLoading />}>
      {children}
    </Suspense>
  </ErrorBoundary>
);

// ============ PrivateRoute: 支持 RBAC 角色守卫 ============
// role: 1=管理员 2=运维 3=财务 4=领导
const ROLE_NAMES: Record<number, string> = { 1: '管理员', 2: '运维', 3: '财务', 4: '领导' };

const PrivateRoute: React.FC<{
  children: React.ReactNode;
  allowedRoles?: number[];
}> = ({ children, allowedRoles }) => {
  const token = useAuthStore((s) => s.token);
  const role = useAuthStore((s) => s.role);

  if (!token) return <Navigate to="/login" replace />;

  if (allowedRoles && role && !allowedRoles.includes(role)) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '60vh' }}>
        <Result
          status="403"
          title="无访问权限"
          subTitle={`当前角色「${ROLE_NAMES[role] || '未知'}」无权访问此页面`}
          extra={<Button type="primary" onClick={() => { window.location.href = '/'; }}>返回首页</Button>}
        />
      </div>
    );
  }

  return <>{children}</>;
};

const AntdLocaleWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  return <ConfigProvider locale={zhCN} theme={morandiTheme}>{children}</ConfigProvider>;
};

const App: React.FC = () => (
  <AntdLocaleWrapper>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<PrivateRoute><AppLayout /></PrivateRoute>}>
            <Route index element={<LazyRoute><Dashboard /></LazyRoute>} />
            <Route path="bill" element={<LazyRoute><BillManagement /></LazyRoute>} />
            <Route path="allocation" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2, 3, 4]}><L1SummaryPage /></PrivateRoute></LazyRoute>} />
            <Route path="allocation/branch" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2, 3, 4]}><L2BranchPage /></PrivateRoute></LazyRoute>} />
            <Route path="allocation/sub-branch" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2, 3, 4]}><L3SubBranchPage /></PrivateRoute></LazyRoute>} />
            <Route path="allocation/analysis" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2, 3]}><FeeAnalysisPage /></PrivateRoute></LazyRoute>} />
            <Route path="org" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2]}><Organization /></PrivateRoute></LazyRoute>} />
            {/* 系统管理页面：仅管理员(1)和运维(2)可访问 */}
            <Route path="settings/users" element={<LazyRoute><PrivateRoute allowedRoles={[1]}><UserManagement /></PrivateRoute></LazyRoute>} />
            <Route path="settings/roles" element={<LazyRoute><PrivateRoute allowedRoles={[1]}><RoleManagement /></PrivateRoute></LazyRoute>} />
            <Route path="settings/announcements" element={<LazyRoute><PrivateRoute allowedRoles={[1]}><AnnouncementPage /></PrivateRoute></LazyRoute>} />
            <Route path="settings/audit-log" element={<LazyRoute><PrivateRoute allowedRoles={[1]}><AuditLogPage /></PrivateRoute></LazyRoute>} />
            <Route path="settings/data-maintenance" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2]}><DataMaintenancePage /></PrivateRoute></LazyRoute>} />
            <Route path="data-comparison" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2]}><DataComparisonPage /></PrivateRoute></LazyRoute>} />
            <Route path="maintenance/allocation-ownership" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2]}><AllocationPhoneOwnership /></PrivateRoute></LazyRoute>} />
            <Route path="maintenance/branch-number" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2]}><BranchNumberPage /></PrivateRoute></LazyRoute>} />
            <Route path="maintenance/allocation-org" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2]}><AllocationOrgPage /></PrivateRoute></LazyRoute>} />
            <Route path="maintenance/org-code-mapping" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2]}><OrgCodeMappingPage /></PrivateRoute></LazyRoute>} />
            <Route path="templates" element={<LazyRoute><PrivateRoute allowedRoles={[1, 2]}><TemplateManagement /></PrivateRoute></LazyRoute>} />
            {/* Redirect old paths */}
            <Route path="settings" element={<Navigate to="/settings/users" replace />} />
            <Route path="audit-log" element={<Navigate to="/settings/audit-log" replace />} />
            {/* 404 catch-all */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </AntdLocaleWrapper>
);
export default App;
