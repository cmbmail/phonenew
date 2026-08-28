import React, { useEffect, useState } from 'react';
import { Card, Table, Tag, Descriptions, Drawer, message } from 'antd';
import { SafetyCertificateOutlined, BankOutlined, EyeOutlined } from '@ant-design/icons';
import { apiGet } from '../lib/request';
import { COLORS } from '../theme/morandi';
import { useTranslation } from 'react-i18next';

interface DataScope {
  scope_type: string;
  label: string;
  description: string;
  org_levels: string[];
  access_mode: string;
  details: string[];
}

interface RoleItem {
  id: number;
  code: string;
  name: string;
  description: string;
  user_count: number;
  permissions: string[];
  data_scope: DataScope;
}

interface PermissionModule {
  module: string;
  permissions: { key: string; label: string }[];
}

interface PermissionsData {
  roles: { id: number; code: string; name: string; description: string }[];
  modules: PermissionModule[];
  matrix: Record<number, string[]>;
  data_scopes: { role_id: number; scope_type: string; label: string; description: string; org_levels: string[]; access_mode: string; details: string[] }[];
}

const ROLE_COLORS: Record<number, string> = {
  1: COLORS.sage,
  2: COLORS.slate,
  3: COLORS.taupe,
  4: COLORS.mauve,
};

const SCOPE_TYPE_CONFIG: Record<string, { icon: React.ReactNode; color: string }> = {
  ALL: { icon: <BankOutlined />, color: COLORS.sage },
  SUBTREE: { icon: <BankOutlined />, color: COLORS.slate },
  SINGLE: { icon: <EyeOutlined />, color: COLORS.taupe },
};

const ACCESS_MODE_STYLE: Record<string, { color: string }> = {
  '读写': { color: COLORS.confirmed },
  '只读': { color: COLORS.slate },
};

// i18n-aware versions
function useAccessModeStyle(t: (k: string) => string) {
  return {
    [t('roleMgmt.accessReadWrite')]: { color: COLORS.confirmed },
    [t('roleMgmt.accessReadOnly')]: { color: COLORS.slate },
  };
}

