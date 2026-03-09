import axios from 'axios';

/**
 * Singleton Axios instance for all API calls.
 * Base URL is /api/v1 — in production, nginx proxies this to the backend.
 * In development, vite.config.ts proxies /api to localhost:8080.
 */
const apiClient = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

/**
 * Token injector — set by configureAxiosAuth() called from AuthProvider.
 * Using a module-level ref avoids circular imports while keeping the token
 * out of localStorage (it stays in React state).
 */
let getToken: (() => string | null) | null = null;
let onUnauthorized: (() => void) | null = null;

export function configureAxiosAuth(
  tokenGetter: () => string | null,
  unauthorizedHandler: () => void,
) {
  getToken = tokenGetter;
  onUnauthorized = unauthorizedHandler;
}

// Request interceptor: attach Bearer token when available
apiClient.interceptors.request.use((config) => {
  const token = getToken?.();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor: on 401, trigger logout + redirect
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      onUnauthorized?.();
    }
    return Promise.reject(error);
  },
);

export default apiClient;
