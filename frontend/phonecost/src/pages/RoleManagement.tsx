import React, { useEffect, useState } from 'react';
import { Card, Table, Tag, Descriptions, Drawer } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { apiGet } from '../lib/request';
import { COLORS } from '../theme/morandi';

interface RoleItem {
  id: number;
  code: string;
  name: string;
  description: string;
  user_count: number;
  permissions: string[];
}

interface PermissionModule {
  module: string;
  permissions: { key: string; label: string }[];
}

interface PermissionsData {
  roles: { id: number; code: string; name: string; description: string }[];
  modules: PermissionModule[];
  matrix: Record<number, string[]>;
}

const ROLE_COLORS: Record<number, string> = {
  1: COLORS.sage,
  2: COLORS.slate,
  3: COLORS.taupe,
  4: COLORS.mauve,
};

export default function RoleManagement() {
  const { t } = useTranslation();
  const [roles, setRoles] = useState<RoleItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedRole, setSelectedRole] = useState<RoleItem | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [permissions, setPermissions] = useState<PermissionsData | null>(null);

  useEffect(() => {
    setLoading(true);
    apiGet<RoleItem[]>('/roles')
      .then(setRoles)
      .catch(() => setRoles([]))
      .finally(() => setLoading(false));
    apiGet<PermissionsData>('/roles/permissions')
      .then(setPermissions)
      .catch(() => {});
  }, []);

  const columns = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 60,
      render: (id: number) => <Tag color={ROLE_COLORS[id]}>{id}</Tag>,
    },
    {
      title: '角色编码',
      dataIndex: 'code',
      key: 'code',
      width: 120,
      render: (code: string) => <code style={{ background: '#f5f5f5', padding: '2px 8px', borderRadius: 4, fontSize: 12 }}>{code}</code>,
    },
    {
      title: '角色名称',
      dataIndex: 'name',
      key: 'name',
      width: 140,
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
    },
    {
      title: '用户数',
      dataIndex: 'user_count',
      key: 'user_count',
      width: 90,
      align: 'center' as const,
      render: (count: number) => <strong>{count}</strong>,
    },
  ];

  const showDetail = (role: RoleItem) => {
    setSelectedRole(role);
    setDrawerOpen(true);
  };

  return (
    <div>
      <Card>
        <Table
          columns={columns}
          dataSource={roles}
          rowKey="id"
          size="small"
          loading={loading}
          pagination={false}
          onRow={(record) => ({ onClick: () => showDetail(record), style: { cursor: 'pointer' } })}
        />
      </Card>

      {/* 权限矩阵 */}
      {permissions && (
        <Card title="权限矩阵" style={{ marginTop: 16 }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ background: COLORS.cream }}>
                <th style={{ padding: '8px 12px', borderBottom: `2px solid ${COLORS.border}`, textAlign: 'left', whiteSpace: 'nowrap' }}>功能模块 / 权限</th>
                {permissions.roles.map(r => (
                  <th key={r.id} style={{ padding: '8px 12px', borderBottom: `2px solid ${COLORS.border}`, textAlign: 'center', whiteSpace: 'nowrap', minWidth: 80 }}>
                    <Tag color={ROLE_COLORS[r.id]}>{r.name}</Tag>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {permissions.modules.map(mod => (
                <React.Fragment key={mod.module}>
                  <tr>
                    <td colSpan={permissions.roles.length + 1} style={{ padding: '8px 12px', background: `rgba(139,157,158,0.08)`, fontWeight: 600, borderBottom: `1px solid ${COLORS.border}` }}>
                      {mod.module}
                    </td>
                  </tr>
                  {mod.permissions.map(perm => (
                    <tr key={perm.key} style={{ borderBottom: `1px solid ${COLORS.border}` }}>
                      <td style={{ padding: '6px 12px 6px 32px', color: COLORS.textMuted }}>{perm.label}</td>
                      {permissions.roles.map(r => {
                        const has = permissions.matrix[r.id]?.includes(perm.key);
                        return (
                          <td key={r.id} style={{ padding: '6px 12px', textAlign: 'center' }}>
                            {has ? (
                              <span style={{ color: COLORS.confirmed, fontWeight: 700 }}>&#10003;</span>
                            ) : (
                              <span style={{ color: '#ddd' }}>&#8212;</span>
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </React.Fragment>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {/* 角色详情抽屉 */}
      <Drawer
        title={
          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <SafetyCertificateOutlined style={{ color: ROLE_COLORS[selectedRole?.id || 0] || COLORS.sage }} />
            {selectedRole?.name || ''}
          </span>
        }
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={420}
      >
        {selectedRole && (
          <>
            <Descriptions column={1} size="small" style={{ marginBottom: 24 }}>
              <Descriptions.Item label="ID">{selectedRole.id}</Descriptions.Item>
              <Descriptions.Item label="角色编码">
                <code style={{ background: '#f5f5f5', padding: '2px 8px', borderRadius: 4 }}>{selectedRole.code}</code>
              </Descriptions.Item>
              <Descriptions.Item label="角色名称">{selectedRole.name}</Descriptions.Item>
              <Descriptions.Item label="描述">{selectedRole.description}</Descriptions.Item>
              <Descriptions.Item label="用户数"><strong>{selectedRole.user_count}</strong></Descriptions.Item>
              <Descriptions.Item label="权限数"><strong>{selectedRole.permissions.length}</strong></Descriptions.Item>
            </Descriptions>
            {permissions && (
              <div>
                <div style={{ fontWeight: 600, marginBottom: 12 }}>权限清单</div>
                {permissions.modules.map(mod => {
                  const granted = mod.permissions.filter(p => selectedRole.permissions.includes(p.key));
                  if (granted.length === 0) return null;
                  return (
                    <div key={mod.module} style={{ marginBottom: 12 }}>
                      <div style={{ fontSize: 12, color: COLORS.textMuted, marginBottom: 4 }}>{mod.module}</div>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                        {granted.map(p => (
                          <Tag key={p.key} color={ROLE_COLORS[selectedRole.id]}>{p.label}</Tag>
                        ))}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}
