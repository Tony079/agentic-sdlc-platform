import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DocumentsPanel } from '../components/DocumentsPanel';
import { mockFetch, renderWithProviders } from './render';

const items = [
  {
    documentId: 'd1', versionId: 'v1', versionNo: 1, fileName: 'spec.pdf', type: 'PDF', sizeBytes: 2048,
    state: 'READY_FOR_EXTRACTION', reasonCode: null, uploadedBy: 'u1', uploadedAt: '2026-11-04T10:00:00Z', downloadable: true,
  },
  {
    documentId: 'd2', versionId: 'v2', versionNo: 1, fileName: 'invoice.txt', type: 'TXT', sizeBytes: 100,
    state: 'REJECTED_MALWARE', reasonCode: 'MALWARE_DETECTED', uploadedBy: 'u1', uploadedAt: '2026-11-04T10:01:00Z',
    downloadable: false,
  },
  {
    documentId: 'd3', versionId: 'v3', versionNo: 1, fileName: 'notes.md', type: 'MARKDOWN', sizeBytes: 100,
    state: 'QUARANTINED', reasonCode: null, uploadedBy: 'u1', uploadedAt: '2026-11-04T10:02:00Z', downloadable: false,
  },
];
const list = { items, limits: { maxFileBytes: 20971520, maxFiles: 10, maxTotalBytes: 104857600 } };

describe('DocumentsPanel', () => {
  it('shows processing states and links only scanned files', async () => {
    mockFetch(() => ({ status: 200, body: list }));
    renderWithProviders(<DocumentsPanel requirementId="r1" requirementState="SUBMITTED" />);
    expect(await screen.findByRole('link', { name: 'spec.pdf' })).toHaveAttribute('href', '/api/v1/requirements/r1/documents/d1/content');
    expect(screen.queryByRole('link', { name: 'invoice.txt' })).not.toBeInTheDocument();
    expect(screen.getByText('Rejected: security threat detected')).toBeInTheDocument();
    expect(screen.getByText('(The file was blocked by the malware scan.)')).toBeInTheDocument();
    expect(screen.getByText('Waiting for security scan')).toBeInTheDocument();
    expect(screen.getByLabelText('Add files')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
  });

  it('allows removal only in draft and sends DELETE with the CSRF token', async () => {
    document.cookie = 'XSRF-TOKEN=t9; path=/';
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const calls = mockFetch((_url, init) => (init.method === 'DELETE' ? { status: 204 } : { status: 200, body: list }));
    renderWithProviders(<DocumentsPanel requirementId="r1" requirementState="DRAFT" />);
    const user = userEvent.setup();
    const buttons = await screen.findAllByRole('button', { name: 'Remove' });
    await user.click(buttons[0]!);
    const del = calls.find((c) => c.init.method === 'DELETE');
    expect(del?.url).toBe('/api/v1/requirements/r1/documents/d1');
    expect((del?.init.headers as Record<string, string>)['X-CSRF-Token']).toBe('t9');
  });

  it('hides the upload control once the requirement is cancelled', async () => {
    mockFetch(() => ({ status: 200, body: { ...list, items: [] } }));
    renderWithProviders(<DocumentsPanel requirementId="r1" requirementState="CANCELLED" />);
    expect(await screen.findByText('No attachments yet.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Add files')).not.toBeInTheDocument();
  });
});
