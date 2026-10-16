import { useState } from 'react';
import DocumentList from './components/DocumentList.jsx';
import ChatPanel from './components/ChatPanel.jsx';

export default function App() {
  const [userId, setUserId] = useState(() => localStorage.getItem('kh-user') || 'alice');
  const [selected, setSelected] = useState([]);

  function changeUser(e) {
    const value = e.target.value.replace(/[^A-Za-z0-9._-]/g, '').slice(0, 64);
    setUserId(value);
    setSelected([]);
    localStorage.setItem('kh-user', value);
  }

  function toggle(id) {
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
  }

  return (
    <>
      <header>
        <strong>KnowledgeHub</strong>
        <label>
          User&nbsp;<input aria-label="User id" value={userId} onChange={changeUser} />
        </label>
      </header>
      {userId ? (
        <main key={userId}>
          <DocumentList userId={userId} selectedIds={selected} onToggleSelect={toggle} />
          <ChatPanel userId={userId} documentIds={selected} />
        </main>
      ) : (
        <p style={{ padding: 24 }}>Enter a user id to start.</p>
      )}
    </>
  );
}
