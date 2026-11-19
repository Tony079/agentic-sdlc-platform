import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Card } from '../ui/components';
import { BrdItems } from '../components/BrdItems';
import type { BrdItem } from '../api/types';

const item = (key: string, section: string, statement: string): BrdItem => ({
  itemKey: key, ordinal: 1, kind: 'RECOMMENDATION', proposedKind: 'RECOMMENDATION', section, statement, risk: null,
  component: null, supportState: 'UNSUPPORTED', rejectedCitations: 0, acknowledged: false, evidence: [],
});

describe('Foldable sections', () => {
  it('folds a card from its header, keeps its actions visible, and remembers the choice', async () => {
    window.localStorage.removeItem('fold:test:card');
    const user = userEvent.setup();
    const { unmount } = render(
      <Card title="Requirement" foldKey="fold:test:card" actions={<span>Badge</span>}>
        <p>Body text</p>
      </Card>,
    );
    const toggle = screen.getByRole('button', { name: 'Requirement' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Body text')).not.toBeInTheDocument();
    expect(screen.getByText('Badge')).toBeInTheDocument();
    unmount();
    render(<Card title="Requirement" foldKey="fold:test:card"><p>Body text</p></Card>);
    expect(screen.queryByText('Body text')).not.toBeInTheDocument();
  });

  it('folds BRD sections one by one or all at once', async () => {
    const user = userEvent.setup();
    render(<BrdItems items={[item('a', 'SCENARIOS', 'A candidate uploads a resume.'),
      item('b', 'ACCEPTANCE_CRITERIA', 'The site shows every section.')]} />);
    await user.click(screen.getByRole('button', { name: /Scenarios/ }));
    expect(screen.queryByText('A candidate uploads a resume.')).not.toBeInTheDocument();
    expect(screen.getByText('The site shows every section.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Collapse all sections' }));
    expect(screen.queryByText('The site shows every section.')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Expand all sections' }));
    expect(screen.getByText('A candidate uploads a resume.')).toBeInTheDocument();
  });
});
