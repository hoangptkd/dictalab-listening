// These standalone quizzes do not yet have a persisted server-side grading session.
(() => {
    const note = document.createElement('p');
    note.className = 'free-practice-note';
    note.setAttribute('role', 'status');
    note.textContent = 'Luyện tự do — kết quả câu hỏi chưa được lưu vào lịch sử. Nội dung đã nghe được ghi nhận khi bạn đăng nhập.';
    const container = document.querySelector('main') || document.body;
    container.prepend(note);
    const recorded = new Set();
    document.addEventListener('play', async event => {
        const audio = event.target;
        if (audio.tagName !== 'AUDIO') return;
        const url = new URL(audio.dataset.src || audio.currentSrc || audio.src, location.href);
        if (url.origin !== location.origin || recorded.has(url.pathname)) return;
        recorded.add(url.pathname);
        try {
            const csrf = await fetch('/api/auth/csrf', {credentials: 'same-origin'});
            if (!csrf.ok) throw Error('csrf');
            const token = await csrf.json();
            const result = await fetch('/api/v1/catalog/preview-exposure', {
                method: 'POST', credentials: 'same-origin',
                headers: {'Content-Type': 'application/json', [token.headerName]: token.token},
                body: JSON.stringify({audioUrl: url.pathname})
            });
            if (!result.ok) throw Error('exposure');
        } catch (_) {
            recorded.delete(url.pathname);
            note.textContent = 'Luyện tự do — kết quả chưa được lưu. Chưa ghi nhận được nội dung đã nghe; hãy kiểm tra kết nối và đăng nhập.';
        }
    }, true);
})();
