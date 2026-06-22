export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}
export interface PagedData<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
  total_pages: number;
}
