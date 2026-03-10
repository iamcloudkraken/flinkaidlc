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
 * Module-level auth state — set synchronously by setAuthToken() called from
 * AuthContext login/logout. Keeping the token here (not in localStorage) prevents
 * XSS theft via storage APIs.
 */
let authToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setAuthToken(token: string | null): void {
  authToken = token;
}

export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler;
}

// Request interceptor: attach Bearer token when available
apiClient.interceptors.request.use((config) => {
  if (authToken) {
    config.headers.set('Authorization', `Bearer ${authToken}`);
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
