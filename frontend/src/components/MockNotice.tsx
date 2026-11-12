import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { RuntimeInfo } from '../api/types';

const MOCK_PROVIDERS = ['development-mock', 'fake'];

/** True when a BRD version was produced by a development provider, not by a language model. */
export function isMockProvider(provider: string | null | undefined): boolean {
  return !!provider && MOCK_PROVIDERS.includes(provider);
}

/** Shown on every BRD version that a development provider produced, so it is never read as real analysis. */
export function MockOutputNotice({ provider }: { provider: string | null | undefined }) {
  if (!isMockProvider(provider)) {
    return null;
  }
  return (
    <p role="note" className="mock-notice">
      <strong>Development mock output.</strong> This version was assembled by the provider “{provider}” from fixed
      rules. No language model analysed the requirement. Do not use it as a real analysis result.
    </p>
  );
}

/**
 * Site-wide banner while development adapters are configured. It renders nothing when the runtime information cannot
 * be loaded: the per-version notice above does not depend on it.
 */
export function DevelopmentAdaptersBanner() {
  const runtime = useQuery({
    queryKey: ['runtime'],
    queryFn: () => api.get<RuntimeInfo>('/api/v1/runtime'),
    staleTime: 5 * 60_000,
    retry: false,
  });
  const adapters = runtime.data?.developmentAdapters ?? [];
  if (adapters.length === 0) {
    return null;
  }
  const parts: string[] = [];
  if (adapters.includes('MODEL')) {
    parts.push(`analysis output comes from the mock provider “${runtime.data?.modelProvider}”, not from a language model`);
  }
  if (adapters.includes('REPOSITORY')) {
    parts.push('repositories are served from a bundled sample (owner “sdlc-dev”), not from GitHub');
  }
  return (
    <div role="status" className="mock-banner">
      <strong>Prototype mode.</strong> Development adapters are active: {parts.join('; ')}.
    </div>
  );
}
