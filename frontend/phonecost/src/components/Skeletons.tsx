import React from 'react';
import { Skeleton } from 'antd';

/** 表格骨架屏 — 用于页面首次加载时替代空白表格 */
export const TableSkeleton: React.FC<{ rows?: number; columns?: number }> = ({ rows = 5, columns = 4 }) => (
  <div style={{ padding: '12px 0' }}>
    <Skeleton active paragraph={{ rows }} />
    <div style={{ display: 'grid', gridTemplateColumns: `repeat(${columns}, 1fr)`, gap: 12, marginTop: 16 }}>
      {Array.from({ length: columns }).map((_, i) => (
        <Skeleton.Input key={i} active size="small" style={{ width: '100%' }} />
      ))}
    </div>
  </div>
);

/** 卡片骨架屏 — 用于统计卡片区域 */
export const CardSkeleton: React.FC<{ count?: number }> = ({ count = 4 }) => (
  <div style={{ display: 'grid', gridTemplateColumns: `repeat(${count}, 1fr)`, gap: 16, marginBottom: 24 }}>
    {Array.from({ length: count }).map((_, i) => (
      <div key={i} style={{ padding: 20, background: '#fff', borderRadius: 12, boxShadow: '0 2px 8px rgba(0,0,0,0.04)' }}>
        <Skeleton active paragraph={{ rows: 2 }} />
      </div>
    ))}
  </div>
);

export default TableSkeleton;
