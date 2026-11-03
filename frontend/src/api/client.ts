import type { ErrorEnvelope, FieldError } from './types';

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-CSRF-Token';

/** An API error carrying the server's stable error code and field details (TDD 20.1 envelope). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: FieldError[];

  constructor(status: number, code: string, message: string, details: FieldError[] = []) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

function readCookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((part) => part.startsWith(`${name}=`))
    ?.substring(name.length + 1);
}

async function ensureCsrfToken(): Promise<string> {
  const existing = readCookie(CSRF_COOKIE);
  if (existing) {
    return existing;
  }
  await request('GET', '/api/v1/auth/csrf');
  const issued = readCookie(CSRF_COOKIE);
  if (!issued) {
    throw new ApiError(0, 'CSRF_UNAVAILABLE', 'Could not obtain a security token. Please reload the page.');
  }
  return issued;
}

async function request<T>(method: string, path: string, body?: unknown, extraHeaders: Record<string, string> = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extraHeaders };
  if (method !== 'GET') {
    headers[CSRF_HEADER] = await ensureCsrfToken();
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const data: unknown = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    const envelope = data as ErrorEnvelope | undefined;
    throw new ApiError(
      response.status,
      envelope?.error?.code ?? 'UNKNOWN_ERROR',
      envelope?.error?.message ?? `Request failed (${response.status}).`,
      envelope?.error?.details ?? [],
    );
  }
  return data as T;
}

/** If-Match value for optimistic locking: the version from the last response. */
export function ifMatch(version: number): Record<string, string> {
  return { 'If-Match': `"${version}"` };
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) => request<T>('POST', path, body, headers),
  put: <T>(path: string, body: unknown, headers?: Record<string, string>) => request<T>('PUT', path, body, headers),
};
