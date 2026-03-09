import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

export interface AuthContextType {
  token: string | null;
  tenantId: string | null;
  login: (token: string, tenantId: string) => void;
  logout: () => void;
  isAuthenticated: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

/**
 * AuthProvider stores the JWT in React state ONLY.
 * The token is NEVER written to localStorage, sessionStorage, or cookies.
 * This prevents XSS attacks from stealing the token via storage APIs.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  // Token is held exclusively in React state — not persisted anywhere.
  const [token, setToken] = useState<string | null>(null);
  const [tenantId, setTenantId] = useState<string | null>(null);

  const login = useCallback((newToken: string, newTenantId: string) => {
    setToken(newToken);
    setTenantId(newTenantId);
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setTenantId(null);
  }, []);

  const value = useMemo<AuthContextType>(
    () => ({
      token,
      tenantId,
      login,
      logout,
      isAuthenticated: token !== null,
    }),
    [token, tenantId, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextType {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
