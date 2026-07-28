import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  apiClient,
  authSession,
  cachedGet,
  getApiErrorMessage,
  getApiValidationErrors,
  invalidateGetCache,
} from './api';

const axiosError = (status: number, data: unknown, configUrl = '/api/test') => {
  const config = {
    url: configUrl,
    method: 'get',
    headers: {},
  } as InternalAxiosRequestConfig;
  return new AxiosError(
    `HTTP ${status}`,
    'ERR_BAD_RESPONSE',
    config,
    undefined,
    {
      data,
      status,
      statusText: 'Error',
      headers: {},
      config,
    },
  );
};

const okResponse = <T>(
  config: InternalAxiosRequestConfig,
  data: T,
): AxiosResponse<T> => ({
  data,
  status: 200,
  statusText: 'OK',
  headers: {},
  config,
});

describe('API error normalization', () => {
  it('prefers validated field errors over a generic backend message', () => {
    const error = axiosError(400, {
      message: 'Dữ liệu không hợp lệ',
      errors: ['Số điện thoại không hợp lệ', '', 'Số giấy tờ đã tồn tại'],
    });

    expect(getApiValidationErrors(error)).toEqual([
      'Số điện thoại không hợp lệ',
      'Số giấy tờ đã tồn tại',
    ]);
    expect(getApiErrorMessage(error, 'Fallback')).toBe(
      'Số điện thoại không hợp lệ · Số giấy tờ đã tồn tại',
    );
  });

  it('uses nested backend messages and finally the caller fallback', () => {
    expect(getApiErrorMessage(
      axiosError(409, { data: { message: 'Phòng đã được gán' } }),
      'Fallback',
    )).toBe('Phòng đã được gán');
    expect(getApiErrorMessage(axiosError(500, {}), 'Fallback')).toBe('Fallback');
    expect(getApiErrorMessage(new Error('Network down'), 'Fallback')).toBe('Network down');
  });
});

describe('cachedGet', () => {
  beforeEach(() => invalidateGetCache());
  afterEach(() => invalidateGetCache());

  it('deduplicates concurrent reads, serves the TTL cache and refetches after invalidation', async () => {
    const response = {
      data: { value: 1 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: { headers: {} },
    } as AxiosResponse<{ value: number }>;
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue(response);

    const first = cachedGet<{ value: number }>('/api/dashboard', { ttlMs: 10_000 });
    const duplicate = cachedGet<{ value: number }>('/api/dashboard', { ttlMs: 10_000 });

    await expect(first).resolves.toBe(response);
    await expect(duplicate).resolves.toBe(response);
    await expect(cachedGet('/api/dashboard', { ttlMs: 10_000 })).resolves.toBe(response);
    expect(get).toHaveBeenCalledTimes(1);

    invalidateGetCache('/api/dashboard');
    await cachedGet('/api/dashboard', { ttlMs: 10_000 });
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('uses a stable key for the same query parameters in a different order', async () => {
    const response = {
      data: [],
      status: 200,
      statusText: 'OK',
      headers: {},
      config: { headers: {} },
    } as unknown as AxiosResponse<unknown[]>;
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue(response);

    await cachedGet('/api/reservations', {
      config: { params: { status: 'CONFIRMED', page: 0 } },
    });
    await cachedGet('/api/reservations', {
      config: { params: { page: 0, status: 'CONFIRMED' } },
    });

    expect(get).toHaveBeenCalledTimes(1);
  });
});

describe('authentication response interceptor', () => {
  const originalAdapter = apiClient.defaults.adapter;

  beforeEach(() => {
    authSession.clear();
    invalidateGetCache();
  });

  afterEach(() => {
    apiClient.defaults.adapter = originalAdapter;
    authSession.clear();
    invalidateGetCache();
    vi.restoreAllMocks();
  });

  it('coalesces concurrent 401 responses into one refresh and retries both requests', async () => {
    const refresh = vi.spyOn(axios, 'post').mockResolvedValue({
      data: { accessToken: 'refreshed-access' },
    });
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      const authorization = String(config.headers?.Authorization || '');
      if (authorization !== 'Bearer refreshed-access') {
        throw axiosError(401, { message: 'Unauthorized' }, String(config.url));
      }
      return okResponse(config, { path: config.url });
    });
    apiClient.defaults.adapter = adapter;

    const [first, second] = await Promise.all([
      apiClient.get('/api/reservations/my'),
      apiClient.get('/api/reviews/my'),
    ]);

    expect(first.data).toEqual({ path: '/api/reservations/my' });
    expect(second.data).toEqual({ path: '/api/reviews/my' });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(adapter).toHaveBeenCalledTimes(4);
  });

  it('does not try to refresh a rejected login request', async () => {
    const refresh = vi.spyOn(axios, 'post');
    apiClient.defaults.adapter = async (config) => {
      throw axiosError(401, { message: 'Unauthorized' }, String(config.url));
    };

    await expect(apiClient.post('/auth/login', {
      username: 'wrong',
      password: 'wrong',
    })).rejects.toMatchObject({ response: { status: 401 } });
    expect(refresh).not.toHaveBeenCalled();
  });
});
