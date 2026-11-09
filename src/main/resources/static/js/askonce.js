// Shared helpers for the AskOnce pages.
const AskOnce = (() => {
  const BOARD_PATTERN = /^[a-z0-9][a-z0-9-]{0,39}$/;

  async function request(method, path, body) {
    const response = await fetch(path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) {
      throw new Error(`${method} ${path} failed with ${response.status}`);
    }
    return response.status === 204 ? null : response.json();
  }

  function boardFromUrl() {
    const board = new URLSearchParams(location.search).get('board') || '';
    return BOARD_PATTERN.test(board) ? board : null;
  }

  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
      if (key === 'class') node.className = value;
      else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
      else node.setAttribute(key, value);
    }
    for (const child of children) {
      if (child != null) node.append(child);
    }
    return node;
  }

  return {
    BOARD_PATTERN,
    boardFromUrl,
    el,
    getBoard: (board) => request('GET', `/api/boards/${board}`),
    setTopic: (board, topic) => request('PUT', `/api/boards/${board}`, { topic }),
    submit: (board, text) => request('POST', `/api/boards/${board}/questions`, { text }),
    top: (board, limit = 50) => request('GET', `/api/boards/${board}/questions?limit=${limit}`),
    upvote: (id) => request('POST', `/api/questions/${id}/upvote`),
  };
})();
