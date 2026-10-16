import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DocumentList from './DocumentList.jsx';
import { jsonResponse } from '../test-utils.js';

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('DocumentList', () => {
  it('renders titles as text (no XSS) with the user header', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse([
      { id: '1', title: '<img src=x onerror=alert(1)>Policy', status: 'READY' },
    ]));
    const { container } = render(<DocumentList userId="alice" />);
    expect(await screen.findByText('<img src=x onerror=alert(1)>Policy')).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
    expect(fetchMock.mock.calls[0][1].headers['X-User-Id']).toBe('alice');
  });

  it('encodes the query and does not loop or append on re-render', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse([{ id: '1', title: 'A', status: 'READY' }]));
    render(<DocumentList userId="alice" />);
    await screen.findByText('A');
    await userEvent.setup().type(screen.getByLabelText('Search documents'), 'a&b=c');
    await waitFor(() => expect(fetchMock.mock.calls.at(-1)[0]).toBe('/api/documents?q=a%26b%3Dc'));
    const calls = fetchMock.mock.calls.length;
    await new Promise((r) => setTimeout(r, 400));
    expect(fetchMock.mock.calls.length).toBe(calls); // no infinite effect loop
    expect(screen.getAllByText('A')).toHaveLength(1); // replaced, not appended
  });

  it('ignores a stale response that arrives after a newer one', async () => {
    let resolveSlow;
    vi.spyOn(globalThis, 'fetch').mockImplementation((url, opts) => {
      if (url.endsWith('q=')) {
        return new Promise((resolve, reject) => {
          resolveSlow = () => resolve(jsonResponse([{ id: 'old', title: 'Stale', status: 'READY' }]));
          opts.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
        });
      }
      return Promise.resolve(jsonResponse([{ id: 'new', title: 'Fresh', status: 'READY' }]));
    });
    render(<DocumentList userId="alice" />);
    await userEvent.setup().type(screen.getByLabelText('Search documents'), 'f');
    expect(await screen.findByText('Fresh')).toBeInTheDocument();
    act(() => resolveSlow());
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('Stale')).toBeNull();
  });

  it('polls while documents are pending and clears the interval on unmount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const clearSpy = vi.spyOn(globalThis, 'clearInterval');
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse([{ id: '1', title: 'P', status: 'PENDING' }]));
    const { unmount } = render(<DocumentList userId="alice" />);
    await screen.findByText('P');
    const before = globalThis.fetch.mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(3100); });
    expect(globalThis.fetch.mock.calls.length).toBeGreaterThan(before);
    unmount();
    expect(clearSpy).toHaveBeenCalled();
  });
});
