export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

/** Shared error shape for Axios catch blocks */
export interface ApiError {
  response?: {
    data?: {
      message?: string;
    };
  };
  errorFields?: { name: string; errors: { message: string }[] }[];
}

/** Type guard for API errors in catch blocks */
export function isApiError(err: unknown): err is ApiError {
  return typeof err === 'object' && err !== null;
}

/** Extract error message from caught error, fallback to default */
export function getErrorMessage(err: unknown, fallback: string): string {
  if (isApiError(err)) {
    return (err as ApiError).response?.data?.message || fallback;
  }
  return fallback;
}

/** Tree node for Ant Design Tree/TreeSelect */
export interface TreeNode {
  value: number;
  title: string;
  children?: TreeNode[];
}
