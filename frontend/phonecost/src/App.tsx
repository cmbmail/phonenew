import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useAuthStore } from './store/auth';
import AppLayout from './components/AppLayout';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import DataImport from './pages/DataImport';
import BillManagement from './pages/BillManagement';
import AllocationPage from './pages/AllocationPage';
import Organization from './pages/Organization';
import UserManagement from './pages/UserManagement';
import TemplateManagement from './pages/TemplateManagement';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 30000 } } });
const PrivateRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const token = useAuthStore((s) => s.token);
  return token ? <>{children}</> : <Navigate to="/login" replace />;
};

const App: React.FC = () => (
  <ConfigProvider locale={zhCN}>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<PrivateRoute><AppLayout /></PrivateRoute>}>
            <Route index element={<Dashboard />} />
            <Route path="import" element={<DataImport />} />
            <Route path="bill" element={<BillManagement />} />
            <Route path="allocation" element={<AllocationPage />} />
            <Route path="org" element={<Organization />} />
            <Route path="settings" element={<UserManagement />} />
            <Route path="templates" element={<TemplateManagement />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </ConfigProvider>
);
export default App;
