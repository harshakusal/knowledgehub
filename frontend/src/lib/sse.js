/**
 * Incremental Server-Sent-Events parser. Network chunks can split an event (or even a line, or a multi-byte
 * character — the caller uses TextDecoder with {stream:true} for that) anywhere, so we buffer until a blank line.
 */
export function createSseParser(onEvent) {
  let buffer = '';

  function dispatch(block) {
    let event = 'message';
    const data = [];
    for (const line of block.split('\n')) {
      if (line === '' || line.startsWith(':')) continue;
      const idx = line.indexOf(':');
      const field = idx === -1 ? line : line.slice(0, idx);
      let value = idx === -1 ? '' : line.slice(idx + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') event = value;
      else if (field === 'data') data.push(value);
    }
    if (data.length > 0 || event !== 'message') onEvent({ event, data: data.join('\n') });
  }

  return {
    feed(text) {
      buffer += text.replace(/\r\n?/g, '\n');
      let sep;
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        dispatch(block);
      }
    },
    flush() {
      if (buffer.trim()) dispatch(buffer);
      buffer = '';
    },
  };
}