export default function RoleManagement() {
  const { t } = useTranslation();
  const accessModeStyle = useAccessModeStyle(t);
  const [roles, setRoles] = useState<RoleItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedRole, setSelectedRole] = useState<RoleItem | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [permissions, setPermissions] = useState<PermissionsData | null>(null);

  useEffect(() => {
    setLoading(true);
    apiGet<RoleItem[]>('/roles')
      .then(setRoles)
      .catch(() => { setRoles([]); message.error(t('roleMgmt.fetchRolesFailed')); })
      .finally(() => setLoading(false));
    apiGet<PermissionsData>('/roles/permissions')
      .then(setPermissions)
      .catch(() => { message.error(t('roleMgmt.fetchPermissionsFailed')); });
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
      title: t('roleMgmt.colCode'),
      dataIndex: 'code',
      key: 'code',
      width: 120,
      render: (code: string) => <code style={{ background: '#f5f5f5', padding: '2px 8px', borderRadius: 4, fontSize: 12 }}>{code}</code>,
    },
    {
      title: t('roleMgmt.colName'),
      dataIndex: 'name',
      key: 'name',
      width: 140,
    },
    {
      title: t('roleMgmt.colDescription'),
      dataIndex: 'description',
      key: 'description',
    },
    {
      title: t('roleMgmt.colUserCount'),
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

      {/* 数据范围 */}
      <Card title={t('roleMgmt.dataScopeTitle')} style={{ marginTop: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 16 }}>
          {permissions?.data_scopes?.map(ds => {
            const scopeCfg = SCOPE_TYPE_CONFIG[ds.scope_type] || SCOPE_TYPE_CONFIG.SINGLE;
            const modeStyle = accessModeStyle[ds.access_mode] || accessModeStyle[t('roleMgmt.accessReadOnly')];
            const roleInfo = permissions?.roles?.find(r => r.id === ds.role_id);
            return (
              <div
                key={ds.role_id}
                style={{
                  border: `1px solid ${COLORS.border}`,
                  borderRadius: 8,
                  padding: 16,
                  cursor: 'pointer',
                  transition: 'box-shadow 0.2s',
                }}
                onClick={() => {
                  const r = roles.find(rl => rl.id === ds.role_id);
                  if (r) showDetail(r);
                }}
                onMouseEnter={e => (e.currentTarget.style.boxShadow = '0 2px 8px rgba(0,0,0,0.08)')}
                onMouseLeave={e => (e.currentTarget.style.boxShadow = 'none')}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                  <Tag color={ROLE_COLORS[ds.role_id]} style={{ margin: 0 }}>
                    {scopeCfg.icon} {roleInfo?.name || t('roleMgmt.roleFallback', { id: ds.role_id })}
                  </Tag>
                  <Tag style={{ margin: 0, color: modeStyle.color, borderColor: modeStyle.color }}>
                    {ds.access_mode}
                  </Tag>
                </div>
                <div style={{ fontWeight: 600, fontSize: 15, marginBottom: 4 }}>{ds.label}</div>
                <div style={{ fontSize: 12, color: COLORS.textMuted, marginBottom: 8 }}>{ds.description}</div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 10 }}>
                  {ds.org_levels.map((level: string) => (
                    <span
                      key={level}
                      style={{
                        fontSize: 11,
                        padding: '2px 8px',
                        borderRadius: 4,
                        background: COLORS.cream,
                        color: COLORS.textDark,
                      }}
                    >
                      {level}
                    </span>
                  ))}
                </div>
                <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, color: COLORS.textMuted, lineHeight: '20px' }}>
                  {ds.details.map((d: string, i: number) => (
                    <li key={i}>{d}</li>
                  ))}
                </ul>
              </div>
            );
          })}
        </div>
      </Card>

      {/* 权限矩阵 */}
      {permissions && (
        <Card title={t('roleMgmt.permissionMatrixTitle')} style={{ marginTop: 16 }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ background: COLORS.cream }}>
                <th style={{ padding: '8px 12px', borderBottom: `2px solid ${COLORS.border}`, textAlign: 'left', whiteSpace: 'nowrap' }}>{t('roleMgmt.colModulePermission')}</th>
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
        width={480}
      >
        {selectedRole && (
          <>
            <Descriptions column={1} size="small" style={{ marginBottom: 24 }}>
              <Descriptions.Item label="ID">{selectedRole.id}</Descriptions.Item>
              <Descriptions.Item label={t('roleMgmt.colCode')}>
                <code style={{ background: '#f5f5f5', padding: '2px 8px', borderRadius: 4 }}>{selectedRole.code}</code>
              </Descriptions.Item>
              <Descriptions.Item label={t('roleMgmt.colName')}>{selectedRole.name}</Descriptions.Item>
              <Descriptions.Item label={t('roleMgmt.colDescription')}>{selectedRole.description}</Descriptions.Item>
              <Descriptions.Item label={t('roleMgmt.colUserCount')}><strong>{selectedRole.user_count}</strong></Descriptions.Item>
              <Descriptions.Item label={t('roleMgmt.colPermissionCount')}><strong>{selectedRole.permissions.length}</strong></Descriptions.Item>
            </Descriptions>

            {/* 数据范围 */}
            {selectedRole.data_scope && (
              <div style={{ marginBottom: 24 }}>
                <div style={{ fontWeight: 600, marginBottom: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
                  <BankOutlined style={{ color: ROLE_COLORS[selectedRole.id] }} />
                  {t('roleMgmt.dataScopeLabel')}
                </div>
                <div style={{ background: COLORS.cream, borderRadius: 8, padding: 16 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                    <Tag color={ROLE_COLORS[selectedRole.id]}>{selectedRole.data_scope.label}</Tag>
                    <Tag style={{ color: accessModeStyle[selectedRole.data_scope.access_mode]?.color, borderColor: accessModeStyle[selectedRole.data_scope.access_mode]?.color }}>
                      {selectedRole.data_scope.access_mode}
                    </Tag>
                  </div>
                  <div style={{ fontSize: 12, color: COLORS.textMuted, marginBottom: 10 }}>
                    {selectedRole.data_scope.description}
                  </div>
                  <div style={{ fontWeight: 600, fontSize: 12, marginBottom: 6, color: COLORS.textDark }}>{t('roleMgmt.orgLevelsLabel')}</div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 12 }}>
                    {selectedRole.data_scope.org_levels.map(level => (
                      <span
                        key={level}
                        style={{
                          fontSize: 11,
                          padding: '2px 8px',
                          borderRadius: 4,
                          background: '#fff',
                          color: COLORS.textDark,
                          border: `1px solid ${COLORS.border}`,
                        }}
                      >
                        {level}
                      </span>
                    ))}
                  </div>
                  <div style={{ fontWeight: 600, fontSize: 12, marginBottom: 6, color: COLORS.textDark }}>{t('roleMgmt.accessRulesLabel')}</div>
                  <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, color: COLORS.textMuted, lineHeight: '22px' }}>
                    {selectedRole.data_scope.details.map((d, i) => (
                      <li key={i}>{d}</li>
                    ))}
                  </ul>
                </div>
              </div>
            )}

            {/* 权限清单 */}
            {permissions && (
              <div>
                <div style={{ fontWeight: 600, marginBottom: 12 }}>{t('roleMgmt.permissionListLabel')}</div>
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
