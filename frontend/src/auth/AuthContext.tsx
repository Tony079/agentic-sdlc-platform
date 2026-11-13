import { createContext, useContext, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { LoginResult, Me, MfaEnrolment } from '../api/types';

export type LoginOutcome = { kind: 'signed-in'; me: Me } | { kind: 'mfa'; enrolled: boolean };

interface AuthState {
  me: Me | null;
  loading: boolean;
  isOrgAdmin: boolean;
  login: (organisation: string, email: string, password: string) => Promise<LoginOutcome>;
  startEnrolment: () => Promise<MfaEnrolment>;
  confirmEnrolment: (code: string) => Promise<Me>;
  verifyMfa: (code: string) => Promise<Me>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

const ME_KEY = ['me'];

function isMe(result: LoginResult): result is Me {
  return (result as Me).userId !== undefined;
}

/**
 * Session state from GET /api/v1/me. A 401 simply means "not signed in". Organisation Admins complete sign-in with a
 * TOTP code (Garuda GD-08); until then the session reaches nothing but the MFA endpoints.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  const meQuery = useQuery({
    queryKey: ME_KEY,
    queryFn: async () => {
      try {
        return await api.get<Me>('/api/v1/me');
      } catch (error) {
        if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
          return null;
        }
        throw error;
      }
    },
    retry: false,
  });

  const signedIn = (me: Me) => {
    queryClient.setQueryData(ME_KEY, me);
    return me;
  };

  const logoutMutation = useMutation({
    mutationFn: () => api.post<void>('/api/v1/auth/logout'),
    onSettled: () => {
      queryClient.clear();
      queryClient.setQueryData(ME_KEY, null);
    },
  });

  const me = meQuery.data ?? null;
  const value: AuthState = {
    me,
    loading: meQuery.isPending,
    isOrgAdmin: !!me && Array.isArray(me.roles) && me.roles.includes('ORG_ADMIN'),
    login: async (organisation, email, password) => {
      const result = await api.post<LoginResult>('/api/v1/auth/login', { organisation, email, password });
      if (isMe(result)) {
        return { kind: 'signed-in', me: signedIn(result) };
      }
      return { kind: 'mfa', enrolled: result.status === 'MFA_REQUIRED' };
    },
    startEnrolment: () => api.post<MfaEnrolment>('/api/v1/auth/mfa/enrolment'),
    confirmEnrolment: async (code) => signedIn(await api.post<Me>('/api/v1/auth/mfa/enrolment/confirm', { code })),
    verifyMfa: async (code) => signedIn(await api.post<Me>('/api/v1/auth/mfa/verify', { code })),
    logout: () => logoutMutation.mutateAsync(),
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
