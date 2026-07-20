import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { deleteCookie } from './cookie-helper';

export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || '/backend_proxy';
let accessToken: string | null = null;

type AuthAwareRequestConfig = {
  skipAuthRedirect?: boolean;
};

type RetriableRequestConfig = InternalAxiosRequestConfig & AuthAwareRequestConfig & {
  _retry?: boolean;
};

export interface ApiErrorPayload {
  message?: string;
  error?: string;
  answer?: string;
  data?: {
    message?: string;
  };
}

export const getApiErrorMessage = (error: unknown, fallback: string): string => {
  if (axios.isAxiosError<ApiErrorPayload>(error)) {
    const payload = error.response?.data;
    return payload?.message
      || payload?.data?.message
      || payload?.error
      || payload?.answer
      || fallback;
  }

  return error instanceof Error && error.message ? error.message : fallback;
};

export const getApiErrorStatus = (error: unknown): number | undefined =>
  axios.isAxiosError(error) ? error.response?.status : undefined;

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true,
});

export const publicApiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true,
});

type CachedGetOptions = {
  ttlMs?: number;
  force?: boolean;
  config?: AxiosRequestConfig;
};

type CachedResponse = {
  expiresAt: number;
  response: AxiosResponse<unknown>;
};

const getResponseCache = new Map<string, CachedResponse>();
const getRequestsInFlight = new Map<string, Promise<AxiosResponse<unknown>>>();
let getCacheGeneration = 0;

const getCacheKey = (url: string, config?: AxiosRequestConfig) => {
  const params = config?.params && typeof config.params === 'object'
    ? JSON.stringify(config.params, Object.keys(config.params).sort())
    : String(config?.params || '');
  return `${url}?${params}`;
};

/**
 * Cache rất ngắn cho dữ liệu đọc dashboard. Nó loại request kép do React
 * Strict Mode và cho phép các trang dùng chung /api/reservations khi chuyển
 * mục liên tiếp, nhưng vẫn tự xóa sau mọi mutation.
 */
// Giữ cùng khả năng suy luận response.data như apiClient.get mặc định;
// call-site có DTO cụ thể vẫn có thể truyền generic T để siết kiểu.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const cachedGet = <T = any>(
  url: string,
  { ttlMs = 5_000, force = false, config }: CachedGetOptions = {},
): Promise<AxiosResponse<T>> => {
  const key = getCacheKey(url, config);
  if (force) getResponseCache.delete(key);

  const cached = getResponseCache.get(key);
  if (cached && cached.expiresAt > Date.now()) {
    return Promise.resolve(cached.response as AxiosResponse<T>);
  }

  const currentRequest = getRequestsInFlight.get(key);
  if (currentRequest) return currentRequest as Promise<AxiosResponse<T>>;

  const requestGeneration = getCacheGeneration;
  const request = apiClient.get<T>(url, config)
    .then((response) => {
      if (requestGeneration === getCacheGeneration) {
        getResponseCache.set(key, {
          response,
          expiresAt: Date.now() + Math.max(0, ttlMs),
        });
      }
      return response;
    })
    .finally(() => {
      getRequestsInFlight.delete(key);
    });
  getRequestsInFlight.set(key, request as Promise<AxiosResponse<unknown>>);
  return request;
};

export const invalidateGetCache = (urlPrefix?: string) => {
  getCacheGeneration += 1;
  if (!urlPrefix) {
    getResponseCache.clear();
    return;
  }
  for (const key of getResponseCache.keys()) {
    if (key.startsWith(urlPrefix)) getResponseCache.delete(key);
  }
};

apiClient.interceptors.request.use(
  (config) => {
    if (accessToken && config.headers) {
      config.headers.Authorization = `Bearer ${accessToken}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

let refreshPromise: Promise<string> | null = null;

const clearAuthSession = () => {
  accessToken = null;
  invalidateGetCache();
  // Dọn dữ liệu cũ từ phiên bản trước. Refresh token mới là cookie HttpOnly.
  deleteCookie('token');
  deleteCookie('refreshToken');
  if (typeof window !== 'undefined') {
    localStorage.removeItem('user');
    localStorage.removeItem('token');
    localStorage.removeItem('access_token');
    localStorage.removeItem('refreshToken');
  }
};

const canRefreshAfterUnauthorized = (error: AxiosError) => {
  const url = String(error.config?.url || '');
  const isAuthenticationRequest = url.includes('/auth/login')
    || url.includes('/auth/refresh-token')
    || url.includes('/auth/register')
    || url.includes('/auth/reset-password');
  return error.response?.status === 401 && !isAuthenticationRequest;
};

const refreshAccessToken = async () => {
  const response = await axios.post(
    `${API_BASE_URL}/auth/refresh-token`,
    null,
    {
      withCredentials: true,
    }
  );

  const nextAccessToken = response.data?.accessToken;
  if (!nextAccessToken) {
    throw new Error('Invalid refresh token response');
  }

  accessToken = nextAccessToken;
  return nextAccessToken;
};

apiClient.interceptors.response.use(
  (response) => {
    const method = String(response.config.method || 'get').toLowerCase();
    if (!['get', 'head', 'options'].includes(method)) invalidateGetCache();
    return response;
  },
  async (error: AxiosError) => {
    const originalRequest = error.config as RetriableRequestConfig | undefined;

    if (!originalRequest || originalRequest._retry || !canRefreshAfterUnauthorized(error)) {
      return Promise.reject(error);
    }

    originalRequest._retry = true;

    try {
      if (!refreshPromise) {
        refreshPromise = refreshAccessToken().finally(() => {
          refreshPromise = null;
        });
      }

      const newAccessToken = await refreshPromise;
      originalRequest.headers = originalRequest.headers || {};
      originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
      return apiClient(originalRequest);
    } catch (refreshError) {
      clearAuthSession();
      if (typeof window !== 'undefined' && !originalRequest.skipAuthRedirect) {
        window.location.href = '/login';
      }
      return Promise.reject(refreshError);
    }
  }
);

export const authSession = {
  clear: clearAuthSession,
  setAccessToken: (token: string) => {
    accessToken = token;
  },
  /**
   * Khôi phục phiên từ refresh cookie HttpOnly và lấy user từ backend.
   * Trang public không redirect khi khách chưa có phiên đăng nhập.
   */
  getCurrentUser: async <T = unknown>(redirectOnFailure = false): Promise<T | null> => {
    try {
      const response = await cachedGet('/api/user/me', {
        ttlMs: 5_000,
        config: {
          skipAuthRedirect: !redirectOnFailure,
        } as AxiosRequestConfig & AuthAwareRequestConfig,
      });
      return (response.data?.data ?? null) as T | null;
    } catch {
      return null;
    }
  },
  isAuthenticated: async (): Promise<boolean> => {
    const currentUser = await authSession.getCurrentUser(false);
    return currentUser != null;
  },
};
