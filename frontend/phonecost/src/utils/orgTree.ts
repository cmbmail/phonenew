import type { DataNode } from 'antd/es/tree';
import type { Organization } from '../types/organization';

/**
 * Build an Ant Design Tree DataNode[] from a flat Organization list.
 * Used by OrganizationPage and UserManagement.
 */
export function buildOrgTree(list: Organization[]): DataNode[] {
  const map = new Map<number, DataNode>();
  const roots: DataNode[] = [];
  list.forEach((org) => {
    map.set(org.id, {
      key: org.id,
      title: org.name,
      children: [],
    });
  });
  list.forEach((org) => {
    const node = map.get(org.id)!;
    if (org.parent_id && map.has(org.parent_id)) {
      map.get(org.parent_id)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  const markLeaf = (nodes: DataNode[]) => {
    nodes.forEach((n) => {
      if (!n.children || n.children.length === 0) {
        n.isLeaf = true;
      } else {
        markLeaf(n.children);
      }
    });
  };
  markLeaf(roots);
  return roots;
}

/**
 * Build tree data for Ant Design TreeSelect (adds `value` field).
 * Used by UserManagement for org selection.
 */
export function buildOrgTreeSelectData(list: Organization[]): DataNode[] {
  const map = new Map<number, DataNode>();
  const roots: DataNode[] = [];
  list.forEach((org) => {
    map.set(org.id, {
      key: org.id,
      value: org.id,
      title: org.name,
      children: [],
    });
  });
  list.forEach((org) => {
    const node = map.get(org.id)!;
    if (org.parent_id && map.has(org.parent_id)) {
      map.get(org.parent_id)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  const markLeaf = (nodes: DataNode[]) => {
    nodes.forEach((n) => {
      if (!n.children || n.children.length === 0) n.isLeaf = true;
      else markLeaf(n.children);
    });
  };
  markLeaf(roots);
  return roots;
}
