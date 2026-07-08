import { apiGet, apiPost, apiPut, apiDelete } from '../lib/request';

export interface AnnouncementItem {
  id: number;
  title: string;
  content: string;
  type: number;       // 0=通知 1=公告
  priority: number;   // 0=普通 1=重要 2=紧急
  status: number;     // 0=草稿 1=已发布 2=已归档
  author_id: number | null;
  author_name: string;
  published_at: string | null;
  pinned: number;     // 0=否 1=是
  created_at: string;
  updated_at: string;
}

export interface PagedAnnouncements {
  content: AnnouncementItem[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export const getAnnouncements = (params: {
  page?: number;
  size?: number;
  status?: number;
  type?: number;
  keyword?: string;
} = {}) => {
  const qs = new URLSearchParams();
  if (params.page != null) qs.set('page', String(params.page));
  if (params.size != null) qs.set('size', String(params.size));
  if (params.status != null) qs.set('status', String(params.status));
  if (params.type != null) qs.set('type', String(params.type));
  if (params.keyword) qs.set('keyword', params.keyword);
  return apiGet<PagedAnnouncements>(`/announcements?${qs.toString()}`);
};

export const getLatestAnnouncements = () =>
  apiGet<AnnouncementItem[]>('/announcements/latest');

export const getAnnouncement = (id: number) =>
  apiGet<AnnouncementItem>(`/announcements/${id}`);

export const createAnnouncement = (data: {
  title: string;
  content: string;
  type?: number;
  priority?: number;
  status?: number;
  author_name?: string;
  pinned?: number;
}) => apiPost<AnnouncementItem>('/announcements', data);

export const updateAnnouncement = (id: number, data: {
  title?: string;
  content?: string;
  type?: number;
  priority?: number;
  pinned?: number;
}) => apiPut<AnnouncementItem>(`/announcements/${id}`, data);

export const publishAnnouncement = (id: number) =>
  apiPut<AnnouncementItem>(`/announcements/${id}/publish`);

export const archiveAnnouncement = (id: number) =>
  apiPut<AnnouncementItem>(`/announcements/${id}/archive`);

export const deleteAnnouncement = (id: number) =>
  apiDelete<void>(`/announcements/${id}`);
