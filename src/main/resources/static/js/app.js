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
        playbackSpeed: 1,
        checkingWords: new Set(),
        wordDrafts: new Map(),
        wordAttemptStartedAt: new Map(),
        inputInteractionRevision: 0,
        practiceMutationQueue: Promise.resolve(),
        completeAfterPendingCheck: false,
        completingSentence: false,
        previewSpeaking: false,
        lastResult: null,
        reviewAudioDurations: new Map(),
        adminLessons: [],
        selectedAdminLessonId: null,
        adminSelectedLesson: null,
        adminEditingNew: false
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
        playbackSpeed: document.querySelector("#playback-speed"),
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
        dashboardAssessment: document.querySelector("#dashboard-assessment"),
        resultAssessment: document.querySelector("#result-assessment"),
        reviewLessonMeta: document.querySelector("#review-lesson-meta"),
        reviewAudioMetrics: document.querySelector("#review-audio-metrics"),
        reviewOutcomeSummary: document.querySelector("#review-outcome-summary"),
        reviewSentences: document.querySelector("#review-sentences"),
        analysisReportPreview: document.querySelector("#analysis-report-preview"),
        copyAnalysisReport: document.querySelector("#copy-analysis-report"),
        copyReportStatus: document.querySelector("#copy-report-status"),
        adminNavLink: document.querySelector("#admin-nav-link"),
        adminNewLesson: document.querySelector("#admin-new-lesson"),
        adminLessonSearch: document.querySelector("#admin-lesson-search"),
        adminLessonList: document.querySelector("#admin-lesson-list"),
        adminEmptyState: document.querySelector("#admin-empty-state"),
        adminEditorContent: document.querySelector("#admin-editor-content"),
        adminEditorKicker: document.querySelector("#admin-editor-kicker"),
        adminEditorTitle: document.querySelector("#admin-editor-title"),
        adminPublicationBadge: document.querySelector("#admin-publication-badge"),
        adminLockNotice: document.querySelector("#admin-lock-notice"),
        adminLessonForm: document.querySelector("#admin-lesson-form"),
        adminCancelCreate: document.querySelector("#admin-cancel-create"),
        adminSentenceSection: document.querySelector("#admin-sentence-section"),
        adminAddSentenceForm: document.querySelector("#admin-add-sentence-form"),
        adminSentenceCount: document.querySelector("#admin-sentence-count"),
        adminSentenceList: document.querySelector("#admin-sentence-list"),
        adminPublishTitle: document.querySelector("#admin-publish-title"),
        adminPublishHelp: document.querySelector("#admin-publish-help"),
        adminPublicationButton: document.querySelector("#admin-publication-button"),
        adminStatus: document.querySelector("#admin-status")
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
            const previewButton = event.target.closest("[data-preview-word]");
            if (previewButton) {
                playPreviewWord(previewButton);
                return;
            }
            const button = event.target.closest("[data-lesson-id]");
            if (button) {
                startLesson(Number(button.dataset.lessonId));
            }
        });
        elements.replayButton.addEventListener("click", playSentence);
        elements.playbackSpeed.addEventListener("change", event => {
            state.playbackSpeed = Number(event.target.value) || 1;
        });
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
        elements.adminNewLesson.addEventListener("click", showNewLessonEditor);
        elements.adminCancelCreate.addEventListener("click", hideAdminEditor);
        elements.adminLessonSearch.addEventListener("input", renderAdminLessonList);
        elements.adminLessonList.addEventListener("click", event => {
            const button = event.target.closest("[data-admin-lesson-id]");
            if (button) selectAdminLesson(Number(button.dataset.adminLessonId));
        });
        elements.adminLessonForm.addEventListener("submit", saveAdminLesson);
        elements.adminLessonForm.elements.level.addEventListener("change", event => {
            if (!state.adminEditingNew) return;
            const difficulty = ({A1: 1, A2: 2, B1: 3, B2: 4})[event.target.value] || 1;
            elements.adminLessonForm.elements.vocabularyDifficulty.value = String(difficulty);
            elements.adminLessonForm.elements.grammarDifficulty.value = String(difficulty);
            elements.adminLessonForm.elements.sentenceDifficulty.value = String(difficulty);
        });
        elements.adminAddSentenceForm.addEventListener("submit", addAdminSentence);
        elements.adminSentenceList.addEventListener("click", handleAdminSentenceAction);
        elements.adminSentenceList.addEventListener("change", handleAdminAudioUpload);
        elements.adminPublicationButton.addEventListener("click", toggleAdminPublication);
        document.addEventListener("keydown", event => {
            if (event.altKey && event.key.toLowerCase() === "p" && !document.querySelector("#practice-view").hidden) {
                event.preventDefault();
                playSentence();
                return;
            }
            if (event.key === "Enter" && !event.isComposing && document.body.dataset.route === "practice"
                && state.sentence?.canComplete && !state.speaking) {
                const nativeControl = event.target.closest?.("button, select, textarea, a");
                if (nativeControl) return;
                event.preventDefault();
                completeSentence(event);
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
        elements.adminNavLink.hidden = state.currentUser.role !== "ADMIN";
        renderLessonSkeletons();
        state.levels = await api("/api/v1/catalog/levels");
        renderLevelTabs();
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
        if (route === "admin") {
            if (state.currentUser?.role !== "ADMIN") {
                showToast("Tài khoản này không có quyền quản trị.", true);
                return;
            }
            await refreshAdminLessons();
        }
        if (route === "dashboard") {
            await refreshDashboard(true);
        }
        if (route === "catalog" && state.levels.length) {
            await selectLevel(state.selectedLevel);
        }
        const target = document.querySelector(`#${route}-view`);
        if (!target) {
            return;
        }
        elements.views.forEach(view => view.hidden = view !== target);
        document.body.dataset.route = route;
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
        elements.lessonGrid.innerHTML = state.lessons.map((lesson, index) => {
            const actionLabel = lesson.inProgress ? "Tiếp tục" : lesson.completed ? "Làm lại" : "Bắt đầu";
            const progressLabel = lesson.inProgress
                ? (lesson.completed ? "Đang luyện lại" : "Đang học")
                : lesson.completed ? "Đã hoàn thành" : "Chưa bắt đầu";
            const stateClass = lesson.inProgress ? "is-in-progress" : lesson.completed ? "is-completed" : "is-new";
            const preview = lesson.previewWords?.length ? `
                <div class="lesson-preview" aria-label="Từ khó nghe trước">
                    <span>Nghe trước · không tính đủ trọng số CEFR</span>
                    <div>${lesson.previewWords.map(item => `
                        <button type="button" data-preview-word="${escapeHtml(item.word)}"
                                data-preview-audio-url="${escapeHtml(lesson.previewAudioUrl || "")}"
                                data-preview-start="${item.audioStartSeconds ?? ""}"
                                data-preview-end="${item.audioEndSeconds ?? ""}"
                                aria-label="Nghe từ ${escapeHtml(item.word)}">
                            <i aria-hidden="true">▶</i>${escapeHtml(item.word)}
                        </button>`).join("")}</div>
                </div>` : "";
            return `
            <article class="lesson-card ${stateClass}">
                <span class="lesson-card-number" aria-hidden="true">${String(index + 1).padStart(2, "0")}</span>
                <div>
                    <div class="lesson-progress-heading">
                        <strong>${progressLabel}</strong>
                        <span>${lesson.progressPercent}%</span>
                    </div>
                    <div class="lesson-progress-bar" role="progressbar"
                         aria-label="Tiến độ bài ${escapeHtml(lesson.title)}"
                         aria-valuemin="0" aria-valuemax="100" aria-valuenow="${lesson.progressPercent}">
                        <span style="width: ${lesson.progressPercent}%"></span>
                    </div>
                    <h3>${escapeHtml(lesson.title)}</h3>
                    <p>${escapeHtml(lesson.description)}</p>
                    <div class="lesson-meta" aria-label="Thông tin bài luyện">
                        <span>${escapeHtml(lesson.topic)}</span>
                        <span>${lesson.sentenceCount} câu</span>
                        <span>~${lesson.estimatedMinutes} phút</span>
                    </div>
                    ${preview}
                </div>
                <button class="start-lesson" type="button" data-lesson-id="${lesson.id}"
                        aria-label="${actionLabel} bài ${escapeHtml(lesson.title)}">
                    <strong>${actionLabel}</strong>
                    <span aria-hidden="true">→</span>
                </button>
            </article>
        `;
        }).join("");
    }

    async function playPreviewWord(button) {
        if (state.previewSpeaking) return;
        state.previewSpeaking = true;
        button.classList.add("is-playing");
        try {
            const audioUrl = button.dataset.previewAudioUrl;
            if (audioUrl && button.dataset.previewStart && button.dataset.previewEnd) {
                await playRecordedAudio(audioUrl, button.dataset.previewStart, button.dataset.previewEnd);
            } else {
                await speak(button.dataset.previewWord, 0.85);
            }
        } catch (error) {
            if (error.name !== "AbortError") showToast(error.message, true);
        } finally {
            state.previewSpeaking = false;
            button.classList.remove("is-playing");
        }
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
            state.wordAttemptStartedAt.clear();
            state.inputInteractionRevision = 0;
            state.practiceMutationQueue = Promise.resolve();
            state.completeAfterPendingCheck = false;
            state.completingSentence = false;
            state.sentenceStartedAt = null;
            state.timerSeconds = 0;
            renderPractice(true);
            await navigate("practice");
            elements.replayButton.focus({preventScroll: true});
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
            state.wordAttemptStartedAt.clear();
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
        elements.playbackSpeed.disabled = state.speaking;
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
            const index = Number(input.dataset.wordIndex);
            state.inputInteractionRevision++;
            state.activeWordIndex = index;
            if (!state.wordAttemptStartedAt.has(index)) {
                state.wordAttemptStartedAt.set(index, Date.now());
            }
            updatePracticeControls();
        }
    }

    function onWordInput(event) {
        const input = event.target.closest("input[data-word-index]");
        if (!input) return;
        const index = Number(input.dataset.wordIndex);
        state.inputInteractionRevision++;
        state.activeWordIndex = index;
        if (!state.wordAttemptStartedAt.has(index)) {
            state.wordAttemptStartedAt.set(index, Date.now());
        }
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
        state.inputInteractionRevision++;
        if (event.key === "Backspace" && !input.value && input.selectionStart === 0 && input.selectionEnd === 0) {
            const previous = [...elements.wordSlots.querySelectorAll("input[data-word-index]:not(:disabled):not([readonly])")]
                .reverse()
                .find(candidate => Number(candidate.dataset.wordIndex) < index);
            if (previous) {
                event.preventDefault();
                focusWord(Number(previous.dataset.wordIndex), false, true);
            }
        } else if (event.key === " ") {
            event.preventDefault();
            window.clearTimeout(input._checkTimer);
            if (state.sentence.words[index]?.correct) {
                focusNextWord(index);
            } else if (input.value.trim()) {
                const check = checkWord(index, input.value);
                focusNextWord(index);
                await check;
            } else {
                focusNextWord(index);
            }
        } else if (event.key === "Enter") {
            if (event.isComposing) return;
            event.preventDefault();
            window.clearTimeout(input._checkTimer);
            if (state.sentence.canComplete) {
                await completeSentence(event);
            } else if (state.checkingWords.has(index)) {
                state.completeAfterPendingCheck = true;
                setPracticeStatus("Đang kiểm tra từ cuối…");
            } else if (input.value.trim() && !state.sentence.words[index]?.correct) {
                state.completeAfterPendingCheck = true;
                const correct = await checkWord(index, input.value);
                if (!correct) state.completeAfterPendingCheck = false;
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
        const sessionId = state.session?.sessionId;
        const sentenceId = state.sentence?.sentenceId;
        const interactionRevision = state.inputInteractionRevision;
        const attemptStartedAt = state.wordAttemptStartedAt.get(index) || Date.now();
        const responseTimeMs = Math.min(300000, Math.max(0, Date.now() - attemptStartedAt));
        try {
            const sentence = await queuePracticeMutation(practicePath(`/words/${index}/checks`), {
                method: "POST",
                body: {answer: answer.trim(), responseTimeMs}
            });
            if (state.session?.sessionId !== sessionId || state.sentence?.sentenceId !== sentenceId) {
                return false;
            }
            const focusBeforeRender = captureWordFocus();
            const userContinuedTyping = state.inputInteractionRevision !== interactionRevision
                || (focusBeforeRender && focusBeforeRender.index !== index);
            state.sentence = sentence;
            const correct = state.sentence.words[index].correct;
            if (correct || !userContinuedTyping) {
                state.wordDrafts.delete(index);
            }
            if (correct) {
                state.wordAttemptStartedAt.delete(index);
            } else {
                state.wordAttemptStartedAt.set(index, Date.now());
            }
            renderPractice(false);
            setPracticeStatus(correct ? `Từ ${index + 1} chính xác.` : `Từ ${index + 1} chưa khớp. Hãy nghe lại hoặc phân loại lỗi.`, !correct);
            if (focusBeforeRender && userContinuedTyping) {
                restoreWordFocus(focusBeforeRender);
            } else if (focusBeforeRender) {
                if (correct) {
                    focusNextWord(index);
                } else {
                    focusWord(index, true);
                }
            }
            if (correct && state.sentence.canComplete && state.completeAfterPendingCheck) {
                state.completeAfterPendingCheck = false;
                await completeSentence();
            }
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
            state.sentence = await queuePracticeMutation(practicePath("/hints"), {
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
            state.sentence = await queuePracticeMutation(practicePath("/classifications"), {
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
        const playbackSpeed = state.playbackSpeed;
        const completedBeforePlayback = state.sentence.canComplete;
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
            state.sentence = await queuePracticeMutation(practicePath("/replay-events"), {
                method: "POST",
                body: {event: "STARTED", playbackSpeed}
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
                    state.sentence.audioEndSeconds,
                    playbackSpeed
                );
            } else {
                await speak(state.sentence.speechText, state.sentence.speechRate * playbackSpeed);
            }
            state.sentence = await queuePracticeMutation(practicePath("/replay-events"), {
                method: "POST",
                body: {event: "COMPLETED", playbackSpeed}
            });
            setPracticeStatus(completedBeforePlayback
                ? "Đã nghe lại câu hoàn chỉnh. Lượt này không tính vào kết quả."
                : state.sentence.requiresReplayAfterReveal
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

    async function playRecordedAudio(url, startSeconds, endSeconds, playbackSpeed = 1) {
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
        source.playbackRate.value = playbackSpeed;
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
        event?.preventDefault();
        if (!state.sentence?.canComplete || state.speaking || state.completingSentence) return;
        state.completingSentence = true;
        state.completeAfterPendingCheck = false;
        elements.nextSentence.disabled = true;
        try {
            const response = await queuePracticeMutation(practicePath("/complete"), {
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
                state.inputInteractionRevision = 0;
                state.completeAfterPendingCheck = false;
                renderPractice(true);
                elements.replayButton.focus({preventScroll: true});
            }
        } catch (error) {
            setPracticeStatus(error.message, true);
            elements.nextSentence.disabled = false;
        } finally {
            state.completingSentence = false;
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
        renderAssessment(elements.resultAssessment, result.assessment);
        const independentAccuracy = result.assessment?.metrics?.unassistedFirstPassAccuracy
            ?? result.firstAttemptAccuracy;
        document.querySelector("#result-message").textContent = independentAccuracy >= 85
            ? "Bạn nhận âm rất chắc ngay lần nghe độc lập. Hệ thống sẽ cân nhắc tăng độ khó."
            : independentAccuracy >= 60
                ? "Nền tảng đang tốt dần. Hãy chú ý những từ quen nhưng thường bị nuốt âm."
                : result.firstAttemptAccuracy >= 85
                    ? "Bạn hoàn thành chính xác, nhưng đã dùng nghe chậm, replay hoặc trợ giúp. Hãy thử một bài mới ở tốc độ chuẩn."
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
            ${escapeHtml(review.topic)} · ${review.totalSentences} câu · ${review.totalWords} từ ·
            ${review.speechRateWpm ? `≈ ${review.speechRateWpm} từ/phút · ` : ""}
            ${review.speakerCount || "?"} người nói · ${escapeHtml(review.accentProfile || "chưa gắn nhãn giọng")} · ${sourceLink}
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
                            <p>${sentence.replayCount} lượt nghe · tốc độ TB ${Number(sentence.averagePlaybackSpeed || 1).toFixed(2)}× · ${formatTime(sentence.timeSpentSeconds)} · ${escapeHtml(helpSummary)}</p>
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
                                    ${word.inferredErrorCategory ? `<small>${escapeHtml(errorCategoryLabel(word.inferredErrorCategory))}</small>` : ""}
                                    ${word.attempts?.length ? `<small>${word.attempts.map(item => `#${item.attemptNumber} “${escapeHtml(item.rawAnswer)}” · ${item.responseTimeMs} ms`).join(" | ")}</small>` : ""}
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
        let copied = false;
        try {
            if (navigator.clipboard?.writeText && window.isSecureContext) {
                await navigator.clipboard.writeText(report);
                copied = true;
            } else {
                copied = copyTextWithSelection(report);
            }
        } catch (_error) {
            try {
                copied = copyTextWithSelection(report);
            } catch (_fallbackError) {
                copied = false;
            }
        }
        try {
            if (copied) {
                elements.copyReportStatus.textContent = "Đã sao chép. Bạn có thể dán thẳng vào ChatGPT.";
                return;
            }
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

    function copyTextWithSelection(text) {
        const textArea = document.createElement("textarea");
        textArea.value = text;
        textArea.setAttribute("readonly", "");
        textArea.setAttribute("aria-hidden", "true");
        textArea.style.position = "fixed";
        textArea.style.inset = "0 auto auto -9999px";
        textArea.style.opacity = "0";
        document.body.appendChild(textArea);
        textArea.focus({preventScroll: true});
        textArea.select();
        textArea.setSelectionRange(0, textArea.value.length);
        let copied = false;
        try {
            copied = document.execCommand("copy");
        } finally {
            textArea.remove();
        }
        return copied;
    }

    function buildAnalysisReport(result) {
        const review = result.review;
        const allWords = review.sentences.flatMap(sentence => sentence.words);
        const totalAudioSeconds = [...state.reviewAudioDurations.values()].reduce((sum, value) => sum + value, 0);
        const wordsPerMinute = review.speechRateWpm
            || (totalAudioSeconds > 0 ? Math.round(review.totalWords * 60 / totalAudioSeconds) : null);
        const outcomeSummary = Object.entries(outcomeLabels)
            .map(([outcome, label]) => `${label}: ${allWords.filter(word => word.outcome === outcome).length}`)
            .join("; ");
        const lines = [
            "BÁO CÁO LUYỆN NGHE DICTALAB",
            `Người học: ${state.currentUser?.displayName || "Không cung cấp"}`,
            `Bài: ${result.lessonTitle}`,
            `CEFR do nguồn gắn nhãn: ${result.level}`,
            `Chủ điểm: ${review.topic}`,
            `Người nói/giọng: ${review.speakerCount || "chưa rõ"}; ${review.accentProfile || "chưa gắn nhãn"}`,
            `Độ khó metadata (1–5): từ vựng ${review.vocabularyDifficulty}; ngữ pháp ${review.grammarDifficulty}; câu ${review.sentenceDifficulty}; hiệu chỉnh ${review.assessmentCalibrated ? "có" : "chưa"}`,
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
            result.assessment?.estimatedLevel
                ? `Ước lượng nghe: ${result.assessment.estimatedLevel}; độ tin cậy ${formatPercent(result.assessment.confidence)}%; bằng chứng ${result.assessment.evidenceLessons}/${result.assessment.requiredLessons} bài mới`
                : "Ước lượng nghe: chưa đủ dữ liệu",
            result.assessment ? `Tín hiệu độc lập: lần đầu không trợ giúp ${formatPercent(result.assessment.metrics.unassistedFirstPassAccuracy)}%; từ chức năng ${formatPercent(result.assessment.metrics.functionWordAccuracy)}%; từ nội dung ${formatPercent(result.assessment.metrics.contentWordAccuracy)}%` : "",
            `Kết quả theo từ: ${outcomeSummary}`,
            "",
            "CHI TIẾT TỪNG CÂU"
        ];
        review.sentences.forEach(sentence => {
            const duration = state.reviewAudioDurations.get(sentence.sentenceId);
            lines.push("", `Câu ${sentence.position} — replay ${sentence.replayCount}; tốc độ TB ${Number(sentence.averagePlaybackSpeed || 1).toFixed(2)}x; làm ${formatTime(sentence.timeSpentSeconds)}`
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
                            word.errorType === "KNOWN_BUT_MISSED" ? "biết nhưng nghe lỡ" : null,
                        word.inferredErrorCategory ? errorCategoryLabel(word.inferredErrorCategory) : null
                    ].filter(Boolean).join("; ");
                    lines.push(`- ${word.expectedWord}: ${details}`);
                    if (word.attempts?.length) {
                        lines.push(`  Lịch sử: ${word.attempts.map(item => `#${item.attemptNumber} “${item.rawAnswer}” (${item.responseTimeMs} ms${item.errorCategory ? `, ${errorCategoryLabel(item.errorCategory)}` : ""})`).join(" | ")}`);
                    }
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
        renderAssessment(elements.dashboardAssessment, dashboard.assessment);
        renderRecommendation(dashboard.recommendation);
    }

    function renderAssessment(container, assessment) {
        if (!assessment?.estimatedLevel) {
            container.innerHTML = `
                <div class="assessment-heading"><div><p class="section-index">Ước lượng năng lực nghe</p>
                <h2>Chưa đủ dữ liệu</h2></div></div>
                <p class="assessment-summary">${escapeHtml(assessment?.summary || "Hoàn thành một bài mới để bắt đầu.")}</p>`;
            return;
        }
        const metrics = assessment.metrics;
        const errors = assessment.errorCategories;
        const errorItems = [
            ["Bỏ sót", errors.omission], ["Chèn từ", errors.insertion],
            ["Thay từ", errors.substitution], ["Chính tả", errors.spelling],
            ["Biến thể từ", errors.wordForm], ["Sai thứ tự", errors.wordOrder]
        ];
        container.innerHTML = `
            <div class="assessment-heading">
                <div>
                    <p class="section-index">Ước lượng năng lực nghe</p>
                    <h2>${escapeHtml(assessment.estimatedLevel)}</h2>
                </div>
                <div class="assessment-confidence">
                    <strong>${formatPercent(assessment.confidence)}%</strong>
                    <span>Tin cậy ${escapeHtml(assessment.confidenceBand.toLowerCase())}</span>
                </div>
            </div>
            <p class="assessment-summary">${escapeHtml(assessment.summary)}</p>
            <div class="assessment-evidence">
                <span class="assessment-badge${assessment.provisional ? " is-provisional" : ""}">${assessment.provisional ? "Ước lượng tạm thời" : "Đủ bằng chứng"}</span>
                <span>${assessment.evidenceLessons}/${assessment.requiredLessons} bài chưa từng học · ${assessment.calibratedLessons} bài đã hiệu chỉnh</span>
                <small>Chẩn đoán nội bộ, không phải chứng chỉ CEFR chuẩn hóa.</small>
            </div>
            <div class="assessment-metrics">
                ${assessmentMetric("Lần đầu không trợ giúp", metrics.unassistedFirstPassAccuracy, "%")}
                ${assessmentMetric("Từ chức năng", metrics.functionWordsObserved ? metrics.functionWordAccuracy : null, "%")}
                ${assessmentMetric("Từ nội dung", metrics.contentWordsObserved ? metrics.contentWordAccuracy : null, "%")}
                ${assessmentMetric("Replay / câu", metrics.averageReplayPerSentence, "")}
                ${assessmentMetric("Phụ thuộc gợi ý", metrics.hintDependencyRate, "%")}
                ${assessmentMetric("Phụ thuộc nghe chậm", metrics.slowPlaybackDependencyRate, "%")}
                ${assessmentMetric("Tỷ lệ xem đáp án", metrics.revealRate, "%")}
                ${assessmentMetric("Phản hồi TB", metrics.averageResponseTimeMs, " ms")}
            </div>
            <details class="assessment-errors">
                <summary>Phân loại lỗi tự động</summary>
                <div>${errorItems.map(([label, value]) => `<span><strong>${value}</strong>${escapeHtml(label)}</span>`).join("")}</div>
            </details>`;
    }

    function assessmentMetric(label, value, suffix) {
        if (value === null || value === undefined) {
            return `<span><strong>—</strong><small>${escapeHtml(label)} · chưa có mẫu</small></span>`;
        }
        const shown = suffix === "%" ? formatPercent(value) : suffix === "" ? Number(value).toFixed(1) : Math.round(value);
        return `<span><strong>${escapeHtml(String(shown))}${suffix}</strong><small>${escapeHtml(label)}</small></span>`;
    }

    function errorCategoryLabel(category) {
        return ({
            OMISSION: "Bỏ sót", INSERTION: "Chèn từ", SUBSTITUTION: "Thay từ",
            SPELLING: "Sai chính tả", WORD_FORM: "Sai biến thể từ", WORD_ORDER: "Sai thứ tự từ"
        })[category] || category;
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

    function captureWordFocus() {
        const input = document.activeElement?.closest?.("input[data-word-index]");
        if (!input || !elements.wordSlots.contains(input)) return null;
        return {
            index: Number(input.dataset.wordIndex),
            selectionStart: input.selectionStart,
            selectionEnd: input.selectionEnd
        };
    }

    function restoreWordFocus(focus) {
        window.requestAnimationFrame(() => {
            const input = elements.wordSlots.querySelector(`input[data-word-index="${focus.index}"]`);
            if (!input || input.disabled) return;
            input.focus({preventScroll: true});
            const end = input.value.length;
            const start = Math.min(focus.selectionStart ?? end, end);
            const selectionEnd = Math.min(focus.selectionEnd ?? start, end);
            input.setSelectionRange(start, selectionEnd);
            updatePracticeControls();
        });
    }

    function focusWord(index, select, caretAtEnd = false) {
        state.activeWordIndex = index;
        window.requestAnimationFrame(() => {
            const input = elements.wordSlots.querySelector(`input[data-word-index="${index}"]`);
            if (input && !input.disabled) {
                input.focus({preventScroll: true});
                if (select) input.select();
                else if (caretAtEnd) input.setSelectionRange(input.value.length, input.value.length);
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

    async function refreshAdminLessons(preferredLessonId = null) {
        if (state.currentUser?.role !== "ADMIN") return;
        try {
            state.adminLessons = await api("/api/admin/lessons");
            if (preferredLessonId !== null) {
                state.selectedAdminLessonId = preferredLessonId;
                state.adminEditingNew = false;
            }
            renderAdminLessonList();
            if (!state.adminEditingNew && state.selectedAdminLessonId !== null) {
                try {
                    state.adminSelectedLesson = await api(`/api/admin/lessons/${state.selectedAdminLessonId}`);
                    renderAdminEditor(state.adminSelectedLesson);
                } catch (_error) {
                    hideAdminEditor();
                }
            }
        } catch (error) {
            elements.adminLessonList.innerHTML = `<p class="admin-help">${escapeHtml(error.message)}</p>`;
        }
    }

    function renderAdminLessonList() {
        const query = elements.adminLessonSearch.value.trim().toLowerCase();
        const lessons = state.adminLessons.filter(lesson => !query || [
            lesson.title, lesson.slug, lesson.level, lesson.topic
        ].some(value => String(value).toLowerCase().includes(query)));
        elements.adminLessonList.innerHTML = lessons.length ? lessons.map(lesson => `
            <button class="admin-lesson-item${lesson.id === state.selectedAdminLessonId ? " is-selected" : ""}"
                    type="button" data-admin-lesson-id="${lesson.id}">
                <span class="admin-lesson-meta">
                    <span>${escapeHtml(lesson.level)} · ${lesson.sentenceCount} câu</span>
                    <span class="${lesson.published ? "is-published" : ""}">${lesson.published ? "Đã xuất bản" : "Bản nháp"}</span>
                </span>
                <strong>${escapeHtml(lesson.title)}</strong>
                <small>${escapeHtml(lesson.slug)}</small>
            </button>
        `).join("") : '<p class="admin-help">Không có bài nào khớp tìm kiếm.</p>';
    }

    function selectedAdminLesson() {
        return state.adminSelectedLesson?.id === state.selectedAdminLessonId ? state.adminSelectedLesson : null;
    }

    async function selectAdminLesson(lessonId) {
        const lesson = state.adminLessons.find(item => item.id === lessonId);
        if (!lesson) return;
        state.selectedAdminLessonId = lessonId;
        state.adminEditingNew = false;
        renderAdminLessonList();
        try {
            state.adminSelectedLesson = await api(`/api/admin/lessons/${lessonId}`);
            renderAdminEditor(state.adminSelectedLesson);
            elements.adminEditorTitle.focus({preventScroll: true});
        } catch (error) {
            setAdminStatus(error.message, true);
        }
    }

    function showNewLessonEditor() {
        state.selectedAdminLessonId = null;
        state.adminSelectedLesson = null;
        state.adminEditingNew = true;
        renderAdminLessonList();
        elements.adminEmptyState.hidden = true;
        elements.adminEditorContent.hidden = false;
        elements.adminEditorKicker.textContent = "Bài mới · lưu dưới dạng bản nháp";
        elements.adminEditorTitle.textContent = "Thông tin bài nghe";
        elements.adminPublicationBadge.textContent = "Chưa lưu";
        elements.adminPublicationBadge.className = "publication-badge";
        elements.adminLockNotice.hidden = true;
        elements.adminLessonForm.reset();
        elements.adminLessonForm.elements.estimatedMinutes.value = "5";
        elements.adminLessonForm.elements.displayOrder.value = String(nextAdminDisplayOrder());
        elements.adminLessonForm.elements.speakerCount.value = "0";
        elements.adminLessonForm.elements.vocabularyDifficulty.value = "1";
        elements.adminLessonForm.elements.grammarDifficulty.value = "1";
        elements.adminLessonForm.elements.sentenceDifficulty.value = "1";
        elements.adminLessonForm.elements.slug.disabled = false;
        elements.adminCancelCreate.hidden = false;
        elements.adminSentenceSection.hidden = true;
        elements.adminPublicationButton.closest(".admin-publish-bar").hidden = true;
        setAdminStatus("");
        window.requestAnimationFrame(() => elements.adminLessonForm.elements.slug.focus());
    }

    function hideAdminEditor() {
        state.adminEditingNew = false;
        state.selectedAdminLessonId = null;
        state.adminSelectedLesson = null;
        elements.adminEditorContent.hidden = true;
        elements.adminEmptyState.hidden = false;
        renderAdminLessonList();
    }

    function nextAdminDisplayOrder() {
        return Math.max(0, ...state.adminLessons.map(lesson => Number(lesson.displayOrder) || 0)) + 10;
    }

    function renderAdminEditor(lesson) {
        elements.adminEmptyState.hidden = true;
        elements.adminEditorContent.hidden = false;
        elements.adminEditorKicker.textContent = `${lesson.contentOrigin === "ADMIN_UPLOAD" ? "Nội dung tải lên" : "Nội dung đóng gói"} · ${lesson.slug}`;
        elements.adminEditorTitle.textContent = lesson.title;
        elements.adminPublicationBadge.textContent = lesson.published ? "Đã xuất bản" : "Bản nháp";
        elements.adminPublicationBadge.className = `publication-badge${lesson.published ? " is-published" : ""}`;

        const form = elements.adminLessonForm.elements;
        form.slug.value = lesson.slug;
        form.slug.disabled = true;
        form.title.value = lesson.title;
        form.description.value = lesson.description;
        form.level.value = lesson.level;
        form.topic.value = lesson.topic;
        form.estimatedMinutes.value = String(lesson.estimatedMinutes);
        form.displayOrder.value = String(lesson.displayOrder);
        form.speakerCount.value = String(lesson.speakerCount ?? 0);
        form.accentProfile.value = lesson.accentProfile || "";
        form.vocabularyDifficulty.value = String(lesson.vocabularyDifficulty);
        form.grammarDifficulty.value = String(lesson.grammarDifficulty);
        form.sentenceDifficulty.value = String(lesson.sentenceDifficulty);
        elements.adminCancelCreate.hidden = true;
        elements.adminSentenceSection.hidden = false;
        elements.adminPublicationButton.closest(".admin-publish-bar").hidden = false;

        const temporarilyLocked = lesson.published && !lesson.contentLocked;
        elements.adminLockNotice.hidden = !(lesson.contentLocked || temporarilyLocked);
        if (lesson.contentLocked) {
            elements.adminLockNotice.textContent = "Bài đã có lịch sử học. Transcript, audio và thứ tự câu được khóa để kết quả cũ không bị thay đổi. Bạn vẫn có thể sửa tên/mô tả hoặc gỡ xuất bản.";
        } else if (temporarilyLocked) {
            elements.adminLockNotice.textContent = "Bài đang được xuất bản. Hãy gỡ xuất bản trước khi sửa câu hoặc thay audio.";
        }
        renderAdminSentences(lesson);
        renderAdminPublication(lesson);
        setAdminStatus("");
    }

    function renderAdminSentences(lesson) {
        const locked = lesson.contentLocked || lesson.published;
        elements.adminSentenceCount.textContent = `${lesson.sentences.length} câu`;
        const addControls = [...elements.adminAddSentenceForm.elements];
        addControls.forEach(control => control.disabled = locked);
        elements.adminSentenceList.innerHTML = lesson.sentences.length ? lesson.sentences.map((sentence, index) => {
            const audioReady = Boolean(sentence.audioUrl);
            const segment = sentence.audioStartSeconds !== null && sentence.audioEndSeconds !== null
                ? ` · đoạn ${Number(sentence.audioStartSeconds).toFixed(2)}–${Number(sentence.audioEndSeconds).toFixed(2)}s`
                : "";
            return `
                <article class="admin-sentence-card" data-sentence-id="${sentence.id}">
                    <span class="admin-sentence-number" aria-label="Câu ${sentence.position}">${sentence.position}</span>
                    <div class="admin-sentence-body">
                        <label class="sr-only" for="admin-transcript-${sentence.id}">Transcript câu ${sentence.position}</label>
                        <textarea id="admin-transcript-${sentence.id}" rows="2" maxlength="1000"
                                  ${locked ? "disabled" : ""}>${escapeHtml(sentence.transcript)}</textarea>
                        <div class="admin-sentence-audio">
                            <span class="audio-state${audioReady ? " is-ready" : ""}">
                                ${audioReady ? `● Có audio${escapeHtml(segment)}` : "○ Chưa có audio"}
                            </span>
                            <button class="admin-icon-button" type="button" data-admin-action="preview"
                                    ${audioReady ? "" : "disabled"}>▶ Nghe thử</button>
                            <label class="admin-file-label${locked ? " is-disabled" : ""}">
                                ${audioReady ? "Thay audio" : "Tải audio"}
                                <input type="file" accept=".mp3,.m4a,.wav,audio/mpeg,audio/mp4,audio/wav"
                                       data-admin-action="upload" ${locked ? "disabled" : ""}>
                            </label>
                            <button class="admin-icon-button" type="button" data-admin-action="save"
                                    ${locked ? "disabled" : ""}>Lưu câu</button>
                            <button class="admin-icon-button" type="button" data-admin-action="up"
                                    ${locked || index === 0 ? "disabled" : ""} aria-label="Đưa câu ${sentence.position} lên">↑</button>
                            <button class="admin-icon-button" type="button" data-admin-action="down"
                                    ${locked || index === lesson.sentences.length - 1 ? "disabled" : ""}
                                    aria-label="Đưa câu ${sentence.position} xuống">↓</button>
                            <button class="admin-icon-button danger" type="button" data-admin-action="delete"
                                    ${locked ? "disabled" : ""}>Xóa</button>
                        </div>
                    </div>
                </article>`;
        }).join("") : '<p class="admin-help">Chưa có câu nào. Thêm transcript đầu tiên ở phía trên.</p>';
    }

    function renderAdminPublication(lesson) {
        const ready = lesson.sentences.length > 0 && lesson.sentences.every(sentence => Boolean(sentence.audioUrl));
        if (lesson.published) {
            elements.adminPublishTitle.textContent = "Bài đang hiển thị với người học";
            elements.adminPublishHelp.textContent = "Gỡ xuất bản sẽ ẩn bài khỏi danh sách nhưng không xóa lịch sử học.";
            elements.adminPublicationButton.textContent = "Gỡ xuất bản";
            elements.adminPublicationButton.disabled = false;
        } else {
            elements.adminPublishTitle.textContent = "Chưa hiển thị với người học";
            elements.adminPublishHelp.textContent = ready
                ? "Bài đã đủ transcript và audio. Bạn có thể xuất bản ngay."
                : "Thêm ít nhất một câu và audio cho mọi câu để xuất bản.";
            elements.adminPublicationButton.textContent = "Xuất bản bài";
            elements.adminPublicationButton.disabled = !ready;
        }
    }

    async function saveAdminLesson(event) {
        event.preventDefault();
        const form = elements.adminLessonForm.elements;
        const common = {
            title: form.title.value.trim(),
            description: form.description.value.trim(),
            level: form.level.value,
            topic: form.topic.value.trim(),
            estimatedMinutes: Number(form.estimatedMinutes.value),
            displayOrder: Number(form.displayOrder.value),
            speakerCount: Number(form.speakerCount.value),
            accentProfile: form.accentProfile.value.trim(),
            vocabularyDifficulty: Number(form.vocabularyDifficulty.value),
            grammarDifficulty: Number(form.grammarDifficulty.value),
            sentenceDifficulty: Number(form.sentenceDifficulty.value)
        };
        const creating = state.adminEditingNew;
        const path = creating ? "/api/admin/lessons" : `/api/admin/lessons/${state.selectedAdminLessonId}`;
        const body = creating ? {slug: form.slug.value.trim(), ...common} : common;
        setAdminStatus(creating ? "Đang tạo bản nháp…" : "Đang lưu thông tin…");
        try {
            const updated = await api(path, {method: creating ? "POST" : "PUT", body});
            state.adminEditingNew = false;
            await refreshAdminLessons(updated.id);
            setAdminStatus(creating ? "Đã tạo bản nháp. Bây giờ hãy thêm từng câu và audio." : "Đã lưu thông tin bài.");
        } catch (error) {
            setAdminStatus(error.message, true);
        }
    }

    async function addAdminSentence(event) {
        event.preventDefault();
        const lesson = selectedAdminLesson();
        if (!lesson) return;
        const textarea = elements.adminAddSentenceForm.elements.transcript;
        setAdminStatus("Đang thêm câu…");
        try {
            const updated = await api(`/api/admin/lessons/${lesson.id}/sentences`, {
                method: "POST",
                body: {transcript: textarea.value.trim(), speechRate: 1.0, audioStartSeconds: null, audioEndSeconds: null}
            });
            textarea.value = "";
            await refreshAdminLessons(updated.id);
            setAdminStatus("Đã thêm câu. Hãy tải audio chứa đúng câu này.");
            textarea.focus();
        } catch (error) {
            setAdminStatus(error.message, true);
        }
    }

    async function handleAdminSentenceAction(event) {
        const button = event.target.closest("[data-admin-action]");
        if (!button || button.dataset.adminAction === "upload") return;
        const card = button.closest("[data-sentence-id]");
        const lesson = selectedAdminLesson();
        if (!card || !lesson) return;
        const sentenceId = Number(card.dataset.sentenceId);
        const sentence = lesson.sentences.find(item => item.id === sentenceId);
        if (!sentence) return;
        const action = button.dataset.adminAction;

        if (action === "preview") {
            setAdminStatus(`Đang phát câu ${sentence.position}…`);
            try {
                await playRecordedAudio(sentence.audioUrl, sentence.audioStartSeconds, sentence.audioEndSeconds);
                setAdminStatus("Đã phát xong audio.");
            } catch (error) {
                if (error.name !== "AbortError") setAdminStatus(error.message, true);
            }
            return;
        }

        if (action === "delete" && !window.confirm(`Xóa câu ${sentence.position}? Audio tải lên của câu này cũng sẽ bị xóa.`)) {
            return;
        }
        button.disabled = true;
        try {
            let updated;
            if (action === "save") {
                const transcript = card.querySelector("textarea").value.trim();
                updated = await api(`/api/admin/lessons/${lesson.id}/sentences/${sentenceId}`, {
                    method: "PUT",
                    body: {
                        transcript,
                        speechRate: sentence.speechRate || 1.0,
                        audioStartSeconds: sentence.audioStartSeconds,
                        audioEndSeconds: sentence.audioEndSeconds
                    }
                });
            } else if (action === "delete") {
                updated = await api(`/api/admin/lessons/${lesson.id}/sentences/${sentenceId}`, {method: "DELETE"});
            } else if (action === "up" || action === "down") {
                const ids = lesson.sentences.map(item => item.id);
                const from = ids.indexOf(sentenceId);
                const to = action === "up" ? from - 1 : from + 1;
                [ids[from], ids[to]] = [ids[to], ids[from]];
                updated = await api(`/api/admin/lessons/${lesson.id}/sentences/order`, {
                    method: "PUT", body: {sentenceIds: ids}
                });
            }
            if (updated) {
                await refreshAdminLessons(updated.id);
                setAdminStatus(action === "delete" ? "Đã xóa câu." : "Đã lưu thay đổi câu.");
            }
        } catch (error) {
            setAdminStatus(error.message, true);
            button.disabled = false;
        }
    }

    async function handleAdminAudioUpload(event) {
        const input = event.target.closest("input[data-admin-action='upload']");
        const file = input?.files?.[0];
        const lesson = selectedAdminLesson();
        const card = input?.closest("[data-sentence-id]");
        if (!file || !lesson || !card) return;
        const sentenceId = Number(card.dataset.sentenceId);
        const formData = new FormData();
        formData.append("file", file);
        input.disabled = true;
        setAdminStatus(`Đang tải ${file.name}…`);
        try {
            const updated = await api(`/api/admin/lessons/${lesson.id}/sentences/${sentenceId}/audio`, {
                method: "POST", formData
            });
            state.audioBufferCache.clear();
            await refreshAdminLessons(updated.id);
            setAdminStatus("Audio đã được lưu bền vững trên máy chủ.");
        } catch (error) {
            setAdminStatus(error.message, true);
            input.disabled = false;
            input.value = "";
        }
    }

    async function toggleAdminPublication() {
        const lesson = selectedAdminLesson();
        if (!lesson) return;
        elements.adminPublicationButton.disabled = true;
        try {
            const updated = await api(`/api/admin/lessons/${lesson.id}/publication`, {
                method: "PUT", body: {published: !lesson.published}
            });
            await refreshAdminLessons(updated.id);
            await selectLevel(state.selectedLevel);
            setAdminStatus(updated.published
                ? "Đã xuất bản. Người học có thể thấy bài ngay bây giờ."
                : "Đã gỡ xuất bản. Lịch sử học vẫn được giữ nguyên.");
        } catch (error) {
            setAdminStatus(error.message, true);
            elements.adminPublicationButton.disabled = false;
        }
    }

    function setAdminStatus(message, error = false) {
        elements.adminStatus.textContent = message;
        elements.adminStatus.classList.toggle("is-error", error);
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
        if (options.formData !== undefined) {
            request.body = options.formData;
        } else if (options.body !== undefined) {
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

    function queuePracticeMutation(path, options) {
        const request = state.practiceMutationQueue
            .catch(() => undefined)
            .then(() => api(path, options));
        state.practiceMutationQueue = request.then(() => undefined, () => undefined);
        return request;
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
