import { useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '../auth/AuthContext';

interface LocationState {
  from?: { pathname: string };
}

/**
 * LoginPage
 *
 * Authenticates the user against a Keycloak token endpoint.
 * The token endpoint URL is configured via the env var VITE_TOKEN_ENDPOINT.
 * Default: /realms/flink/protocol/openid-connect/token
 *
 * SECURITY: The JWT is stored exclusively in React in-memory state (AuthContext).
 * It is NEVER written to localStorage, sessionStorage, or any cookie.
 *
 * TODO: Replace VITE_TOKEN_ENDPOINT default with your actual Keycloak realm URL
 *       e.g. https://auth.example.com/realms/flink/protocol/openid-connect/token
 */
export default function LoginPage() {
  const { isAuthenticated, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state as LocationState | null;
  const from = state?.from?.pathname ?? '/dashboard';

  const [tenantId, setTenantId] = useState('');
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Already authenticated — redirect immediately
  useEffect(() => {
    if (isAuthenticated) {
      navigate('/dashboard', { replace: true });
    }
  }, [isAuthenticated, navigate]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!tenantId.trim() || !clientId.trim() || !clientSecret.trim()) {
      setError('All fields are required.');
      return;
    }

    setLoading(true);
    try {
      // Replace with real token endpoint
      const tokenEndpoint =
        import.meta.env.VITE_TOKEN_ENDPOINT ??
        '/realms/flink/protocol/openid-connect/token';

      const params = new URLSearchParams({
        grant_type: 'client_credentials',
        client_id: clientId,
        client_secret: clientSecret,
        scope: 'openid',
      });

      const response = await axios.post<{ access_token: string }>(
        tokenEndpoint,
        params,
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } },
      );

      const token = response.data.access_token;
      if (!token) {
        setError('Invalid response from authentication server.');
        return;
      }

      login(token, tenantId.trim());
      navigate(from, { replace: true });
    } catch (err: unknown) {
      if (axios.isAxiosError(err)) {
        const msg =
          (err.response?.data as { error_description?: string })
            ?.error_description ?? err.message;
        setError(String(msg));
      } else {
        setError('Authentication failed. Please check your credentials.');
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="max-w-md mx-auto mt-16">
      <div className="bg-white shadow-md rounded-lg p-8">
        <h1 className="text-2xl font-bold text-gray-800 mb-6">Sign In</h1>

        {error && (
          <div
            role="alert"
            className="mb-4 p-3 bg-red-50 border border-red-200 rounded text-red-700 text-sm"
          >
            {/* Error text is rendered as text content, not HTML — no XSS risk */}
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <div className="mb-4">
            <label
              htmlFor="tenantId"
              className="block text-sm font-medium text-gray-700 mb-1"
            >
              Tenant ID (UUID)
            </label>
            <input
              id="tenantId"
              type="text"
              autoComplete="off"
              placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
              className="w-full border border-gray-300 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <div className="mb-4">
            <label
              htmlFor="clientId"
              className="block text-sm font-medium text-gray-700 mb-1"
            >
              Client ID (FID)
            </label>
            <input
              id="clientId"
              type="text"
              autoComplete="username"
              value={clientId}
              onChange={(e) => setClientId(e.target.value)}
              className="w-full border border-gray-300 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <div className="mb-6">
            <label
              htmlFor="clientSecret"
              className="block text-sm font-medium text-gray-700 mb-1"
            >
              Client Secret
            </label>
            <input
              id="clientSecret"
              type="password"
              autoComplete="current-password"
              value={clientSecret}
              onChange={(e) => setClientSecret(e.target.value)}
              className="w-full border border-gray-300 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-400 text-white font-medium py-2 px-4 rounded transition-colors"
          >
            {loading ? 'Signing in…' : 'Sign In'}
          </button>
        </form>

        <p className="mt-4 text-center text-sm text-gray-500">
          No account?{' '}
          <a href="/register" className="text-indigo-600 hover:underline">
            Register a tenant
          </a>
        </p>
      </div>
    </div>
  );
}
