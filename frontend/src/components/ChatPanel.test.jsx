import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ChatPanel from './ChatPanel.jsx';
import { streamResponse, jsonResponse } from '../test-utils.js';

afterEach(() => vi.restoreAllMocks());

async function ask(text = 'How much leave?') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Question'), text);
  await user.click(screen.getByRole('button', { name: 'Ask' }));
  return user;
}

describe('ChatPanel', () => {
  it('streams tokens split across chunks, sends user header and finishes on done', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((url, opts) => Promise.resolve(streamResponse([
      'event: sources\ndata: [{"documentId":"abcdef123456","chunkIndex":0}]\n\nevent: tok',
      'en\ndata: {"t":"Twenty "}\n\nevent: token\ndata: {"t":"da',
      'ys."}\n\nevent: done\ndata: {}\n\n',
    ], { signal: opts.signal })));
    render(<ChatPanel userId="alice" documentIds={['d1']} />);
    await ask();

    await waitFor(() => expect(screen.getByTestId('answer')).toHaveTextContent('Twenty days.'));
    await waitFor(() => expect(screen.getByLabelText('Question')).not.toBeDisabled());
    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/chat/stream');
    expect(opts.headers['X-User-Id']).toBe('alice');
    expect(JSON.parse(opts.body)).toEqual({ question: 'How much leave?', documentIds: ['d1'] });
    expect(screen.getByText(/abcdef12 #0/)).toBeInTheDocument();
  });

  it('disables input while streaming and Stop aborts the request', async () => {
    let signal;
    vi.spyOn(globalThis, 'fetch').mockImplementation((url, opts) => {
      signal = opts.signal;
      return Promise.resolve(streamResponse(['event: token\ndata: {"t":"partial"}\n\n'], { signal, hang: true }));
    });
    render(<ChatPanel userId="alice" />);
    const user = await ask();

    await waitFor(() => expect(screen.getByTestId('answer')).toHaveTextContent('partial'));
    expect(screen.getByLabelText('Question')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Stop' }));

    expect(signal.aborted).toBe(true);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Stopped'));
    expect(screen.getByTestId('answer')).toHaveTextContent('partial');
    expect(screen.getByLabelText('Question')).not.toBeDisabled();
  });

  it('shows server error events', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((u, o) => Promise.resolve(streamResponse([
      'event: error\ndata: {"message":"The AI service is busy"}\n\n',
    ], { signal: o.signal })));
    render(<ChatPanel userId="alice" />);
    await ask();
    expect(await screen.findByRole('alert')).toHaveTextContent('The AI service is busy');
  });

  it('shows HTTP errors (e.g. 503) without crashing', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ detail: 'Server is busy' }, 503));
    render(<ChatPanel userId="alice" />);
    await ask();
    expect(await screen.findByRole('alert')).toHaveTextContent('Server is busy');
  });

  it('aborts the stream on unmount', async () => {
    let signal;
    vi.spyOn(globalThis, 'fetch').mockImplementation((u, o) => {
      signal = o.signal;
      return Promise.resolve(streamResponse([], { signal, hang: true }));
    });
    const { unmount } = render(<ChatPanel userId="alice" />);
    await ask();
    await waitFor(() => expect(signal).toBeDefined());
    unmount();
    expect(signal.aborted).toBe(true);
  });
});
