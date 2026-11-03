import { createContext, useContext, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Me } from '../api/types';

interface AuthState {
  me: Me | null;
  loading: boolean;
  login: (organisation: string, email: string, password: string) => Promise<Me>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

const ME_KEY = ['me'];

/** Session state from GET /api/v1/me. A 401 simply means "not signed in". */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  const meQuery = useQuery({
    queryKey: ME_KEY,
    queryFn: async () => {
      try {
        return await api.get<Me>('/api/v1/me');
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          return null;
        }
        throw error;
      }
    },
    retry: false,
  });

  const loginMutation = useMutation({
    mutationFn: (input: { organisation: string; email: string; password: string }) =>
      api.post<Me>('/api/v1/auth/login', input),
    onSuccess: (me) => queryClient.setQueryData(ME_KEY, me),
  });

  const logoutMutation = useMutation({
    mutationFn: () => api.post<void>('/api/v1/auth/logout'),
    onSettled: () => {
      queryClient.clear();
      queryClient.setQueryData(ME_KEY, null);
    },
  });

  const value: AuthState = {
    me: meQuery.data ?? null,
    loading: meQuery.isPending,
    login: (organisation, email, password) => loginMutation.mutateAsync({ organisation, email, password }),
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
