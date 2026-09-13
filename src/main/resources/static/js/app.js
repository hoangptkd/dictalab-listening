(() => {
    "use strict";

    const state = {
        currentUser: null,
        csrfHeader: "X-XSRF-TOKEN",
        csrfToken: null,
        levels: [],
        lessons: [],
        selectedLevel: "A1",
        session: null,
        sentence: null,
        activeWordIndex: 0,
        sentenceStartedAt: null,
        timerId: null,
        timerSeconds: 0,
        speaking: false,
        activeAudio: null,
        audioPlaybackGeneration: 0,
        audioContext: null,
        audioBufferCache: new Map(),
        checkingWords: new Set(),
        wordDrafts: new Map(),
        lastResult: null,
        reviewAudioDurations: new Map()
    };

    const outcomeLabels = {
        FIRST_ATTEMPT: "Đúng ngay lần 1",
        SECOND_ATTEMPT: "Đúng ở lần 2",
        ATTEMPTS_3_TO_6: "Đúng trong lần 3–6",
        ATTEMPTS_7_PLUS: "Đúng từ lần 7 trở đi",
        FIRST_LETTER_HINT: "Đúng sau gợi ý chữ đầu",
        WORD_REVEAL: "Đã hiện riêng từ này",
        SENTENCE_REVEAL: "Đã dùng gợi ý cả câu"
    };

    const elements = {
        authScreen: document.querySelector("#auth-screen"),
        appShell: document.querySelector("#app-shell"),
        authHeading: document.querySelector("#auth-heading"),
        authSubtitle: document.querySelector("#auth-subtitle"),
        loginTab: document.querySelector("#login-tab"),
        registerTab: document.querySelector("#register-tab"),
        loginForm: document.querySelector("#login-form"),
        registerForm: document.querySelector("#register-form"),
        authStatus: document.querySelector("#auth-status"),
        accountName: document.querySelector("#account-name"),
        accountEmail: document.querySelector("#account-email"),
        logoutButton: document.querySelector("#logout-button"),
        views: [...document.querySelectorAll(".view")],
        routeButtons: [...document.querySelectorAll("[data-route]")],
        navLinks: [...document.querySelectorAll(".nav-link")],
        levelTabs: document.querySelector("#level-tabs"),
        levelDescription: document.querySelector("#level-description"),
        lessonGrid: document.querySelector("#lesson-grid"),
        lessonCount: document.querySelector("#lesson-count"),
        headerSessionCount: document.querySelector("#header-session-count"),
        leavePractice: document.querySelector("#leave-practice"),
        practiceLevel: document.querySelector("#practice-level"),
        practiceTitle: document.querySelector("#practice-title"),
        sentencePosition: document.querySelector("#sentence-position"),
        sentenceTotal: document.querySelector("#sentence-total"),
        sentenceProgress: document.querySelector("#sentence-progress"),
        replayButton: document.querySelector("#replay-button"),
        listeningConsole: document.querySelector(".listening-console"),
        replayCount: document.querySelector("#replay-count"),
        sentenceTimer: document.querySelector("#sentence-timer"),
        hintCount: document.querySelector("#hint-count"),
        speechSupportNote: document.querySelector("#speech-support-note"),
        wordCompletion: document.querySelector("#word-completion"),
        wordSlots: document.querySelector("#word-slots"),
        dictationForm: document.querySelector("#dictation-form"),
        hintLetter: document.querySelector("#hint-letter"),
        hintWord: document.querySelector("#hint-word"),
        revealSentence: document.querySelector("#reveal-sentence"),
        feedbackPanel: document.querySelector("#feedback-panel"),
        classifications: document.querySelector("#error-classifications"),
        practiceStatus: document.querySelector("#practice-status"),
        nextRequirement: document.querySelector("#next-requirement"),
        nextSentence: document.querySelector("#next-sentence"),
        toast: document.querySelector("#toast"),
        dashboardSummary: document.querySelector("#dashboard-summary"),
        trendChart: document.querySelector("#trend-chart"),
        levelProgress: document.querySelector("#level-progress"),
        errorBreakdown: document.querySelector("#error-breakdown"),
        recommendationCard: document.querySelector("#recommendation-card"),
        reviewLessonMeta: document.querySelector("#review-lesson-meta"),
        reviewAudioMetrics: document.querySelector("#review-audio-metrics"),
        reviewOutcomeSummary: document.querySelector("#review-outcome-summary"),
        reviewSentences: document.querySelector("#review-sentences"),
        analysisReportPreview: document.querySelector("#analysis-report-preview"),
        copyAnalysisReport: document.querySelector("#copy-analysis-report"),
        copyReportStatus: document.querySelector("#copy-report-status")
    };

    document.addEventListener("DOMContentLoaded", init);

    async function init() {
        bindEvents();
        try {
            await refreshCsrf();
            state.currentUser = await api("/api/auth/me", {redirectOnUnauthorized: false});
            await enterApplication();
        } catch (error) {
            showAuthScreen(error.status === 401 ? "" : error.message);
        }
    }

    function bindEvents() {
        elements.loginTab.addEventListener("click", () => switchAuthMode("login"));
        elements.registerTab.addEventListener("click", () => switchAuthMode("register"));
        [elements.loginTab, elements.registerTab].forEach((tab, index, tabs) => {
            tab.addEventListener("keydown", event => {
                if (!["ArrowLeft", "ArrowRight"].includes(event.key)) return;
                event.preventDefault();
                const next = tabs[index === 0 ? 1 : 0];
                switchAuthMode(next === elements.loginTab ? "login" : "register");
                next.focus();
            });
        });
        elements.loginForm.addEventListener("submit", event => submitAuth(event, "login"));
        elements.registerForm.addEventListener("submit", event => submitAuth(event, "register"));
        elements.logoutButton.addEventListener("click", logout);
        elements.routeButtons.forEach(button => {
            button.addEventListener("click", () => navigate(button.dataset.route));
        });
        elements.leavePractice.addEventListener("click", () => navigate("catalog"));
        elements.levelTabs.addEventListener("click", event => {
            const button = event.target.closest("[data-level]");
            if (button) {
                selectLevel(button.dataset.level);
            }
        });
        elements.lessonGrid.addEventListener("click", event => {
            const button = event.target.closest("[data-lesson-id]");
            if (button) {
                startLesson(Number(button.dataset.lessonId));
            }
        });
        elements.replayButton.addEventListener("click", playSentence);
        elements.wordSlots.addEventListener("focusin", onWordFocus);
        elements.wordSlots.addEventListener("input", onWordInput);
        elements.wordSlots.addEventListener("keydown", onWordKeydown);
        elements.wordSlots.addEventListener("focusout", onWordBlur);
        elements.wordSlots.addEventListener("paste", onSentencePaste);
        elements.hintLetter.addEventListener("click", () => useHint("FIRST_LETTER"));
        elements.hintWord.addEventListener("click", () => useHint("REVEAL_WORD"));
        elements.revealSentence.addEventListener("click", () => useHint("REVEAL_SENTENCE"));
        elements.classifications.addEventListener("click", onClassifyWord);
        elements.dictationForm.addEventListener("submit", completeSentence);
        elements.reviewSentences.addEventListener("click", playReviewAudio);
        elements.copyAnalysisReport.addEventListener("click", copyAnalysisReport);
        document.addEventListener("keydown", event => {
            if (event.altKey && event.key.toLowerCase() === "p" && !document.querySelector("#practice-view").hidden) {
                event.preventDefault();
                playSentence();
            }
        });
    }

    async function refreshCsrf() {
        const csrf = await api("/api/auth/csrf", {redirectOnUnauthorized: false});
        state.csrfHeader = csrf.headerName;
        state.csrfToken = csrf.token;
    }

    async function enterApplication() {
        elements.authScreen.hidden = true;
        elements.appShell.hidden = false;
        elements.accountName.textContent = state.currentUser.displayName;
        elements.accountEmail.textContent = state.currentUser.email;
        renderLessonSkeletons();
        state.levels = await api("/api/v1/catalog/levels");
        renderLevelTabs();
        await selectLevel(state.selectedLevel);
        await refreshDashboard(false);
        await navigate("catalog");
    }

    function showAuthScreen(message = "") {
        stopRecordedAudio();
        stopTimer();
        state.currentUser = null;
        state.session = null;
        state.sentence = null;
        elements.appShell.hidden = true;
        elements.authScreen.hidden = false;
        switchAuthMode("login");
        elements.authStatus.textContent = message;
        window.requestAnimationFrame(() => document.querySelector("#login-email")?.focus());
    }

    function switchAuthMode(mode) {
        const login = mode === "login";
        elements.loginForm.hidden = !login;
        elements.registerForm.hidden = login;
        elements.loginTab.setAttribute("aria-selected", String(login));
        elements.registerTab.setAttribute("aria-selected", String(!login));
        elements.loginTab.tabIndex = login ? 0 : -1;
        elements.registerTab.tabIndex = login ? -1 : 0;
        elements.authHeading.textContent = login ? "Chào mừng trở lại." : "Bắt đầu nhật ký nghe.";
        elements.authSubtitle.textContent = login
            ? "Đăng nhập để tiếp tục từ đúng nơi bạn đã dừng."
            : "Kết quả luyện tập sẽ được lưu riêng và theo dõi theo thời gian.";
        elements.authStatus.textContent = "";
    }

    async function submitAuth(event, mode) {
        event.preventDefault();
        const form = event.currentTarget;
        const submitButton = form.querySelector("button[type='submit']");
        const data = Object.fromEntries(new FormData(form).entries());
        submitButton.disabled = true;
        elements.authStatus.textContent = mode === "login" ? "Đang đăng nhập…" : "Đang tạo tài khoản…";
        try {
            state.currentUser = await api(`/api/auth/${mode}`, {
                method: "POST",
                body: data,
                redirectOnUnauthorized: false
            });
            form.reset();
            elements.authStatus.textContent = "";
            await enterApplication();
        } catch (error) {
            elements.authStatus.textContent = error.message;
        } finally {
            submitButton.disabled = false;
        }
    }

    async function logout() {
        elements.logoutButton.disabled = true;
        try {
            await api("/api/auth/logout", {method: "POST", redirectOnUnauthorized: false});
        } catch (error) {
            if (error.status !== 401) {
                showToast(error.message, true);
                return;
            }
        } finally {
            elements.logoutButton.disabled = false;
        }
        await refreshCsrf();
        showAuthScreen();
    }

    async function navigate(route) {
        if (route === "dashboard") {
            await refreshDashboard(true);
        }
        const target = document.querySelector(`#${route}-view`);
        if (!target) {
            return;
        }
        elements.views.forEach(view => view.hidden = view !== target);
        elements.navLinks.forEach(link => {
            const active = link.dataset.route === route;
            link.classList.toggle("is-active", active);
            if (active) {
                link.setAttribute("aria-current", "page");
            } else {
                link.removeAttribute("aria-current");
            }
        });
        window.scrollTo({top: 0, behavior: "smooth"});
        document.querySelector("#main-content").focus({preventScroll: true});
    }

    function renderLevelTabs() {
        elements.levelTabs.innerHTML = state.levels.map((level, index) => `
            <button class="level-tab" id="level-tab-${level.code}" type="button" role="tab"
                    aria-selected="${level.code === state.selectedLevel}" aria-controls="lesson-grid"
                    tabindex="${level.code === state.selectedLevel ? 0 : -1}" data-level="${level.code}">
                <strong>${escapeHtml(level.code)}</strong>
                <span>${escapeHtml(level.label.split("·")[1]?.trim() || level.label)}</span>
            </button>
        `).join("");

        const tabs = [...elements.levelTabs.querySelectorAll("[role='tab']")];
        tabs.forEach((tab, index) => tab.addEventListener("keydown", event => {
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) {
                return;
            }
            event.preventDefault();
            let nextIndex = index;
            if (event.key === "ArrowRight") nextIndex = (index + 1) % tabs.length;
            if (event.key === "ArrowLeft") nextIndex = (index - 1 + tabs.length) % tabs.length;
            if (event.key === "Home") nextIndex = 0;
            if (event.key === "End") nextIndex = tabs.length - 1;
            tabs[nextIndex].focus();
            selectLevel(tabs[nextIndex].dataset.level);
        }));
    }

    async function selectLevel(level) {
        state.selectedLevel = level;
        [...elements.levelTabs.querySelectorAll("[role='tab']")].forEach(tab => {
            const selected = tab.dataset.level === level;
            tab.setAttribute("aria-selected", String(selected));
            tab.tabIndex = selected ? 0 : -1;
        });
        const levelInfo = state.levels.find(item => item.code === level);
        elements.levelDescription.textContent = levelInfo?.description || "";
        renderLessonSkeletons();
        try {
            state.lessons = await api(`/api/v1/catalog/lessons?level=${encodeURIComponent(level)}`);
            renderLessons();
        } catch (error) {
            elements.lessonGrid.setAttribute("aria-busy", "false");
            elements.lessonGrid.innerHTML = `<p>Không tải được bài luyện: ${escapeHtml(error.message)}</p>`;
        }
    }

    function renderLessonSkeletons() {
        elements.lessonGrid.setAttribute("aria-busy", "true");
        elements.lessonGrid.innerHTML = '<div class="lesson-skeleton"></div><div class="lesson-skeleton"></div>';
        elements.lessonCount.textContent = "Đang tải…";
    }

    function renderLessons() {
        elements.lessonGrid.setAttribute("aria-busy", "false");
        elements.lessonCount.textContent = `${state.lessons.length} bài phù hợp`;
        if (!state.lessons.length) {
            elements.lessonGrid.innerHTML = "<p>Level này chưa có bài luyện.</p>";
            return;
        }
        elements.lessonGrid.innerHTML = state.lessons.map((lesson, index) => `
            <article class="lesson-card">
                <span class="lesson-card-number" aria-hidden="true">${String(index + 1).padStart(2, "0")}</span>
                <div>
                    <h3>${escapeHtml(lesson.title)}</h3>
                    <p>${escapeHtml(lesson.description)}</p>
                    <div class="lesson-meta" aria-label="Thông tin bài luyện">
                        <span>${escapeHtml(lesson.topic)}</span>
                        <span>${lesson.sentenceCount} câu</span>
                        <span>~${lesson.estimatedMinutes} phút</span>
                    </div>
                </div>
                <button class="start-lesson" type="button" data-lesson-id="${lesson.id}"
                        aria-label="Bắt đầu bài ${escapeHtml(lesson.title)}">
                    <span aria-hidden="true">→</span>
                </button>
            </article>
        `).join("");
    }

    async function startLesson(lessonId) {
        setCatalogBusy(true);
        try {
            state.session = await api("/api/v1/practice-sessions", {
                method: "POST",
                body: {lessonId}
            });
            state.sentence = state.session.sentence;
            state.activeWordIndex = 0;
            state.sentenceStartedAt = null;
            state.timerSeconds = 0;
            renderPractice(true);
            await navigate("practice");
            elements.replayButton.focus();
        } catch (error) {
            showToast(error.message, true);
        } finally {
            setCatalogBusy(false);
        }
    }

    function setCatalogBusy(busy) {
        elements.lessonGrid.querySelectorAll("button").forEach(button => button.disabled = busy);
        elements.lessonGrid.setAttribute("aria-busy", String(busy));
    }

    function renderPractice(newSentence = false) {
        const sentence = state.sentence;
        if (!sentence || !state.session) {
            return;
        }
        if (newSentence) {
            state.wordDrafts.clear();
        }
        elements.practiceLevel.textContent = state.session.level;
        elements.practiceTitle.textContent = state.session.lessonTitle;
        elements.sentencePosition.textContent = sentence.position;
        elements.sentenceTotal.textContent = sentence.totalSentences;
        elements.sentenceProgress.style.width = `${sentence.position / sentence.totalSentences * 100}%`;
        elements.replayCount.textContent = sentence.replayCount;
        elements.hintCount.textContent = sentence.hintCount + sentence.revealCount;
        if (newSentence) {
            resetTimer();
            setPracticeStatus(sentence.hasListened
                ? "Tiếp tục câu đang làm dở. Bạn có thể nghe lại hoặc nhập tiếp."
                : "Nhấn Nghe câu để bắt đầu.");
        }
        renderWordSlots();
        renderClassifications();
        updatePracticeControls();
    }

    function renderWordSlots() {
        const words = state.sentence.words;
        const focused = document.activeElement?.dataset?.wordIndex;
        if (focused !== undefined) {
            state.activeWordIndex = Number(focused);
        }
        elements.wordSlots.innerHTML = words.map(word => {
            const width = Math.max(3, Math.min(18, word.length));
            const draft = state.wordDrafts.get(word.index);
            const inputValue = draft === undefined
                ? (word.userInput || word.firstLetter || "")
                : draft;
            const classes = ["word-slot"];
            if (word.correct) classes.push("is-correct");
            if (word.revealed) classes.push("is-revealed");
            if (word.userInput && !word.correct && !word.revealed) classes.push("is-wrong");
            const hint = word.firstLetter
                ? `<span class="slot-hint" aria-hidden="true">Bắt đầu: ${escapeHtml(word.firstLetter)}</span>`
                : "";
            const control = word.revealed
                ? `<span class="revealed-word" style="width: calc(${width}ch + 24px)"
                         aria-label="Đáp án từ ${word.index + 1}: ${escapeHtml(word.revealedText)}">
                       ${escapeHtml(word.revealedText)}
                   </span>`
                : `<label>
                       <span class="sr-only">Từ ${word.index + 1}, ${word.length} ký tự${word.firstLetter ? `, bắt đầu bằng ${escapeHtml(word.firstLetter)}` : ""}</span>
                       <input type="text" inputmode="text" autocomplete="off" autocapitalize="none" spellcheck="false"
                              data-word-index="${word.index}" maxlength="${word.length}"
                              style="width: calc(${width}ch + 24px)" placeholder="${escapeHtml(word.mask)}"
                              value="${escapeAttribute(inputValue)}" ${word.correct ? "readonly aria-readonly=\"true\"" : ""}
                              aria-invalid="${word.userInput && !word.correct ? "true" : "false"}">
                   </label>`;
            return `<span class="${classes.join(" ")}" data-slot-index="${word.index}">${hint}${control}</span>`;
        }).join("");

        const resolved = words.filter(word => word.correct || word.revealed).length;
        elements.wordCompletion.textContent = `${resolved}/${words.length} từ`;
    }

    function updatePracticeControls() {
        const sentence = state.sentence;
        const currentWord = sentence.words[state.activeWordIndex];
        const canUseWordHint = sentence.hasListened && !state.speaking && currentWord
            && !currentWord.correct && !currentWord.revealed;
        elements.hintLetter.disabled = !canUseWordHint;
        elements.hintWord.disabled = !canUseWordHint;
        elements.revealSentence.disabled = !sentence.hasListened || state.speaking
            || sentence.words.every(word => word.correct || word.revealed);
        elements.replayButton.disabled = state.speaking;
        elements.wordSlots.querySelectorAll("input").forEach(input => {
            const word = sentence.words[Number(input.dataset.wordIndex)];
            input.disabled = !sentence.hasListened;
            input.readOnly = Boolean(word?.correct);
        });
        elements.nextSentence.disabled = !sentence.canComplete || state.speaking;

        if (!sentence.hasListened) {
            elements.nextRequirement.textContent = "Nghe câu ít nhất một lần để bắt đầu.";
        } else if (sentence.requiresReplayAfterReveal) {
            elements.nextRequirement.textContent = "Bạn đã xem đáp án. Hãy nghe lại câu đúng trọn vẹn trước khi tiếp tục.";
        } else if (sentence.canComplete) {
            elements.nextRequirement.textContent = "Đã hoàn thành câu. Bạn có thể chuyển tiếp.";
        } else {
            const remaining = sentence.words.filter(word => !word.correct && !word.revealed).length;
            elements.nextRequirement.textContent = `Còn ${remaining} từ cần hoàn thành.`;
        }
    }

    function onWordFocus(event) {
        const input = event.target.closest("input[data-word-index]");
        if (input) {
            state.activeWordIndex = Number(input.dataset.wordIndex);
            updatePracticeControls();
        }
    }

    function onWordInput(event) {
        const input = event.target.closest("input[data-word-index]");
        if (!input) return;
        const index = Number(input.dataset.wordIndex);
        state.activeWordIndex = index;
        if (input.value) {
            state.wordDrafts.set(index, input.value);
        } else {
            state.wordDrafts.delete(index);
        }
        input.closest(".word-slot")?.classList.remove("is-wrong");
        input.setAttribute("aria-invalid", "false");
        window.clearTimeout(input._checkTimer);
        const expectedLength = state.sentence.words[index].length;
        if (Array.from(input.value.trim()).length === expectedLength) {
            input._checkTimer = window.setTimeout(() => checkWord(index, input.value), 240);
        }
    }

    function onWordBlur(event) {
        const input = event.target.closest("input[data-word-index]");
        if (!input) {
            return;
        }
        const index = Number(input.dataset.wordIndex);
        const answer = input.value.trim();
        if (state.sentence.words[index]?.correct) {
            return;
        }
        const lastCheckedAnswer = state.sentence.words[index]?.userInput?.trim();
        if (!answer || answer === lastCheckedAnswer || state.checkingWords.has(index)) {
            return;
        }
        checkWord(index, answer);
    }

    async function onWordKeydown(event) {
        const input = event.target.closest("input[data-word-index]");
        if (!input) return;
        const index = Number(input.dataset.wordIndex);
        if (event.key === " ") {
            event.preventDefault();
            window.clearTimeout(input._checkTimer);
            if (state.sentence.words[index]?.correct) {
                focusNextWord(index);
            } else if (input.value.trim()) {
                await checkWord(index, input.value);
                focusNextWord(index);
            } else {
                focusNextWord(index);
            }
        } else if (event.key === "Enter") {
            event.preventDefault();
            window.clearTimeout(input._checkTimer);
            if (state.sentence.canComplete) {
                await completeSentence(event);
            } else if (input.value.trim() && !state.sentence.words[index]?.correct) {
                const correct = await checkWord(index, input.value);
                if (correct && state.sentence.canComplete) {
                    await completeSentence(event);
                }
            }
        }
    }

    function onSentencePaste(event) {
        const input = event.target.closest("input[data-word-index]");
        const pasted = event.clipboardData?.getData("text")?.trim();
        if (!input || !pasted || !/\s/.test(pasted)) return;
        event.preventDefault();
        const incomingWords = pasted.split(/\s+/);
        const inputs = [...elements.wordSlots.querySelectorAll("input[data-word-index]:not(:disabled)")]
            .filter(candidate => Number(candidate.dataset.wordIndex) >= Number(input.dataset.wordIndex));
        inputs.forEach((candidate, position) => {
            if (incomingWords[position]) {
                candidate.value = incomingWords[position].replace(/[.,!?;:]$/u, "");
                candidate.dispatchEvent(new Event("input", {bubbles: true}));
            }
        });
    }

    async function checkWord(index, answer) {
        if (!answer.trim() || state.checkingWords.has(index)) return false;
        state.checkingWords.add(index);
        try {
            state.sentence = await api(practicePath(`/words/${index}/checks`), {
                method: "POST",
                body: {answer: answer.trim()}
            });
            state.wordDrafts.delete(index);
            const correct = state.sentence.words[index].correct;
            renderPractice(false);
            setPracticeStatus(correct ? `Từ ${index + 1} chính xác.` : `Từ ${index + 1} chưa khớp. Hãy nghe lại hoặc phân loại lỗi.`, !correct);
            focusWord(index, !correct);
            return correct;
        } catch (error) {
            setPracticeStatus(error.message, true);
            return false;
        } finally {
            state.checkingWords.delete(index);
        }
    }

    async function useHint(type) {
        if (!state.sentence) return;
        try {
            state.sentence = await api(practicePath("/hints"), {
                method: "POST",
                body: {
                    type,
                    wordIndex: type === "REVEAL_SENTENCE" ? null : state.activeWordIndex
                }
            });
            renderPractice(false);
            if (type === "FIRST_LETTER") {
                setPracticeStatus("Đã mở chữ cái đầu. Gợi ý này được tính vào điểm độc lập.");
                focusWord(state.activeWordIndex, true);
            } else {
                setPracticeStatus("Đáp án đã được mở. Hãy nghe lại câu đúng trước khi chuyển tiếp.");
            }
        } catch (error) {
            setPracticeStatus(error.message, true);
        }
    }

    async function onClassifyWord(event) {
        const button = event.target.closest("button[data-error-type]");
        if (!button) return;
        const wordIndex = Number(button.dataset.wordIndex);
        try {
            state.sentence = await api(practicePath("/classifications"), {
                method: "POST",
                body: {wordIndex, errorType: button.dataset.errorType}
            });
            renderPractice(false);
            setPracticeStatus("Đã cập nhật loại lỗi cho dashboard.");
        } catch (error) {
            setPracticeStatus(error.message, true);
        }
    }

    function renderClassifications() {
        const missedWords = state.sentence.words.filter(word => word.errorType);
        elements.feedbackPanel.hidden = missedWords.length === 0;
        elements.classifications.innerHTML = missedWords.map(word => `
            <div class="error-choice">
                <strong>Từ ${word.index + 1}</strong>
                <span class="choice-buttons" role="group" aria-label="Phân loại lỗi của từ ${word.index + 1}">
                    <button type="button" data-word-index="${word.index}" data-error-type="KNOWN_BUT_MISSED"
                            aria-pressed="${word.errorType === "KNOWN_BUT_MISSED"}">Biết nhưng lỡ</button>
                    <button type="button" data-word-index="${word.index}" data-error-type="UNKNOWN"
                            aria-pressed="${word.errorType === "UNKNOWN"}">Từ mới</button>
                </span>
            </div>
        `).join("");
    }

    async function playSentence() {
        if (!state.sentence || state.speaking) return;
        const hasRecordedAudio = Boolean(state.sentence.audioUrl);
        if (!hasRecordedAudio && !("speechSynthesis" in window)) {
            elements.speechSupportNote.hidden = false;
            elements.speechSupportNote.textContent = "Trình duyệt này không hỗ trợ đọc câu. Hãy dùng Chrome, Edge hoặc Safari mới.";
            return;
        }

        elements.speechSupportNote.hidden = true;
        state.speaking = true;
        elements.listeningConsole.classList.add("is-speaking");
        updatePracticeControls();
        try {
            state.sentence = await api(practicePath("/replay-events"), {
                method: "POST",
                body: {event: "STARTED"}
            });
            if (!state.sentenceStartedAt) {
                startTimer();
            }
            elements.replayCount.textContent = state.sentence.replayCount;
            updatePracticeControls();
            setPracticeStatus("Đang phát câu. Bạn có thể nhập ngay trong khi nghe.");
            if (hasRecordedAudio) {
                await playRecordedAudio(
                    state.sentence.audioUrl,
                    state.sentence.audioStartSeconds,
                    state.sentence.audioEndSeconds
                );
            } else {
                await speak(state.sentence.speechText, state.sentence.speechRate);
            }
            state.sentence = await api(practicePath("/replay-events"), {
                method: "POST",
                body: {event: "COMPLETED"}
            });
            setPracticeStatus(state.sentence.requiresReplayAfterReveal
                ? "Hãy nghe hết câu trước khi chuyển tiếp."
                : "Đã nghe trọn câu. Bây giờ hãy điền những gì bạn nghe được.");
        } catch (error) {
            stopRecordedAudio();
            if (error.name !== "AbortError") {
                setPracticeStatus(error.message, true);
            }
        } finally {
            state.speaking = false;
            elements.listeningConsole.classList.remove("is-speaking");
            updatePracticeControls();
        }
    }

    async function playRecordedAudio(url, startSeconds, endSeconds) {
        stopRecordedAudio();
        const playbackGeneration = ++state.audioPlaybackGeneration;
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) {
            throw new Error("Trình duyệt này không hỗ trợ phát từng đoạn audio.");
        }
        state.audioContext ||= new AudioContextClass();
        if (state.audioContext.state === "suspended") {
            await state.audioContext.resume();
        }

        const buffer = await loadAudioBuffer(url);
        if (playbackGeneration !== state.audioPlaybackGeneration) {
            throw new DOMException("Audio playback cancelled", "AbortError");
        }
        const segment = audioSegment(startSeconds, endSeconds, buffer.duration);
        const source = state.audioContext.createBufferSource();
        source.buffer = buffer;
        source.connect(state.audioContext.destination);

        return new Promise((resolve, reject) => {
            let settled = false;
            const finish = callback => {
                if (settled) return;
                settled = true;
                source.onended = null;
                if (state.activeAudio?.source === source) {
                    state.activeAudio = null;
                }
                callback();
            };
            source.onended = () => finish(resolve);
            state.activeAudio = {
                source,
                cancel: () => finish(() => {
                    try {
                        source.stop();
                    } catch (_error) {
                        // The source may already have stopped naturally.
                    }
                    reject(new DOMException("Audio playback cancelled", "AbortError"));
                })
            };
            try {
                source.start(0, segment.start, segment.duration);
            } catch (_error) {
                finish(() => reject(new Error("Không thể bắt đầu phát file audio của bài nghe.")));
            }
        });
    }

    function loadAudioBuffer(url) {
        const cached = state.audioBufferCache.get(url);
        if (cached) {
            state.audioBufferCache.delete(url);
            state.audioBufferCache.set(url, cached);
            return cached;
        }

        const pending = fetch(url, {credentials: "same-origin"})
            .then(response => {
                if (!response.ok) throw new Error(`Audio HTTP ${response.status}`);
                return response.arrayBuffer();
            })
            .then(data => state.audioContext.decodeAudioData(data))
            .catch(error => {
                state.audioBufferCache.delete(url);
                throw new Error("Không thể tải file audio của bài nghe.", {cause: error});
            });
        if (state.audioBufferCache.size >= 8) {
            state.audioBufferCache.delete(state.audioBufferCache.keys().next().value);
        }
        state.audioBufferCache.set(url, pending);
        return pending;
    }

    function audioSegment(startSeconds, endSeconds, audioDuration = Number.POSITIVE_INFINITY) {
        const start = Number.parseFloat(startSeconds);
        const end = Number.parseFloat(endSeconds);
        if (Number.isFinite(start) && Number.isFinite(end) && start >= 0 && end > start) {
            const safeStart = Math.min(start, audioDuration);
            return {start: safeStart, duration: Math.max(0, Math.min(end, audioDuration) - safeStart)};
        }
        return {start: 0, duration: audioDuration};
    }

    function stopRecordedAudio() {
        state.audioPlaybackGeneration += 1;
        if (!state.activeAudio) {
            return;
        }
        const activeAudio = state.activeAudio;
        state.activeAudio = null;
        activeAudio.cancel();
    }

    function speak(text, rate) {
        return new Promise((resolve, reject) => {
            window.speechSynthesis.cancel();
            const utterance = new SpeechSynthesisUtterance(text);
            utterance.lang = "en-US";
            utterance.rate = rate || 1;
            const voices = window.speechSynthesis.getVoices();
            utterance.voice = voices.find(voice => voice.lang === "en-US")
                || voices.find(voice => voice.lang?.startsWith("en"))
                || null;
            utterance.onend = resolve;
            utterance.onerror = event => reject(new Error(event.error === "canceled"
                ? "Đã dừng phát câu."
                : "Không thể phát giọng đọc trên trình duyệt này."));
            window.speechSynthesis.speak(utterance);
        });
    }

    async function completeSentence(event) {
        event.preventDefault();
        if (!state.sentence?.canComplete || state.speaking) return;
        elements.nextSentence.disabled = true;
        try {
            const response = await api(practicePath("/complete"), {
                method: "POST",
                body: {timeSpentSeconds: state.timerSeconds}
            });
            if (response.lessonCompleted) {
                stopTimer();
                renderResult(response.result);
                await refreshDashboard(false);
                await navigate("result");
            } else {
                state.sentence = response.nextSentence;
                state.activeWordIndex = 0;
                renderPractice(true);
                elements.replayButton.focus();
            }
        } catch (error) {
            setPracticeStatus(error.message, true);
            elements.nextSentence.disabled = false;
        }
    }

    function renderResult(result) {
        state.lastResult = result;
        state.reviewAudioDurations.clear();
        document.querySelector("#result-score").textContent = Math.round(result.overallScore);
        document.querySelector("#result-first").textContent = `${formatPercent(result.firstAttemptAccuracy)}%`;
        document.querySelector("#result-final").textContent = `${formatPercent(result.finalAccuracy)}%`;
        document.querySelector("#result-replays").textContent = result.replayCount;
        document.querySelector("#result-hints").textContent = `${result.hintCount} / ${result.revealCount}`;
        document.querySelector("#result-known").textContent = result.knownButMissed;
        document.querySelector("#result-unknown").textContent = result.unknownWords;
        document.querySelector("#result-message").textContent = result.firstAttemptAccuracy >= 85
            ? "Bạn nhận âm rất chắc ở lần đầu. Hệ thống sẽ cân nhắc tăng độ khó."
            : result.firstAttemptAccuracy >= 60
                ? "Nền tảng đang tốt dần. Hãy chú ý những từ quen nhưng thường bị nuốt âm."
                : "Hãy luyện thêm ở tốc độ này. First attempt thấp là dữ liệu hữu ích, không phải thất bại.";
        renderLessonReview(result);
        loadReviewAudioMetrics(result).catch(() => {
            if (state.lastResult?.sessionId === result.sessionId) {
                elements.reviewAudioMetrics.innerHTML = "<span>Không đo được thời lượng audio</span>";
            }
        });
    }

    function renderLessonReview(result) {
        const review = result.review;
        if (!review) {
            elements.reviewLessonMeta.textContent = "Phiên này chưa có dữ liệu review chi tiết.";
            elements.reviewSentences.innerHTML = "";
            elements.reviewOutcomeSummary.innerHTML = "";
            return;
        }

        const sourceLink = review.sourceUrl
            ? `<a href="${escapeAttribute(review.sourceUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(review.sourceName)}</a>`
            : escapeHtml(review.sourceName);
        elements.reviewLessonMeta.innerHTML = `
            <strong>${escapeHtml(result.lessonTitle)}</strong> · ${escapeHtml(result.level)} ·
            ${escapeHtml(review.topic)} · ${review.totalSentences} câu · ${review.totalWords} từ · ${sourceLink}
        `;
        elements.reviewAudioMetrics.innerHTML = "<span>Đang đo thời lượng audio…</span>";

        const allWords = review.sentences.flatMap(sentence => sentence.words);
        const counts = Object.keys(outcomeLabels).reduce((summary, outcome) => {
            summary[outcome] = allWords.filter(word => word.outcome === outcome).length;
            return summary;
        }, {});
        elements.reviewOutcomeSummary.innerHTML = Object.entries(outcomeLabels).map(([outcome, label]) => `
            <article data-outcome="${outcome}">
                <strong>${counts[outcome]}</strong>
                <span>${escapeHtml(label)}</span>
            </article>
        `).join("");

        elements.reviewSentences.innerHTML = review.sentences.map(sentence => {
            const attentionWords = sentence.words.filter(word => word.outcome !== "FIRST_ATTEMPT");
            const helpSummary = [
                sentence.firstLetterHintCount ? `${sentence.firstLetterHintCount} chữ đầu` : "",
                sentence.wordRevealCount ? `${sentence.wordRevealCount} từ đã hiện` : "",
                sentence.fullRevealUsed ? "đã hiện cả câu" : ""
            ].filter(Boolean).join(" · ") || "không dùng gợi ý";
            return `
                <article class="review-sentence" id="review-sentence-${sentence.position}">
                    <div class="review-sentence-heading">
                        <div>
                            <span class="review-sentence-number">${String(sentence.position).padStart(2, "0")}</span>
                            <p>${sentence.replayCount} lượt nghe · ${formatTime(sentence.timeSpentSeconds)} · ${escapeHtml(helpSummary)}</p>
                        </div>
                        ${sentence.audioUrl ? `
                            <button class="review-audio-button" type="button"
                                    data-review-audio="${escapeAttribute(sentence.audioUrl)}"
                                    data-audio-start="${escapeAttribute(sentence.audioStartSeconds ?? "")}"
                                    data-audio-end="${escapeAttribute(sentence.audioEndSeconds ?? "")}"
                                    aria-label="Nghe lại câu ${sentence.position}">
                                <span aria-hidden="true">▶</span> Nghe lại
                            </button>` : ""}
                    </div>
                    <p class="colored-transcript" aria-label="${escapeAttribute(sentence.transcript)}">
                        ${renderReviewTranscript(sentence)}
                    </p>
                    ${attentionWords.length ? `
                        <details class="word-evidence">
                            <summary>${attentionWords.length} từ cần chú ý</summary>
                            <ul>${attentionWords.map(word => `
                                <li>
                                    <strong>${escapeHtml(word.expectedWord)}</strong>
                                    <span>${escapeHtml(outcomeLabels[word.outcome] || "Chưa phân loại")}</span>
                                    ${word.firstAnswer ? `<small>Nhập đầu: “${escapeHtml(word.firstAnswer)}”</small>` : ""}
                                    ${word.attemptCount > 1 ? `<small>${word.attemptCount} lần thử</small>` : ""}
                                    ${word.errorType ? `<small>${word.errorType === "UNKNOWN" ? "Từ chưa biết" : "Biết nhưng nghe lỡ"}</small>` : ""}
                                </li>`).join("")}</ul>
                        </details>` : ""}
                </article>
            `;
        }).join("");
        refreshAnalysisReport();
    }

    function renderReviewTranscript(sentence) {
        const pattern = /[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*/gu;
        let cursor = 0;
        let wordIndex = 0;
        let html = "";
        for (const match of sentence.transcript.matchAll(pattern)) {
            html += escapeHtml(sentence.transcript.slice(cursor, match.index));
            const word = sentence.words[wordIndex++];
            if (!word) {
                html += escapeHtml(match[0]);
            } else {
                const label = outcomeLabels[word.outcome] || "Chưa phân loại";
                const firstAnswer = word.firstAnswer ? `; nhập đầu ${word.firstAnswer}` : "";
                html += `<mark class="review-word" data-outcome="${escapeAttribute(word.outcome || "UNKNOWN")}" `
                    + `title="${escapeAttribute(label + firstAnswer)}">${escapeHtml(match[0])}</mark>`;
            }
            cursor = match.index + match[0].length;
        }
        return html + escapeHtml(sentence.transcript.slice(cursor));
    }

    async function playReviewAudio(event) {
        const button = event.target.closest("[data-review-audio]");
        if (!button || button.disabled) return;
        const audioButtons = [...elements.reviewSentences.querySelectorAll("[data-review-audio]")];
        audioButtons.forEach(item => item.disabled = true);
        button.classList.add("is-playing");
        const originalText = button.innerHTML;
        button.innerHTML = '<span aria-hidden="true">■</span> Đang phát';
        try {
            await playRecordedAudio(
                button.dataset.reviewAudio,
                button.dataset.audioStart,
                button.dataset.audioEnd
            );
        } catch (error) {
            if (error.name !== "AbortError") {
                showToast(error.message, true);
            }
        } finally {
            audioButtons.forEach(item => item.disabled = false);
            button.classList.remove("is-playing");
            button.innerHTML = originalText;
        }
    }

    async function loadReviewAudioMetrics(result) {
        const sentences = result.review?.sentences.filter(sentence => sentence.audioUrl) || [];
        const withoutSegments = [];
        sentences.forEach(sentence => {
            const segment = audioSegment(sentence.audioStartSeconds, sentence.audioEndSeconds);
            if (Number.isFinite(segment.duration) && segment.duration > 0) {
                state.reviewAudioDurations.set(sentence.sentenceId, segment.duration);
            } else {
                withoutSegments.push(sentence);
            }
        });
        for (let start = 0; start < withoutSegments.length; start += 5) {
            const batch = withoutSegments.slice(start, start + 5);
            const durations = await Promise.all(batch.map(sentence => readAudioDuration(sentence.audioUrl)));
            batch.forEach((sentence, index) => {
                if (durations[index] > 0) state.reviewAudioDurations.set(sentence.sentenceId, durations[index]);
            });
        }
        if (state.lastResult?.sessionId !== result.sessionId) return;
        const totalSeconds = [...state.reviewAudioDurations.values()].reduce((sum, value) => sum + value, 0);
        const wordsPerMinute = totalSeconds > 0 ? Math.round(result.review.totalWords * 60 / totalSeconds) : 0;
        elements.reviewAudioMetrics.innerHTML = totalSeconds > 0
            ? `<strong>${formatTime(Math.round(totalSeconds))}</strong><span>audio · ≈ ${wordsPerMinute} từ/phút</span>`
            : "<span>Không có dữ liệu thời lượng audio</span>";
        elements.reviewSentences.querySelectorAll(".review-sentence").forEach((card, index) => {
            const sentence = result.review.sentences[index];
            const duration = state.reviewAudioDurations.get(sentence.sentenceId);
            const meta = card.querySelector(".review-sentence-heading p");
            if (duration && meta) {
                meta.textContent += ` · audio ${formatTime(Math.round(duration))}`;
            }
        });
        refreshAnalysisReport();
    }

    function readAudioDuration(url) {
        return new Promise(resolve => {
            const audio = new Audio();
            let settled = false;
            const finish = duration => {
                if (settled) return;
                settled = true;
                window.clearTimeout(timeout);
                audio.onloadedmetadata = null;
                audio.onerror = null;
                audio.removeAttribute("src");
                audio.load();
                resolve(Number.isFinite(duration) ? duration : 0);
            };
            const timeout = window.setTimeout(() => finish(0), 7000);
            audio.preload = "metadata";
            audio.onloadedmetadata = () => finish(audio.duration);
            audio.onerror = () => finish(0);
            audio.src = url;
        });
    }

    function refreshAnalysisReport() {
        if (!state.lastResult?.review) return;
        elements.analysisReportPreview.textContent = buildAnalysisReport(state.lastResult);
    }

    async function copyAnalysisReport() {
        if (!state.lastResult?.review) return;
        const report = buildAnalysisReport(state.lastResult);
        elements.copyAnalysisReport.disabled = true;
        try {
            await navigator.clipboard.writeText(report);
            elements.copyReportStatus.textContent = "Đã sao chép. Bạn có thể dán thẳng vào ChatGPT.";
        } catch (_error) {
            const selection = window.getSelection();
            const range = document.createRange();
            range.selectNodeContents(elements.analysisReportPreview);
            selection.removeAllRanges();
            selection.addRange(range);
            elements.copyReportStatus.textContent = "Trình duyệt không cho sao chép tự động. Nội dung đã được chọn để bạn nhấn Ctrl+C.";
        } finally {
            elements.copyAnalysisReport.disabled = false;
        }
    }

    function buildAnalysisReport(result) {
        const review = result.review;
        const allWords = review.sentences.flatMap(sentence => sentence.words);
        const totalAudioSeconds = [...state.reviewAudioDurations.values()].reduce((sum, value) => sum + value, 0);
        const wordsPerMinute = totalAudioSeconds > 0 ? Math.round(review.totalWords * 60 / totalAudioSeconds) : null;
        const outcomeSummary = Object.entries(outcomeLabels)
            .map(([outcome, label]) => `${label}: ${allWords.filter(word => word.outcome === outcome).length}`)
            .join("; ");
        const lines = [
            "BÁO CÁO LUYỆN NGHE DICTALAB",
            `Người học: ${state.currentUser?.displayName || "Không cung cấp"}`,
            `Bài: ${result.lessonTitle}`,
            `CEFR do nguồn gắn nhãn: ${result.level}`,
            `Chủ điểm: ${review.topic}`,
            `Nguồn audio người thật: ${review.sourceName}${review.sourceUrl ? ` — ${review.sourceUrl}` : ""}`,
            `Quy mô: ${review.totalSentences} câu, ${review.totalWords} từ`,
            totalAudioSeconds > 0
                ? `Audio: ${formatTime(Math.round(totalAudioSeconds))}, tốc độ ước tính ${wordsPerMinute} từ/phút (tính cả khoảng lặng)`
                : "Audio: chưa đo được thời lượng/tốc độ",
            `Thời gian làm: ${formatTime(result.practiceSeconds)}`,
            `Điểm tổng: ${formatPercent(result.overallScore)}/100`,
            `Độ chính xác lần đầu: ${formatPercent(result.firstAttemptAccuracy)}%`,
            `Độ chính xác cuối: ${formatPercent(result.finalAccuracy)}%`,
            `Replay: ${result.replayCount}; hint: ${result.hintCount}; reveal cả câu: ${result.revealCount}`,
            `Phân loại lỗi: biết nhưng nghe lỡ ${result.knownButMissed}; từ chưa biết ${result.unknownWords}`,
            `Kết quả theo từ: ${outcomeSummary}`,
            "",
            "CHI TIẾT TỪNG CÂU"
        ];
        review.sentences.forEach(sentence => {
            const duration = state.reviewAudioDurations.get(sentence.sentenceId);
            lines.push("", `Câu ${sentence.position} — replay ${sentence.replayCount}; làm ${formatTime(sentence.timeSpentSeconds)}`
                + `${duration ? `; audio ${formatTime(Math.round(duration))}` : ""}`);
            lines.push(`Transcript: ${sentence.transcript}`);
            const evidence = sentence.words.filter(word => word.outcome !== "FIRST_ATTEMPT");
            if (!evidence.length) {
                lines.push("Tất cả từ đúng ngay lần đầu.");
            } else {
                lines.push("Từ cần chú ý:");
                evidence.forEach(word => {
                    const details = [
                        outcomeLabels[word.outcome] || "Chưa phân loại",
                        word.firstAnswer ? `nhập đầu “${word.firstAnswer}”` : "không có đáp án nhập",
                        `${word.attemptCount} lần thử`,
                        word.errorType === "UNKNOWN" ? "từ chưa biết" :
                            word.errorType === "KNOWN_BUT_MISSED" ? "biết nhưng nghe lỡ" : null
                    ].filter(Boolean).join("; ");
                    lines.push(`- ${word.expectedWord}: ${details}`);
                });
            }
        });
        lines.push(
            "",
            "Hãy phân tích giúp tôi:",
            "1. Độ khó thực tế của transcript theo CEFR và liệu nhãn level có hợp lý không.",
            "2. Các mẫu lỗi nghe nổi bật (âm yếu, nối âm, âm cuối, từ chức năng, từ vựng hay chính tả).",
            "3. Điểm mạnh, 3 ưu tiên cần cải thiện và một kế hoạch luyện ngắn cho bài tiếp theo.",
            "Lưu ý: tốc độ chỉ là ước tính từ độ dài file; muốn đánh giá accent/phát âm chính xác cần nghe file audio hoặc mở link nguồn."
        );
        return lines.join("\n");
    }

    async function refreshDashboard(showErrors) {
        try {
            const dashboard = await api("/api/v1/dashboard");
            renderDashboard(dashboard);
        } catch (error) {
            if (showErrors) showToast(error.message, true);
        }
    }

    function renderDashboard(dashboard) {
        const summary = dashboard.summary;
        elements.headerSessionCount.textContent = summary.completedLessons;
        elements.dashboardSummary.innerHTML = [
            ["Buổi hoàn thành", summary.completedLessons, false],
            ["Phút luyện", summary.learningMinutes, false],
            ["First attempt", `${formatPercent(summary.averageFirstAttemptAccuracy)}%`, true],
            ["Final accuracy", `${formatPercent(summary.averageFinalAccuracy)}%`, false],
            ["Điểm trung bình", formatPercent(summary.averageScore), false]
        ].map(([label, value, priority]) => `
            <article class="summary-card ${priority ? "is-priority" : ""}">
                <span>${escapeHtml(label)}</span><strong>${escapeHtml(String(value))}</strong>
            </article>
        `).join("");

        renderTrend(dashboard.trend);
        elements.levelProgress.innerHTML = dashboard.byLevel.map(item => `
            <div class="level-row">
                <strong>${item.level}</strong>
                <div class="level-meter" role="meter" aria-label="First-attempt accuracy level ${item.level}"
                     aria-valuemin="0" aria-valuemax="100" aria-valuenow="${item.averageFirstAttemptAccuracy}">
                    <span style="width: ${item.averageFirstAttemptAccuracy}%"></span>
                </div>
                <span class="level-value">${formatPercent(item.averageFirstAttemptAccuracy)}% · ${item.completedLessons} bài</span>
            </div>
        `).join("");
        elements.errorBreakdown.innerHTML = `
            <article class="error-stat"><strong>${dashboard.errors.knownButMissed}</strong><span>Biết nhưng nghe lỡ</span></article>
            <article class="error-stat"><strong>${dashboard.errors.unknownWords}</strong><span>Từ chưa biết</span></article>
        `;
        renderRecommendation(dashboard.recommendation);
    }

    function renderTrend(trend) {
        if (!trend.length) {
            elements.trendChart.innerHTML = '<p class="trend-empty">Hoàn thành bài đầu tiên để bắt đầu biểu đồ.</p>';
            elements.trendChart.setAttribute("aria-label", "Chưa có dữ liệu tiến bộ");
            return;
        }
        const visible = trend.slice(-8);
        elements.trendChart.setAttribute("role", "img");
        elements.trendChart.setAttribute("aria-label", visible.map(point =>
            `${point.date}, ${point.level}, lần đầu ${point.firstAttemptAccuracy}%, cuối cùng ${point.finalAccuracy}%`
        ).join("; "));
        elements.trendChart.innerHTML = visible.map(point => `
            <div class="trend-group" title="${escapeAttribute(point.lessonTitle)}">
                <div class="trend-bar first" style="height: ${point.firstAttemptAccuracy}%">
                    <span>${Math.round(point.firstAttemptAccuracy)}</span>
                    <small class="trend-label">${escapeHtml(point.level)}</small>
                </div>
                <div class="trend-bar final" style="height: ${point.finalAccuracy}%">
                    <span>${Math.round(point.finalAccuracy)}</span>
                </div>
            </div>
        `).join("");
    }

    function renderRecommendation(recommendation) {
        if (!recommendation) {
            elements.recommendationCard.innerHTML = `
                <p class="section-index">Bài tiếp theo</p><h2 id="recommendation-heading">Chưa có gợi ý</h2>
                <p>Thư viện hiện chưa có bài phù hợp.</p>`;
            return;
        }
        elements.recommendationCard.innerHTML = `
            <p class="section-index">Bài tiếp theo</p>
            <span class="recommendation-level">${escapeHtml(recommendation.level)}</span>
            <h2 id="recommendation-heading">${escapeHtml(recommendation.lessonTitle)}</h2>
            <p>${escapeHtml(recommendation.reason)}</p>
            <button class="primary-button dark" type="button" data-recommended-lesson="${recommendation.lessonId}">
                Luyện bài này <span aria-hidden="true">→</span>
            </button>`;
        elements.recommendationCard.querySelector("[data-recommended-lesson]")
            ?.addEventListener("click", event => startLesson(Number(event.currentTarget.dataset.recommendedLesson)));
    }

    function practicePath(suffix) {
        return `/api/v1/practice-sessions/${encodeURIComponent(state.session.sessionId)}`
            + `/sentences/${state.sentence.sentenceId}${suffix}`;
    }

    function focusNextWord(index) {
        const next = state.sentence.words.find(word => word.index > index && !word.correct && !word.revealed)
            || state.sentence.words.find(word => !word.correct && !word.revealed);
        if (next) focusWord(next.index, true);
    }

    function focusWord(index, select) {
        state.activeWordIndex = index;
        window.requestAnimationFrame(() => {
            const input = elements.wordSlots.querySelector(`input[data-word-index="${index}"]`);
            if (input && !input.disabled) {
                input.focus();
                if (select) input.select();
            }
            updatePracticeControls();
        });
    }

    function startTimer() {
        if (state.timerId) return;
        state.sentenceStartedAt = Date.now();
        state.timerId = window.setInterval(() => {
            state.timerSeconds++;
            elements.sentenceTimer.textContent = formatTime(state.timerSeconds);
        }, 1000);
    }

    function resetTimer() {
        stopTimer();
        state.timerSeconds = 0;
        state.sentenceStartedAt = null;
        elements.sentenceTimer.textContent = "00:00";
    }

    function stopTimer() {
        if (state.timerId) {
            window.clearInterval(state.timerId);
            state.timerId = null;
        }
    }

    function formatTime(totalSeconds) {
        const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, "0");
        const seconds = (totalSeconds % 60).toString().padStart(2, "0");
        return `${minutes}:${seconds}`;
    }

    function formatPercent(value) {
        return Number(value || 0).toFixed(1).replace(".0", "");
    }

    function setPracticeStatus(message, error = false) {
        elements.practiceStatus.textContent = message;
        elements.practiceStatus.classList.toggle("is-error", error);
    }

    function showToast(message, error = false) {
        elements.toast.hidden = false;
        elements.toast.className = `toast${error ? " is-error" : ""}`;
        elements.toast.textContent = message;
        window.clearTimeout(elements.toast._hideTimer);
        elements.toast._hideTimer = window.setTimeout(() => elements.toast.hidden = true, 5000);
    }

    async function api(path, options = {}) {
        const request = {
            method: options.method || "GET",
            headers: {
                "Accept": "application/json"
            },
            credentials: "same-origin"
        };
        if (options.body !== undefined) {
            request.headers["Content-Type"] = "application/json";
            request.body = JSON.stringify(options.body);
        }
        if (!["GET", "HEAD", "OPTIONS"].includes(request.method) && state.csrfToken) {
            request.headers[state.csrfHeader] = state.csrfToken;
        }
        const response = await fetch(path, request);
        const contentType = response.headers.get("content-type") || "";
        const payload = contentType.includes("application/json") ? await response.json() : null;
        if (!response.ok) {
            const error = new Error(payload?.message || `Yêu cầu thất bại (${response.status}).`);
            error.status = response.status;
            if (response.status === 401 && options.redirectOnUnauthorized !== false) {
                showAuthScreen("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
            }
            throw error;
        }
        return payload;
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }

    function escapeAttribute(value) {
        return escapeHtml(value).replaceAll("`", "&#096;");
    }
})();
