(() => {
    "use strict";
    const isAdminArea = document.body.dataset.area === "admin";

    // ======================================================================
    // ROUTER — History API (pushState / replaceState / popstate)
    // Không phải framework; chỉ map URL ↔ màn hình.
    // ======================================================================
    const router = {
        /** Danh sách level hợp lệ cho catalog và history */
        validLevels: new Set(["A1", "A2", "B1", "B2", "all"]),

        /**
         * Phân tích pathname + search → { route, params, query }
         * route: key nội bộ (catalog, practice, history, dashboard, room, admin, login, register)
         * params: object chứa các path segment (sessionId, roomId, inviteCode, lessonId)
         * query: URLSearchParams
         */
        parse(pathname, search) {
            const q = new URLSearchParams(search);
            const page = q.get("page") || "1";
            const seg = pathname.replace(/^\//, "").split("/");
            const s0 = seg[0] || "";
            const s1 = seg[1] || "";
            const s2 = seg[2] || "";

            if ((s0 === "admin" && !s1) || s0 === "admin.html") return { route: "admin", params: {page}, query: q };
            if ((s0 === "admin" && s1 === "listening") || (s0 === "listening" && q.get("view") === "admin")) {
                return {route: "admin-listening", params: {edit: q.get("edit") || "", page}, query: q};
            }

            if (s0 === "" || s0 === "login")  return { route: "login",    params: {}, query: q };
            if (s0 === "register")             return { route: "register", params: {}, query: q };
            if (s0 === "reset-password" || s0 === "verify-email") return { route: s0, params: {}, query: q };
            if (s0 === "account" || s0 === "support") return { route: s0, params: {}, query: q };
            if (s0 === "admin" && (s1 === "users" || s1 === "support")) return { route: `admin-${s1}`, params: {}, query: q };
            if (s0 === "catalog")              return { route: "catalog",  params: { level: q.get("level") || "A1", page }, query: q };
            if (s0 === "listening")            return { route: "listening", params: { sessionId: s1 || null, page, level: q.get("level") || "", view: q.get("view") || "", edit: q.get("edit") || "" }, query: q };
            if (s0 === "conversation")         return { route: "conversation", params: { sessionId: s1 || null }, query: q };
            if (s0 === "dashboard")            return { route: "dashboard", params: {}, query: q };
            if (s0 === "practice" && s1)       return { route: "practice", params: { sessionId: s1 }, query: q };
            if (s0 === "history" && !s1)       return { route: "history",  params: { level: q.get("level") || "all", page }, query: q };
            if (s0 === "history" && s1)        return { route: "result",   params: { sessionId: s1 }, query: q };
            if (s0 === "rooms" && !s1)         return { route: "room",     params: {}, query: q };
            if (s0 === "rooms" && s1 === "join" && s2) return { route: "room-join", params: { inviteCode: s2 }, query: q };
            if (s0 === "rooms" && s1)          return { route: "room-id",  params: { roomId: s1 }, query: q };
            if (s0 === "admin" && s1 === "lessons" && !s2) return { route: "admin", params: {page}, query: q };
            if (s0 === "admin" && s1 === "lessons" && s2 === "new") return { route: "admin-new", params: {}, query: q };
            if (s0 === "admin" && s1 === "lessons" && s2) return { route: "admin-lesson", params: { lessonId: Number(s2), page }, query: q };

            // URL không hợp lệ
            return { route: "not-found", params: {}, query: q };
        },

        /** Map route key + params → pathname để gọi pushState/replaceState */
        toPath(route, params = {}) {
            switch (route) {
                case "login":       return "/login";
                case "register":    return "/register";
                case "account":     return "/account";
                case "support":     return "/support";
                case "admin-users": return "/admin/users";
                case "admin-listening": return `/admin/listening?page=${encodeURIComponent(params.page || 1)}${params.edit ? `&edit=${encodeURIComponent(params.edit)}` : ""}`;
                case "admin-support": return "/admin/support";
                case "catalog":     return `/catalog?level=${encodeURIComponent(params.level || "A1")}&page=${encodeURIComponent(params.page || 1)}`;
                case "conversation": return params.sessionId ? `/conversation/${encodeURIComponent(params.sessionId)}` : "/conversation";
                case "listening": {
                    if (params.sessionId) {
                        const query = params.view ? `?view=${encodeURIComponent(params.view)}` : "";
                        return `/listening/${encodeURIComponent(params.sessionId)}${query}`;
                    }
                    return `/listening?${new URLSearchParams({level: params.level || "", page: params.page || 1, view: params.view || "", edit: params.edit || ""})}`;
                }
                case "practice":    return `/practice/${encodeURIComponent(params.sessionId || "")}`;
                case "result":      return `/history/${encodeURIComponent(params.sessionId || "")}`;
                case "history":     return `/history?level=${encodeURIComponent(params.level || "all")}&page=${encodeURIComponent(params.page || 1)}`;
                case "dashboard":   return "/dashboard";
                case "room":        return "/rooms";
                case "room-id":     return `/rooms/${encodeURIComponent(params.roomId || "")}`;
                case "room-join":   return `/rooms/join/${encodeURIComponent(params.inviteCode || "")}`;
                case "admin":       return `/admin/lessons?page=${encodeURIComponent(params.page || 1)}`;
                case "admin-new":   return "/admin/lessons/new";
                case "admin-lesson":return `/admin/lessons/${encodeURIComponent(params.lessonId || "")}?page=${encodeURIComponent(params.page || 1)}`;
                default:            return "/";
            }
        },

        push(route, params = {}) {
            const path = this.toPath(route, params);
            if (window.location.pathname + window.location.search !== path) {
                window.history.pushState({ route, params }, "", path);
            }
        },

        replace(route, params = {}) {
            const path = this.toPath(route, params);
            window.history.replaceState({ route, params }, "", path);
        }
    };

    // Paginate the existing API arrays without changing their response contracts.
    function paginateList(container, size = 6) {
        if (container.nextElementSibling?.matches(".pagination")) container.nextElementSibling.remove();
        const items = [...container.children].filter(item => !item.hidden);
        const count = Math.max(1, Math.ceil(items.length / size));
        const nav = document.createElement("nav");
        nav.className = "pagination";
        nav.setAttribute("aria-label", "Phân trang danh sách");
        container.after(nav);
        function render(requested, focus = false) {
            const page = Math.min(count, Math.max(1, Number.isSafeInteger(Number(requested)) ? Number(requested) : 1));
            items.forEach((item, index) => { item.hidden = index < (page - 1) * size || index >= page * size; });
            const url = new URL(window.location.href);
            url.searchParams.set("page", page);
            window.history.replaceState(window.history.state, "", url);
            nav.hidden = items.length <= size;
            nav.innerHTML = `<button type="button" class="text-button" data-page="${page - 1}" ${page === 1 ? "disabled" : ""}>← Trang trước</button>
                <span role="status" tabindex="-1">Trang ${page}/${count} · ${items.length} mục</span>
                <button type="button" class="text-button" data-page="${page + 1}" ${page === count ? "disabled" : ""}>Trang sau →</button>`;
            nav.querySelectorAll("button").forEach(button => button.addEventListener("click", () => render(button.dataset.page, true)));
            if (focus) {
                nav.querySelector("[role='status']").focus({preventScroll: true});
                container.scrollIntoView({block: "start"});
            }
        }
        render(new URLSearchParams(window.location.search).get("page"));
    }

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
        adminEditingNew: false,
        historySessions: [],
        historyPage: null,
        historyLoadGeneration: 0,
        historyFilter: "all",
        historyViewingSession: false,
        // --- Room (Luyện cùng bạn) ---
        room: null,              // thông tin phòng hiện tại
        roomInviteUrl: null,     // URL mời để copy
        roomWs: null,            // WebSocket instance
        roomPc: null,            // RTCPeerConnection
        roomLocalStream: null,   // MediaStream từ mic
        roomRemoteAudio: null,   // HTMLAudioElement cho remote stream
        roomMicEnabled: false,
        roomIsHost: false,
        roomWsReconnectCount: 0,
        roomWsReconnectTimer: null,
        roomPeerReady: false,
        roomMakingOffer: false,
        roomJoinAttempt: null,
        roomAudioCheckTimer: null,
        roomStatus: null,
        roomMediaStatus: null,
        roomPeerMicEnabled: null,
        roomPollingRequest: null,
        roomWsHeartbeatTimer: null,
        roomSpeechContext: null,
        roomSpeechMonitors: {},
        roomSpeechTimer: null,
        roomCallStartedAt: null,
        roomServerTimeOffset: 0,
        roomCallTimer: null,
        roomCallReadySent: false,
        roomStudy: null,
        roomPlayback: null,
        roomPlaybackSeen: 0,
        roomAudioEnabled: false,
        roomAudioPreparedKey: null,
        roomAudioPreparingKey: null,
        roomAudioClockOffset: null,
        roomStudySyncing: false,
        roomSharedSessionId: null,
        roomSharedCompleted: false,
        roomReady: false,
        roomDraftTimer: null,
        roomDraftSequence: 0,
        roomPeerDraftSequence: 0,
        roomDraftPosition: 0,
        roomPendingIce: [],       // ICE candidates đến trước remote description
        roomPollingTimer: null    // polling timer để check trạng thái phòng
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
        navLinks: [...document.querySelectorAll(".nav-link[data-route]")],
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
        replayCountLabel: document.querySelector("#replay-count-label"),
        roomEnableStudyAudio: document.querySelector("#room-enable-study-audio"),
        roomStudyAudioStatus: document.querySelector("#room-study-audio-status"),
        sentenceTimer: document.querySelector("#sentence-timer"),
        hintCount: document.querySelector("#hint-count"),
        speechSupportNote: document.querySelector("#speech-support-note"),
        wordCompletion: document.querySelector("#word-completion"),
        wordSlots: document.querySelector("#word-slots"),
        dictationCopyText: document.querySelector("#dictation-copy-text"),
        copyDictationSelection: document.querySelector("#copy-dictation-selection"),
        copyDictationAll: document.querySelector("#copy-dictation-all"),
        dictationCopyStatus: document.querySelector("#dictation-copy-status"),
        dictationForm: document.querySelector("#dictation-form"),
        hintLetter: document.querySelector("#hint-letter"),
        hintWord: document.querySelector("#hint-word"),
        revealSentence: document.querySelector("#reveal-sentence"),
        feedbackPanel: document.querySelector("#feedback-panel"),
        classifications: document.querySelector("#error-classifications"),
        practiceStatus: document.querySelector("#practice-status"),
        nextRequirement: document.querySelector("#next-requirement"),
        nextSentence: document.querySelector("#next-sentence"),
        sharedPracticeBar: document.querySelector("#shared-practice-bar"),
        sharedCallStatus: document.querySelector("#shared-call-status"),
        sharedCallTimer: document.querySelector("#shared-call-timer"),
        sharedHostAvatar: document.querySelector("#shared-host-avatar"),
        sharedGuestAvatar: document.querySelector("#shared-guest-avatar"),
        sharedMicButton: document.querySelector("#shared-mic-button"),
        sharedEnableAudio: document.querySelector("#shared-enable-audio"),
        sharedReconnect: document.querySelector("#shared-reconnect"),
        sharedRoomButton: document.querySelector("#shared-room-button"),
        peerPracticePanel: document.querySelector("#peer-practice-panel"),
        peerPracticeHeading: document.querySelector("#peer-practice-heading"),
        peerWordSlots: document.querySelector("#peer-word-slots"),
        peerPracticeStatus: document.querySelector("#peer-practice-status"),
        roomAdvanceSentence: document.querySelector("#room-advance-sentence"),
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
        adminStatus: document.querySelector("#admin-status"),
        historyLevelTabs: document.querySelector("#history-level-tabs"),
        historyList: document.querySelector("#history-list"),
        historyEmpty: document.querySelector("#history-empty"),
        historyCount: document.querySelector("#history-count"),
        // --- Room ---
        roomLobby: document.querySelector("#room-lobby"),
        roomCallPanel: document.querySelector("#room-call-panel"),
        roomPendingPanel: document.querySelector("#room-pending-panel"),
        roomCreateBtn: document.querySelector("#room-create-btn"),
        roomJoinForm: document.querySelector("#room-join-form"),
        roomInviteInput: document.querySelector("#room-invite-input"),
        roomLobbyStatus: document.querySelector("#room-lobby-status"),
        roomCode: document.querySelector("#room-code"),
        roomCopyInvite: document.querySelector("#room-copy-invite"),
        roomStudyStatus: document.querySelector("#room-study-status"),
        roomChooseLesson: document.querySelector("#room-choose-lesson"),
        roomReturnPractice: document.querySelector("#room-return-practice"),
        roomLeaveBtn: document.querySelector("#room-leave-btn"),
        roomStatusBadge: document.querySelector("#room-status-badge"),
        roomCallTimer: document.querySelector("#room-call-timer"),
        roomHostCard: document.querySelector("#room-member-host"),
        roomHostName: document.querySelector("#room-host-name"),
        roomHostTag: document.querySelector("#room-host-tag"),
        roomHostAvatar: document.querySelector("#room-host-avatar"),
        roomHostMicIndicator: document.querySelector("#room-host-mic-indicator"),
        roomHostActivity: document.querySelector("#room-host-activity"),
        roomGuestCard: document.querySelector("#room-member-guest"),
        roomGuestName: document.querySelector("#room-guest-name"),
        roomGuestTag: document.querySelector("#room-guest-tag"),
        roomGuestAvatar: document.querySelector("#room-guest-avatar"),
        roomGuestMicIndicator: document.querySelector("#room-guest-mic-indicator"),
        roomGuestActivity: document.querySelector("#room-guest-activity"),
        roomApprovalPanel: document.querySelector("#room-approval-panel"),
        roomApprovalText: document.querySelector("#room-approval-text"),
        roomApproveBtn: document.querySelector("#room-approve-btn"),
        roomRejectBtn: document.querySelector("#room-reject-btn"),
        roomMicBtn: document.querySelector("#room-mic-btn"),
        roomMicLabel: document.querySelector("#room-mic-label"),
        roomJoinCallBtn: document.querySelector("#room-join-call-btn"),
        roomReconnectBtn: document.querySelector("#room-reconnect-btn"),
        roomAutoplayNotice: document.querySelector("#room-autoplay-notice"),
        roomEnableAudioBtn: document.querySelector("#room-enable-audio-btn"),
        roomErrorBox: document.querySelector("#room-error-box"),
        roomErrorText: document.querySelector("#room-error-text"),
        roomErrorHint: document.querySelector("#room-error-hint"),
        roomRemoteAudio: document.querySelector("#room-remote-audio"),
        roomPendingHostName: document.querySelector("#room-pending-host-name"),
        roomPendingStatus: document.querySelector("#room-pending-status"),
        roomPendingCancelBtn: document.querySelector("#room-pending-cancel-btn")
    };

    document.addEventListener("DOMContentLoaded", init);

    // Xử lý Back/Forward của trình duyệt
    window.addEventListener("popstate", async event => {
        if (!state.currentUser) return; // đang ở auth screen
        const { pathname, search } = window.location;
        const parsed = router.parse(pathname, search);
        await resolveUrlAndNavigate(parsed, false); // false = không push lại history
    });

    async function init() {
        bindEvents();
        try {
            await refreshCsrf();
            const publicRoute = router.parse(location.pathname, location.search).route;
            if (["reset-password", "verify-email"].includes(publicRoute)) {
                showAuthScreen();
                window.DictaLabAccount?.showPublic(publicRoute);
                return;
            }
            state.currentUser = await api("/api/auth/me", {redirectOnUnauthorized: false});
            const next = new URLSearchParams(location.search).get("next");
            if (next?.startsWith("/")) {
                const target = new URL(next, location.origin);
                if (target.origin === location.origin && target.pathname.startsWith("/admin")) {
                    location.replace(target.pathname + target.search);
                    return;
                }
            }
            await enterApplication();
        } catch (error) {
            if (isAdminArea) {
                if (error.status === 401) showAuthScreen();
                else {
                    const loading = document.querySelector("#admin-loading");
                    loading.hidden = false;
                    loading.replaceChildren(document.createTextNode(error.message + " "));
                    const retry = document.createElement("a");
                    retry.href = location.href;
                    retry.textContent = "Thử lại";
                    loading.appendChild(retry);
                }
                return;
            }
            // Giữ nguyên URL để sau khi đăng nhập có thể redirect về đúng trang
            const { pathname, search } = window.location;
            const parsed = router.parse(pathname, search);
            const nextPath = (parsed.route !== "login" && parsed.route !== "register" && parsed.route !== "not-found")
                ? (pathname + search) : null;
            showAuthScreen(error.status === 401 ? "" : error.message, nextPath);
        }
    }

    function bindEvents() {
        elements.logoutButton.addEventListener("click", logout);
        bindMobileNavigation();
        elements.accountLink = document.querySelector("#account-link");
        elements.routeButtons.forEach(link => {
            link.addEventListener("click", event => {
                if (link.tagName === "A") {
                    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                    event.preventDefault();
                }
                elements.mobileNavMenu?.hidePopover?.();
                navigate(link.dataset.route);
            });
        });
        if (isAdminArea) {
            bindAdminEvents();
            return;
        }
        window.DictaLabAccount.enhancePasswords(document);
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
        document.querySelector("#forgot-password-link").addEventListener("click", () => {
            try { sessionStorage.setItem("dictalab-reset-email", elements.loginForm.elements.email.value.trim()); }
            catch (_) { /* The reset form remains usable when browser storage is unavailable. */ }
        });
        document.querySelector("#dashboard-load-error button").addEventListener("click", async () => {
            await refreshDashboard(true);
            if (document.body.dataset.route === "dashboard" && document.querySelector("#dashboard-load-error").hidden)
                document.querySelector("#main-content").focus();
        });
        document.querySelector("#history-load-error button").addEventListener("click", async () => {
            await loadHistory();
            if (document.body.dataset.route === "history" && document.querySelector("#history-load-error").hidden)
                elements.historyLevelTabs.querySelector('[aria-selected="true"]')?.focus();
        });
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
        elements.copyDictationSelection.addEventListener("click", () => copyDictationText(true));
        elements.copyDictationAll.addEventListener("click", () => copyDictationText(false));
        elements.hintLetter.addEventListener("click", () => useHint("FIRST_LETTER"));
        elements.hintWord.addEventListener("click", () => useHint("REVEAL_WORD"));
        elements.revealSentence.addEventListener("click", () => useHint("REVEAL_SENTENCE"));
        elements.classifications.addEventListener("click", onClassifyWord);
        elements.dictationForm.addEventListener("submit", completeSentence);
        elements.reviewSentences.addEventListener("click", playReviewAudio);
        elements.copyAnalysisReport.addEventListener("click", copyAnalysisReport);
        elements.historyLevelTabs.addEventListener("click", event => {
            const tab = event.target.closest("[data-history-level]");
            if (tab) selectHistoryLevel(tab.dataset.historyLevel);
        });
        elements.historyEmpty.querySelector("button").addEventListener("click", () => {
            elements.historyLevelTabs.querySelector('[data-history-level="all"]').focus();
            selectHistoryLevel("all");
        });
        elements.historyList.addEventListener("click", event => {
            const card = event.target.closest("[data-history-session-id]");
            if (!card || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            viewHistorySession(card.dataset.historySessionId);
        });
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
        // --- Room events ---
        elements.roomCreateBtn.addEventListener("click", roomCreateNew);
        elements.roomJoinForm.addEventListener("submit", event => {
            event.preventDefault();
            const input = elements.roomInviteInput.value.trim();
            if (!input) return;
            roomOpenByInviteCode(input);
        });
        elements.roomCopyInvite.addEventListener("click", roomCopyInviteLink);
        elements.roomReturnPractice.addEventListener("click", event => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            if (state.session) navigate("practice", {sessionId: state.session.sessionId});
        });
        elements.roomAdvanceSentence.addEventListener("click", roomAdvanceLesson);
        elements.sharedMicButton.addEventListener("click", () => state.roomLocalStream ? roomToggleMic() : roomJoinCall());
        elements.sharedEnableAudio.addEventListener("click", roomEnableAudio);
        elements.roomEnableStudyAudio.addEventListener("click", roomEnableStudyAudio);
        elements.sharedReconnect.addEventListener("click", roomReconnect);
        elements.roomLeaveBtn.addEventListener("click", roomLeave);
        elements.roomApproveBtn.addEventListener("click", roomApproveGuest);
        elements.roomRejectBtn.addEventListener("click", roomRejectGuest);
        elements.roomMicBtn.addEventListener("click", roomToggleMic);
        elements.roomJoinCallBtn.addEventListener("click", roomJoinCall);
        elements.roomReconnectBtn.addEventListener("click", roomReconnect);
        elements.roomEnableAudioBtn.addEventListener("click", roomEnableAudio);
        elements.roomPendingCancelBtn.addEventListener("click", roomLeave);
    }

    function bindAdminEvents() {
        elements.adminNewLesson.addEventListener("click", event => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            showNewLessonEditor();
        });
        elements.adminCancelCreate.addEventListener("click", event => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            hideAdminEditor();
        });
        const filters = document.querySelector("#admin-lesson-filters");
        filters.addEventListener("submit", event => {
            event.preventDefault();
            window.DictaLabAdmin.url({...Object.fromEntries(new FormData(filters)), page: 1});
            refreshAdminLessons();
        });
        filters.querySelector("[data-clear]").addEventListener("click", () => {
            window.DictaLabAdmin.url({page: 1}); refreshAdminLessons();
        });
        elements.adminLessonList.addEventListener("click", event => {
            const link = event.target.closest("[data-admin-lesson-id]");
            if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            selectAdminLesson(Number(link.dataset.adminLessonId));
        });
        elements.adminLessonForm.addEventListener("submit", event => { event.preventDefault(); window.DictaLabAdmin.busy(elements.adminEditorContent,()=>saveAdminLesson(event)); });
        elements.adminLessonForm.elements.level.addEventListener("change", event => {
            if (!state.adminEditingNew) return;
            const difficulty = ({A1: 1, A2: 2, B1: 3, B2: 4})[event.target.value] || 1;
            elements.adminLessonForm.elements.vocabularyDifficulty.value = String(difficulty);
            elements.adminLessonForm.elements.grammarDifficulty.value = String(difficulty);
            elements.adminLessonForm.elements.sentenceDifficulty.value = String(difficulty);
        });
        elements.adminAddSentenceForm.addEventListener("submit", event => { event.preventDefault(); window.DictaLabAdmin.busy(elements.adminEditorContent,()=>addAdminSentence(event)); });
        elements.adminSentenceList.addEventListener("click", event=>window.DictaLabAdmin.busy(elements.adminEditorContent,()=>handleAdminSentenceAction(event)));
        elements.adminSentenceList.addEventListener("change", event=>window.DictaLabAdmin.busy(elements.adminEditorContent,()=>handleAdminAudioUpload(event)));
        elements.adminPublicationButton.addEventListener("click", ()=>window.DictaLabAdmin.busy(elements.adminEditorContent,toggleAdminPublication));
        document.querySelector("#admin-copy-lesson").addEventListener("click",()=>window.DictaLabAdmin.busy(elements.adminEditorContent,async()=>{
            const lesson=selectedAdminLesson(); if(!lesson) return;
            if(!window.DictaLabAdmin.canLeave()) return;
            try {
                const copied=await api(`/api/admin/lessons/${lesson.id}/copy`,{method:"POST"});
                history.pushState({},"",`/admin/lessons/${copied.id}`); window.DictaLabAdmin.rememberUrl();
                await refreshAdminLessons(copied.id); setAdminStatus("Đã tạo bản nháp riêng. Bài gốc và lịch sử học vẫn được giữ.");
            } catch(error) {setAdminStatus(error.message,true);}
        }));
    }

    async function refreshCsrf() {
        const csrf = await api("/api/auth/csrf", {redirectOnUnauthorized: false});
        state.csrfHeader = csrf.headerName;
        state.csrfToken = csrf.token;
    }

    async function enterApplication() {
        if (isAdminArea) {
            if (state.currentUser.role !== "ADMIN") {
                location.replace("/admin");
                return;
            }
            document.querySelector("#admin-loading").hidden = true;
            elements.appShell.hidden = false;
            elements.accountName.textContent = state.currentUser.displayName;
            elements.accountEmail.textContent = state.currentUser.email;
            await resolveUrlAndNavigate(router.parse(location.pathname, location.search), false);
            return;
        }
        // Loading a different document gives each area its own layout and startup flow.
        const destination = router.parse(location.pathname, location.search);
        if (destination.route === "login" && destination.query.get("next")) {
            const next = new URL(destination.query.get("next"), location.origin);
            if (next.origin === location.origin && router.parse(next.pathname, next.search).route.startsWith("admin")) {
                location.replace(next.pathname + next.search);
                return;
            }
        }
        if (destination.route.startsWith("admin")) {
            location.replace(router.toPath(destination.route, destination.params));
            return;
        }
        elements.authScreen.hidden = true;
        elements.appShell.hidden = false;
        elements.accountName.textContent = state.currentUser.displayName;
        elements.accountEmail.textContent = state.currentUser.email;
        elements.adminNavLink.hidden = state.currentUser.role !== "ADMIN";
        renderLessonSkeletons();
        state.levels = await api("/api/v1/catalog/levels");
        renderLevelTabs();
        await refreshDashboard(false);

        // Tương thích backward: /?invite=xxx → /rooms/join/xxx
        const legacyInvite = new URLSearchParams(window.location.search).get("invite");
        if (legacyInvite) {
            router.replace("room-join", { inviteCode: legacyInvite });
        }

        // Đọc URL hiện tại và điều hướng đúng màn hình
        const parsed = router.parse(window.location.pathname, window.location.search);
        await resolveUrlAndNavigate(parsed, false);
    }

    /**
     * Đọc parsed URL và điều hướng tới màn hình phù hợp.
     * @param {object} parsed  — kết quả từ router.parse()
     * @param {boolean} pushHistory — true khi gọi từ user action (navigate), false khi khởi động / popstate
     */
    async function resolveUrlAndNavigate({ route, params, query }, pushHistory = true) {
        if (isAdminArea && pushHistory && window.DictaLabAdmin?.canLeave() === false) return;
        if (route.startsWith("admin") !== isAdminArea) {
            location.assign(router.toPath(route, params));
            return;
        }
        switch (route) {
            case "admin-listening": {
                if (pushHistory) router.push(route, params);
                _showView(route);
                await window.DictaLabListening?.show();
                return;
            }
            case "account":
            case "support":
            case "admin-users":
            case "admin-support": {
                if (route.startsWith("admin-") && state.currentUser?.role !== "ADMIN") {
                    showToast("Tài khoản này không có quyền quản trị.", true);
                    await resolveUrlAndNavigate({route: "catalog", params: {}, query}, false);
                    return;
                }
                if (pushHistory) router.push(route);
                else if (!route.startsWith("admin-") && !(route === "support" && (query?.has("id") || query?.has("category")))) router.replace(route);
                _showView(route);
                await window.DictaLabAccount?.show(route);
                return;
            }
            case "listening": {
                if (pushHistory) router.push("listening", params);
                else router.replace("listening", params);
                await _showView("listening");
                await window.DictaLabListening?.show(params.sessionId);
                return;
            }
            case "conversation": {
                if (pushHistory) router.push("conversation", params);
                else router.replace("conversation", params);
                await _showView("conversation");
                await window.DictaLabConversation?.show(params.sessionId);
                return;
            }
            case "login":
            case "register": {
                // Nếu đã đăng nhập thì redirect về catalog
                router.replace("catalog", { level: state.selectedLevel });
                await _showView("catalog");
                await selectLevel(state.selectedLevel);
                return;
            }

            case "catalog": {
                const level = router.validLevels.has(params.level) && params.level !== "all"
                    ? params.level : "A1";
                state.selectedLevel = level;
                if (pushHistory) router.push("catalog", { level, page: params.page });
                else router.replace("catalog", { level, page: params.page });
                await _showView("catalog");
                await selectLevel(level, params.page);
                return;
            }

            case "practice": {
                const { sessionId } = params;
                if (!sessionId) { await resolveUrlAndNavigate({ route: "catalog", params: { level: state.selectedLevel }, query }, pushHistory); return; }
                try {
                    const session = await api(`/api/v1/practice-sessions/${encodeURIComponent(sessionId)}`);
                    // Phục hồi session mà không tạo mới và không tự phát audio
                    state.session = session;
                    state.sentence = session.sentence;
                    if (!state.room && !state.roomSharedSessionId) {
                        const mine = await api("/api/v1/rooms/mine");
                        if (mine?.lessonId === session.lessonId) {
                            state.room = {roomId: mine.roomId, inviteCode: mine.inviteCode};
                            state.roomIsHost = mine.host.userId === state.currentUser.id;
                            state.roomStatus = mine.status;
                            roomConnectWebSocket(mine.roomId);
                            roomStartPolling(mine.roomId);
                        }
                    }
                    if (state.room && !state.roomSharedSessionId) {
                        const roomState = await api(`/api/v1/rooms/${encodeURIComponent(state.room.roomId)}`);
                        if (roomState.lessonId === session.lessonId) {
                            const shared = await api(`/api/v1/rooms/${encodeURIComponent(state.room.roomId)}/practice`, {method: "POST"});
                            if (shared.sessionId === session.sessionId) {
                                state.roomSharedSessionId = session.sessionId;
                                state.roomStudy = roomState;
                            }
                        }
                    }
                    state.activeWordIndex = 0;
                    state.wordAttemptStartedAt.clear();
                    state.inputInteractionRevision = 0;
                    state.practiceMutationQueue = Promise.resolve();
                    state.completeAfterPendingCheck = false;
                    state.completingSentence = false;
                    state.sentenceStartedAt = null;
                    state.timerSeconds = 0;
                    renderPractice(true);
                    if (pushHistory) router.push("practice", { sessionId });
                    else router.replace("practice", { sessionId });
                    await _showView("practice");
                    elements.replayButton.focus({ preventScroll: true });
                } catch (error) {
                    // Phiên không tồn tại hoặc không thuộc user này → về catalog
                    showToast(error.message || "Không tìm thấy phiên luyện tập.", true);
                    await resolveUrlAndNavigate({ route: "catalog", params: { level: state.selectedLevel }, query }, false);
                }
                return;
            }

            case "result": {
                const { sessionId } = params;
                if (!sessionId) { await resolveUrlAndNavigate({ route: "history", params: { level: "all" }, query }, pushHistory); return; }
                try {
                    const result = await api(`/api/v1/history/${encodeURIComponent(sessionId)}`);
                    renderResult(result, true);
                    if (pushHistory) router.push("result", { sessionId });
                    else router.replace("result", { sessionId });
                    await _showView("result");
                } catch (error) {
                    showToast(error.message || "Không tìm thấy kết quả.", true);
                    await resolveUrlAndNavigate({ route: "history", params: { level: "all" }, query }, false);
                }
                return;
            }

            case "history": {
                const level = router.validLevels.has(params.level) ? params.level : "all";
                state.historyFilter = level;
                if (pushHistory) router.push("history", { level, page: params.page });
                else router.replace("history", { level, page: params.page });
                await _showView("history");
                await loadHistory();
                return;
            }

            case "dashboard": {
                if (pushHistory) router.push("dashboard", {});
                else router.replace("dashboard", {});
                await _showView("dashboard");
                await refreshDashboard(true);
                return;
            }

            case "room": {
                if (pushHistory) router.push("room", {});
                else router.replace("room", {});
                await _showView("room");
                if (state.room) {
                    if (elements.roomPendingPanel.hidden) await roomEnterCallPanel(state.room.roomId);
                } else await roomRestoreMine();
                return;
            }

            case "room-join": {
                const { inviteCode } = params;
                if (!inviteCode) { await resolveUrlAndNavigate({ route: "room", params: {}, query }, false); return; }
                if (pushHistory) router.push("room-join", { inviteCode });
                else router.replace("room-join", { inviteCode });
                await _showView("room");
                roomInitLobby();
                roomOpenByInviteCode(inviteCode);
                return;
            }

            case "room-id": {
                const { roomId } = params;
                // Phục hồi trạng thái phòng từ server
                if (roomId && !state.room) {
                    try {
                        const roomState = await api(`/api/v1/rooms/${encodeURIComponent(roomId)}`);
                        if (roomState.status === "CLOSED") throw new Error("Phòng đã đóng.");
                        // Xác định vai trò
                        const myId = state.currentUser?.id;
                        state.room = {roomId, inviteCode: roomState.inviteCode};
                        state.roomIsHost = roomState.host?.userId == myId;
                        if (pushHistory) router.push("room-id", { roomId });
                        else router.replace("room-id", { roomId });
                        await _showView("room");
                        if (roomState.status === "PENDING_APPROVAL" && roomState.pending?.userId === myId) {
                            roomShowPending(roomState);
                        } else {
                            await roomEnterCallPanel(roomId);
                            if (roomState.status === "ACTIVE" || state.roomIsHost) roomConnectWebSocket(roomId);
                        }
                        roomStartPolling(roomId);
                    } catch (error) {
                        showToast(error.message || "Phòng không còn tồn tại.", true);
                        await resolveUrlAndNavigate({ route: "room", params: {}, query }, false);
                    }
                } else {
                    const activeRoomId = state.room.roomId;
                    if (pushHistory) router.push("room-id", {roomId: activeRoomId});
                    else router.replace("room-id", {roomId: activeRoomId});
                    await _showView("room");
                    if (elements.roomPendingPanel.hidden) await roomEnterCallPanel(activeRoomId);
                }
                return;
            }

            case "admin": {
                if (state.currentUser?.role !== "ADMIN") {
                    showToast("Tài khoản này không có quyền quản trị.", true);
                    await resolveUrlAndNavigate({ route: "catalog", params: { level: state.selectedLevel }, query }, false);
                    return;
                }
                if (pushHistory) router.push("admin", params);
                await _showView("admin");
                await refreshAdminLessons();
                return;
            }

            case "admin-new": {
                if (state.currentUser?.role !== "ADMIN") {
                    showToast("Tài khoản này không có quyền quản trị.", true);
                    await resolveUrlAndNavigate({ route: "catalog", params: { level: state.selectedLevel }, query }, false);
                    return;
                }
                if (pushHistory) router.push("admin-new", {});
                else router.replace("admin-new", {});
                await _showView("admin");
                await refreshAdminLessons();
                showNewLessonEditor();
                return;
            }

            case "admin-lesson": {
                if (state.currentUser?.role !== "ADMIN") {
                    showToast("Tài khoản này không có quyền quản trị.", true);
                    await resolveUrlAndNavigate({ route: "catalog", params: { level: state.selectedLevel }, query }, false);
                    return;
                }
                const { lessonId } = params;
                if (pushHistory) router.push("admin-lesson", { lessonId, page: params.page });
                await _showView("admin");
                await refreshAdminLessons(lessonId);
                return;
            }

            case "not-found":
            default: {
                // URL không hợp lệ → về catalog
                router.replace("catalog", { level: state.selectedLevel });
                await _showView("catalog");
                await selectLevel(state.selectedLevel);
                return;
            }
        }
    }

    /**
     * Hàm nội bộ: chỉ chuyển DOM view (không push history, không load data).
     * Tách riêng để resolveUrlAndNavigate kiểm soát hoàn toàn.
     */
    function bindMobileNavigation() {
        elements.mobileNavMore = document.querySelector("#mobile-nav-more");
        elements.mobileNavMenu = document.querySelector("#mobile-nav-menu");
        if (!elements.mobileNavMore || !elements.mobileNavMenu?.hidePopover) return;
        elements.mobileNavMenu.addEventListener("toggle", event => {
            elements.mobileNavMore.setAttribute("aria-expanded", String(event.newState === "open"));
        });
        window.matchMedia("(min-width: 761px)").addEventListener("change", event => {
            if (event.matches) elements.mobileNavMenu.hidePopover();
        });
    }

    function _showView(route) {
        // Map route key → view id
        const viewId = {
            catalog: "catalog", practice: "practice", result: "result",
            history: "history", dashboard: "dashboard", listening: "listening", conversation: "conversation",
            room: "room", admin: "admin", account: "account", support: "support",
            "admin-users": "admin-users", "admin-support": "admin-support", "admin-listening": "admin-listening", login: null, register: null
        }[route] || "catalog";

        const target = document.querySelector(`#${viewId}-view`);
        if (!target) return;
        if (state.roomStudy && viewId !== "practice") {
            roomSendWs({type: "audio-ready", position: state.sentence?.position, ready: false});
            state.roomAudioPreparedKey = null;
            stopRecordedAudio();
            window.speechSynthesis?.cancel();
        }
        if (document.body.dataset.route === "result" && viewId !== "result") window.DictaLabCoach?.leave();
        if (document.body.dataset.route === "conversation" && viewId !== "conversation") window.DictaLabConversation?.leave();
        if (isAdminArea) {
            stopRecordedAudio();
            const title = {admin: "Bài nghe", "admin-users": "Người dùng", "admin-support": "Yêu cầu hỗ trợ", "admin-listening": "Nghe hiểu"}[viewId];
            document.title = `${title} · Quản trị DictaLab`;
        }
        if (["listening", "admin-listening"].includes(document.body.dataset.route) && viewId !== document.body.dataset.route) window.DictaLabListening?.leave();
        elements.views.forEach(view => view.hidden = view !== target);
        document.body.dataset.route = viewId;
        if (viewId === "practice") roomPrepareStudyAudio();
        elements.mobileNavMenu?.hidePopover?.();
        elements.mobileNavMore?.classList.toggle("is-active", ["history", "room", "support"].includes(viewId));
        elements.mobileNavMore?.setAttribute("aria-label", {
            history: "Thêm, đang xem Lịch sử", room: "Thêm, đang xem Luyện cùng bạn", support: "Thêm, đang xem Hỗ trợ"
        }[viewId] || "Thêm");
        elements.navLinks.forEach(link => {
            const active = link.dataset.route === viewId;
            link.classList.toggle("is-active", active);
            if (active) {
                link.setAttribute("aria-current", "page");
            } else {
                link.removeAttribute("aria-current");
            }
        });
        window.scrollTo({ top: 0, behavior: "smooth" });
        document.querySelector("#main-content").focus({ preventScroll: true });
    }

    function showAuthScreen(message = "", nextPath = null) {
        if (isAdminArea) {
            location.replace(`/login?next=${encodeURIComponent(nextPath || location.pathname + location.search)}`);
            return;
        }
        elements.mobileNavMenu?.hidePopover?.();
        window.DictaLabCoach?.leave();
        window.DictaLabListening?.leave();
        window.DictaLabConversation?.leave();
        stopRecordedAudio();
        document.body.dataset.route = "login";
        stopTimer();
        state.currentUser = null;
        state.session = null;
        state.sentence = null;
        elements.appShell.hidden = true;
        elements.authScreen.hidden = false;
        // Xác định mode từ URL hiện tại
        const parsed = router.parse(window.location.pathname, window.location.search);
        const mode = parsed.route === "register" ? "register" : "login";
        nextPath ||= parsed.query.get("next");
        switchAuthMode(mode);
        elements.authStatus.textContent = message;
        // Lưu next path vào URL để sau đăng nhập redirect về đúng nơi
        if (["reset-password", "verify-email"].includes(parsed.route)) {
            // Preserve the one-time link while the public form is displayed.
        } else if (nextPath && nextPath !== "/" && nextPath !== "/login" && nextPath !== "/register") {
            const loginUrl = `/login?next=${encodeURIComponent(nextPath)}`;
            window.history.replaceState({}, "", loginUrl);
        } else if (mode === "register") {
            window.history.replaceState({}, "", "/register");
        } else {
            window.history.replaceState({}, "", "/login");
        }
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
            // Đọc ?next= để redirect về đúng trang sau đăng nhập
            const nextParam = new URLSearchParams(window.location.search).get("next");
            const nextUrl = nextParam?.startsWith("/") ? new URL(nextParam, location.origin) : null;
            const safeNext = nextUrl?.origin === location.origin ? nextUrl.pathname + nextUrl.search : null;
            if (safeNext) {
                // Thay URL trước khi enterApplication đọc lại
                window.history.replaceState({}, "", safeNext);
            }
            await enterApplication();
        } catch (error) {
            elements.authStatus.textContent = error.message;
        } finally {
            submitButton.disabled = false;
        }
    }

    async function logout() {
        if (isAdminArea && !window.DictaLabAdmin.canLeave()) return;
        elements.logoutButton.disabled = true;
        try {
            if (state.room && !await roomLeave()) return;
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

    /**
     * navigate() — dùng cho user action (click nút). Push history và chuyển màn hình.
     * @param {string} route   — key nội bộ hoặc alias
     * @param {object} params  — params cho router.toPath
     */
    async function navigate(route, params = {}) {
        // Map alias → parsed object
        const routeMap = {
            catalog:   { route: "catalog",   params: { level: params.level || state.selectedLevel } },
            listening: { route: "listening", params: { sessionId: params.sessionId || null, level: params.level || "", page: params.page || 1, view: params.view || "", edit: params.edit || "" } },
            conversation: { route: "conversation", params: { sessionId: params.sessionId || null } },
            history:   { route: "history",   params: { level: params.level || state.historyFilter || "all" } },
            dashboard: { route: "dashboard", params: {} },
            account:   { route: "account", params: {} },
            support:   { route: "support", params: {} },
            "admin-users": { route: "admin-users", params: {} },
            "admin-listening": { route: "admin-listening", params: {edit: params.edit || "", page: params.page || 1} },
            "admin-support": { route: "admin-support", params: {} },
            room:      { route: "room",       params: {} },
            admin:     { route: "admin",      params: {} },
            result:    { route: "result",     params: { sessionId: params.sessionId || state.session?.sessionId } },
            practice:  { route: "practice",   params: { sessionId: params.sessionId || state.session?.sessionId } },
        };
        const mapped = routeMap[route];
        if (!mapped) {
            // Cố gắng hiển thị view trực tiếp (backward compat)
            const target = document.querySelector(`#${route}-view`);
            if (target) { _showView(route); }
            return;
        }
        await resolveUrlAndNavigate(mapped, true);
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

    let catalogRequest = 0;
    async function selectLevel(level, page = 1) {
        const request = ++catalogRequest;
        state.selectedLevel = level;
        document.querySelectorAll('a[data-route="catalog"]').forEach(link => {
            link.href = router.toPath("catalog", {level, page: 1});
        });
        [...elements.levelTabs.querySelectorAll("[role='tab']")].forEach(tab => {
            const selected = tab.dataset.level === level;
            tab.setAttribute("aria-selected", String(selected));
            tab.tabIndex = selected ? 0 : -1;
        });
        const levelInfo = state.levels.find(item => item.code === level);
        elements.levelDescription.textContent = levelInfo?.description || "";
        renderLessonSkeletons();
        // Cập nhật URL query param (dùng replaceState để không tạo entry mới)
        router.replace("catalog", { level, page });
        try {
            const lessons = await api(`/api/v1/catalog/lessons?level=${encodeURIComponent(level)}`);
            if (request !== catalogRequest || document.body.dataset.route !== "catalog") return;
            state.lessons = lessons;
            renderLessons();
            paginateList(elements.lessonGrid);
        } catch (error) {
            if (request !== catalogRequest || document.body.dataset.route !== "catalog") return;
            elements.lessonGrid.setAttribute("aria-busy", "false");
            elements.lessonGrid.innerHTML = `<p>Không tải được bài luyện: ${escapeHtml(error.message)}</p>`;
        }
    }

    function renderLessonSkeletons() {
        if (elements.lessonGrid.nextElementSibling?.matches(".pagination")) elements.lessonGrid.nextElementSibling.remove();
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
            const actionLabel = state.room ? "Chọn cho cả hai" : lesson.inProgress ? "Tiếp tục" : lesson.completed ? "Làm lại" : "Bắt đầu";
            const progressLabel = lesson.inProgress
                ? (lesson.completed ? "Đang luyện lại" : "Đang học")
                : lesson.completed ? "Đã hoàn thành" : "Chưa bắt đầu";
            const stateClass = lesson.inProgress ? "is-in-progress" : lesson.completed ? "is-completed" : "is-new";
            const previewGroups = [
                ["Từ khó", lesson.previewWords?.filter(item => !item.properName) || []],
                ["Tên riêng", lesson.previewWords?.filter(item => item.properName) || []]
            ].filter(([, items]) => items.length);
            const preview = previewGroups.length ? `
                <div class="lesson-preview" role="group" aria-label="Từ cần nghe trước">
                    <span class="lesson-preview-label">Nghe trước · không tính trọng số CEFR</span>
                    ${previewGroups.map(([label, items]) => `
                        <div class="lesson-preview-group">
                            <strong>${label}</strong>
                            <div>${items.map(item => `
                                <button type="button" data-preview-word="${escapeHtml(item.word)}"
                                        data-preview-lesson-id="${lesson.id}"
                                        data-preview-audio-url="${escapeHtml(lesson.previewAudioUrl || "")}"
                                        data-preview-start="${item.audioStartSeconds ?? ""}"
                                        data-preview-end="${item.audioEndSeconds ?? ""}"
                                        aria-label="Nghe từ ${escapeHtml(item.word)}">
                                    <i aria-hidden="true">▶</i>${escapeHtml(item.word)}
                                </button>`).join("")}</div>
                        </div>`).join("")}
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
                    <div class="lesson-meta" role="group" aria-label="Thông tin bài luyện">
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
                await loadAudioBuffer(audioUrl);
                await api("/api/v1/catalog/preview-exposure", {method: "POST", body: {
                    lessonId: Number(button.dataset.previewLessonId), audioUrl}});
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
            if (state.room) {
                if (!state.roomIsHost || state.roomStatus !== "ACTIVE") {
                    showToast("Chờ cả hai vào phòng; chỉ chủ phòng chọn bài học chung.", true);
                    return;
                }
                const roomId = encodeURIComponent(state.room.roomId);
                const room = await api(`/api/v1/rooms/${roomId}/lesson`, {
                    method: "POST", body: {lessonId}
                });
                await roomSyncStudy(room);
                return;
            }
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
            // Push URL /practice/{sessionId} vào history
            router.push("practice", { sessionId: state.session.sessionId });
            _showView("practice");
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
            elements.dictationCopyText.value = "";
            elements.dictationCopyStatus.textContent = "";
        }
        elements.practiceLevel.textContent = state.session.level;
        elements.practiceTitle.textContent = state.session.lessonTitle;
        elements.roomReturnPractice.href = router.toPath("practice", {sessionId: state.session.sessionId});
        if (state.room?.roomId) elements.sharedRoomButton.href = router.toPath("room-id", {roomId: state.room.roomId});
        const shared = Boolean(state.room && state.roomSharedSessionId === state.session.sessionId);
        elements.sharedPracticeBar.hidden = !shared;
        elements.peerPracticePanel.hidden = !shared;
        elements.sentencePosition.textContent = sentence.position;
        elements.sentenceTotal.textContent = sentence.totalSentences;
        elements.sentenceProgress.style.width = `${sentence.position / sentence.totalSentences * 100}%`;
        elements.replayCount.textContent = shared ? (state.roomPlayback?.replayCount || 0) : sentence.replayCount;
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
        if (shared) roomRenderStudy(state.roomStudy);
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
            const inputValue = word.prefilled ? word.userInput : draft === undefined
                ? (word.userInput || word.firstLetter || "")
                : draft;
            const classes = ["word-slot"];
            if (word.prefilled) classes.push("is-prefilled");
            else if (word.correct) classes.push("is-correct");
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
                       <span class="sr-only">Từ ${word.index + 1}, ${word.length} ký tự${word.prefilled ? ", tên riêng đã điền sẵn" : word.firstLetter ? `, bắt đầu bằng ${escapeHtml(word.firstLetter)}` : ""}</span>
                       <input type="text" inputmode="text" autocomplete="off" autocapitalize="none" spellcheck="false"
                              data-word-index="${word.index}" maxlength="${word.length}"
                              style="width: calc(${width}ch + 24px)" placeholder="${escapeHtml(word.mask)}"
                              value="${escapeAttribute(inputValue)}" ${word.correct ? "readonly aria-readonly=\"true\"" : ""}
                              ${word.prefilled ? "tabindex=\"-1\"" : ""}
                              aria-invalid="${word.userInput && !word.correct ? "true" : "false"}">
                   </label>`;
            const before = word.punctuationBefore
                ? `<span class="word-punctuation before">${escapeHtml(word.punctuationBefore)}</span>`
                : "";
            const after = word.punctuationAfter
                ? `<span class="word-punctuation after">${escapeHtml(word.punctuationAfter)}</span>`
                : "";
            return `<span class="dictation-token">${before}<span class="${classes.join(" ")}" `
                + `data-slot-index="${word.index}">${hint}${control}</span>${after}</span>`;
        }).join("");

        const answerable = words.filter(word => !word.prefilled);
        const resolved = answerable.filter(word => word.correct || word.revealed).length;
        elements.wordCompletion.textContent = `${resolved}/${answerable.length} từ`;
        refreshDictationCopy();
        roomQueueDraft();
    }

    function buildVisibleDictationSentence() {
        return (state.sentence?.words || []).map(word => {
            const input = elements.wordSlots.querySelector(`input[data-word-index="${word.index}"]`);
            const revealed = elements.wordSlots.querySelector(`[data-slot-index="${word.index}"] .revealed-word`);
            const value = (input?.value ?? revealed?.textContent ?? "").trim();
            return value ? `${word.punctuationBefore || ""}${value}${word.punctuationAfter || ""}` : "";
        }).filter(Boolean).join(" ");
    }

    function refreshDictationCopy() {
        const text = buildVisibleDictationSentence();
        // Leave selection untouched when a background word check doesn't change the text.
        if (elements.dictationCopyText.value !== text) {
            elements.dictationCopyText.value = text;
            elements.dictationCopyStatus.textContent = "";
        }
        elements.copyDictationAll.disabled = !text;
    }

    async function copyDictationText(selectionOnly) {
        const field = elements.dictationCopyText;
        const sourceText = field.value;
        const sessionId = state.session?.sessionId;
        const sentenceId = state.sentence?.sentenceId;
        const route = document.body.dataset.route;
        const start = selectionOnly ? field.selectionStart : 0;
        const end = selectionOnly ? field.selectionEnd : field.value.length;
        const text = field.value.slice(start, end);
        if (!text.trim()) {
            elements.dictationCopyStatus.textContent = selectionOnly
                ? "Hãy bôi chọn từ hoặc cụm từ trong vùng câu bên trên trước."
                : "Chưa có nội dung để sao chép.";
            return;
        }
        const button = selectionOnly ? elements.copyDictationSelection : elements.copyDictationAll;
        button.disabled = true;
        let copied = false;
        let usedFallback = false;
        try {
            if (navigator.clipboard?.writeText && window.isSecureContext) {
                await navigator.clipboard.writeText(text);
                copied = true;
            }
        } catch (_error) {
            // HTTP deployments and denied clipboard permission can still use native copy.
        }
        // Clipboard permission can finish after the learner edits or leaves this sentence.
        if (state.session?.sessionId !== sessionId || state.sentence?.sentenceId !== sentenceId
            || document.body.dataset.route !== route || field.value !== sourceText) {
            button.disabled = !field.value;
            return;
        }
        try {
            if (!copied) {
                usedFallback = true;
                copied = copyTextWithSelection(text);
            }
        } catch (_error) {
            copied = false;
        } finally {
            button.disabled = selectionOnly ? false : !field.value;
        }
        if ((usedFallback || !copied) && field.value.slice(start, end) === text) {
            field.focus({preventScroll: true});
            field.setSelectionRange(start, end);
        }
        elements.dictationCopyStatus.textContent = copied
            ? (selectionOnly ? "Đã sao chép phần đã chọn." : "Đã sao chép câu đang hiển thị.")
            : "Không thể sao chép tự động. Nhấn Ctrl+C (Mac: ⌘C) để sao chép phần đã chọn.";
    }

    function updatePracticeControls() {
        const sentence = state.sentence;
        if (!sentence) return;
        const currentWord = sentence.words[state.activeWordIndex];
        const canUseWordHint = sentence.hasListened && !state.speaking && currentWord
            && !currentWord.correct && !currentWord.revealed;
        elements.hintLetter.disabled = !canUseWordHint;
        elements.hintWord.disabled = !canUseWordHint;
        elements.revealSentence.disabled = !sentence.hasListened || state.speaking
            || sentence.words.every(word => word.correct || word.revealed);
        if (state.roomStudy && state.roomReady) {
            elements.hintLetter.disabled = true;
            elements.hintWord.disabled = true;
            elements.revealSentence.disabled = true;
        }
        const shared = state.roomSharedSessionId === state.session?.sessionId && Boolean(state.roomStudy);
        const playback = shared ? state.roomPlayback : null;
        const sharedBusy = Boolean(playback?.playing);
        if (shared) elements.roomAdvanceSentence.disabled = sharedBusy || !state.roomStudy.hostReady || !state.roomStudy.guestReady;
        elements.replayButton.disabled = state.speaking || (shared && (!state.roomIsHost || sharedBusy
            || !playback?.hostAudioReady || !playback?.guestAudioReady || state.roomWs?.readyState !== WebSocket.OPEN));
        elements.playbackSpeed.disabled = state.speaking || (shared && (!state.roomIsHost || sharedBusy));
        elements.replayButton.querySelector("strong").textContent = shared ? "Nghe chung" : "Nghe câu";
        elements.replayCountLabel.textContent = shared ? "Lượt nghe chung" : "Lượt nghe";
        elements.roomEnableStudyAudio.hidden = !shared || (state.roomAudioEnabled && state.audioContext?.state === "running"
            && (state.roomIsHost ? playback?.hostAudioReady : playback?.guestAudioReady));
        elements.roomStudyAudioStatus.hidden = !shared;
        if (shared) {
            elements.replayCount.textContent = playback?.replayCount || 0;
            elements.roomStudyAudioStatus.textContent = !sentence.audioUrl
                ? "Bài này chưa có file audio để nghe chung. Hãy rời phòng và chọn bài có audio."
                : !state.roomAudioEnabled || state.audioContext?.state !== "running"
                ? "Bấm Bật nghe chung một lần để cho phép phát âm thanh."
                : sharedBusy ? "Đang phát câu cho cả hai."
                    : !playback?.hostAudioReady || !playback?.guestAudioReady ? "Đang chờ cả hai bật nghe chung và tải audio."
                        : state.roomIsHost ? "Bấm Nghe chung để cả hai nghe; tốc độ do chủ phòng chọn."
                            : "Chủ phòng bấm Nghe chung để phát câu cho cả hai.";
        }
        elements.wordSlots.querySelectorAll("input").forEach(input => {
            const word = sentence.words[Number(input.dataset.wordIndex)];
            input.disabled = !sentence.hasListened || (Boolean(state.roomStudy) && state.roomReady);
            input.readOnly = Boolean(word?.correct);
        });
        elements.nextSentence.disabled = !sentence.canComplete || state.speaking || sharedBusy || (Boolean(state.roomStudy) && state.roomReady);
        elements.nextSentence.firstChild.textContent = state.roomStudy ? "Sẵn sàng " : "Câu tiếp theo ";

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
        // An empty draft is an intentional deletion, not a missing draft.
        state.wordDrafts.set(index, input.value);
        refreshDictationCopy();
        roomQueueDraft();
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
            if (incomingWords[position] && !candidate.readOnly) {
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

    async function playSentence(sharedPlayback = null) {
        if (!state.sentence || state.speaking) return;
        const shared = state.roomSharedSessionId === state.session?.sessionId && Boolean(state.roomStudy);
        // Click/keyboard handlers pass DOM events, not playback commands.
        sharedPlayback = sharedPlayback?.startsAt ? sharedPlayback : null;
        if (shared && !sharedPlayback) {
            if (!state.roomIsHost || !state.roomPlayback?.hostAudioReady || !state.roomPlayback?.guestAudioReady
                || state.roomPlayback.playing) return;
            roomSendWs({type: "lesson-play", position: state.sentence.position,
                sequence: state.roomPlayback.sequence, speed: state.playbackSpeed});
            return;
        }
        const playbackSpeed = sharedPlayback?.speed || state.playbackSpeed;
        const sessionId = state.session.sessionId;
        const sentenceId = state.sentence.sentenceId;
        const sameSentence = () => state.session?.sessionId === sessionId && state.sentence?.sentenceId === sentenceId;
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
            if (sharedPlayback && (!hasRecordedAudio || state.audioContext?.state !== "running")) {
                throw new Error("Chưa sẵn sàng nghe chung. Hãy bấm Bật nghe chung rồi phát lại.");
            }
            if (sharedPlayback && Date.now() - sharedPlayback.localStartsAt > 1500) {
                throw new Error("Mạng phản hồi chậm; nhờ chủ phòng phát lại để cả hai nghe cùng lượt.");
            }
            const started = await queuePracticeMutation(practicePath("/replay-events"), {
                method: "POST",
                body: {event: "STARTED", playbackSpeed}
            });
            if (!sameSentence()) throw new DOMException("Sentence changed", "AbortError");
            state.sentence = started;
            if (!state.sentenceStartedAt) {
                startTimer();
            }
            elements.replayCount.textContent = shared ? sharedPlayback.replayCount : state.sentence.replayCount;
            updatePracticeControls();
            setPracticeStatus("Đang phát câu. Bạn có thể nhập ngay trong khi nghe.");
            if (hasRecordedAudio) {
                if (sharedPlayback && Date.now() - sharedPlayback.localStartsAt > 1500) {
                    throw new Error("Mạng phản hồi chậm; nhờ chủ phòng phát lại để cả hai nghe cùng lượt.");
                }
                await playRecordedAudio(
                    state.sentence.audioUrl,
                    state.sentence.audioStartSeconds,
                    state.sentence.audioEndSeconds,
                    playbackSpeed,
                    sharedPlayback ? Math.max(0, sharedPlayback.localStartsAt - Date.now()) / 1000 : 0
                );
            } else {
                await speak(state.sentence.speechText, state.sentence.speechRate * playbackSpeed);
            }
            if (!sameSentence()) throw new DOMException("Sentence changed", "AbortError");
            const completed = await queuePracticeMutation(practicePath("/replay-events"), {
                method: "POST",
                body: {event: "COMPLETED", playbackSpeed}
            });
            if (!sameSentence()) throw new DOMException("Sentence changed", "AbortError");
            state.sentence = completed;
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
            if (sharedPlayback) {
                state.roomAudioPreparedKey = null;
                roomSendWs({type: "audio-ready", position: sharedPlayback.position, ready: false});
            }
        } finally {
            if (sharedPlayback) roomSendWs({type: "lesson-played", position: sharedPlayback.position, sequence: sharedPlayback.sequence});
            state.speaking = false;
            elements.listeningConsole.classList.remove("is-speaking");
            updatePracticeControls();
        }
    }

    async function playRecordedAudio(url, startSeconds, endSeconds, playbackSpeed = 1, startDelay = 0) {
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
                source.start(state.audioContext.currentTime + startDelay, segment.start, segment.duration);
            } catch (_error) {
                finish(() => reject(new Error("Không thể bắt đầu phát file audio của bài nghe.")));
            }
        });
    }

    function loadAudioBuffer(url) {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) return Promise.reject(new Error("Trình duyệt này không hỗ trợ phát audio."));
        state.audioContext ||= new AudioContextClass();
        const cached = state.audioBufferCache.get(url);
        if (cached) {
            state.audioBufferCache.delete(url);
            state.audioBufferCache.set(url, cached);
            return cached;
        }

        const pending = authenticatedFetch(url, {credentials: "same-origin"})
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

    async function completeSentence(event, fromRoom = false) {
        event?.preventDefault();
        if (!state.sentence?.canComplete || state.speaking || state.completingSentence) return;
        if (state.room && state.roomSharedSessionId === state.session?.sessionId && !fromRoom) {
            await roomMarkReady();
            return;
        }
        if (fromRoom && state.room && state.roomStudy
            && state.sentence?.position >= state.roomStudy.lessonPosition) return;
        state.completingSentence = true;
        state.completeAfterPendingCheck = false;
        elements.nextSentence.disabled = true;
        try {
            const response = await queuePracticeMutation(practicePath("/complete"), {
                method: "POST",
                body: {timeSpentSeconds: state.timerSeconds}
            });
            if (response.lessonCompleted) {
                if (state.roomSharedSessionId === state.session?.sessionId) state.roomSharedCompleted = true;
                stopTimer();
                renderResult(response.result);
                if (state.roomSharedCompleted) {
                    elements.resultAssessment.textContent = "Bài học chung: không dùng kết quả này để ước lượng năng lực nghe độc lập.";
                    elements.resultAssessment.setAttribute("aria-label", "Bài học chung không dùng để đánh giá năng lực độc lập");
                }
                await refreshDashboard(false);
                // Chuyển URL sang /history/{sessionId}
                const sessionId = response.result?.sessionId || state.session?.sessionId;
                router.push("result", { sessionId });
                _showView("result");
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

    function renderResult(result, isHistoryReview = false) {
        state.lastResult = result;
        state.historyViewingSession = isHistoryReview;
        state.reviewAudioDurations.clear();
        document.querySelector("#result-score").textContent = Math.round(result.overallScore);
        document.querySelector("#result-first").textContent = `${formatPercent(result.firstAttemptAccuracy)}%`;
        document.querySelector("#result-final").textContent = `${formatPercent(result.finalAccuracy)}%`;
        document.querySelector("#result-replays").textContent = result.replayCount;
        document.querySelector("#result-hints").textContent = `${result.hintCount} / ${result.revealCount}`;
        document.querySelector("#result-known").textContent = result.knownButMissed;
        document.querySelector("#result-unknown").textContent = result.unknownWords;
        renderAssessment(elements.resultAssessment, result.assessment);
        document.querySelector("#coach-accuracy").textContent = `${formatPercent(result.firstAttemptAccuracy)}%`;
        document.querySelector("#coach-lesson").textContent = `${result.lessonTitle} · ${result.level}`;
        document.querySelector("#result-message").textContent = result.firstAttemptAccuracy >= 85
            ? "Bạn nhận ra phần lớn từ ngay từ đầu. Ôn vài chỗ đã lỡ rồi thử một bài mới."
            : result.firstAttemptAccuracy >= 60
                ? "Bạn đã nắm được nhiều từ trong bài. Tập trung vào vài điểm còn vướng dưới đây."
                : "Bài này còn nhiều chỗ khó. Bắt đầu bằng một nhóm lỗi nhỏ, nghe lại rồi thử lại.";

        const resultActions = document.querySelector(".result-score-card");
        const existingBack = resultActions.querySelector(".history-back-link");
        if (existingBack) existingBack.remove();
        resultActions.querySelector(".result-report-link")?.remove();
        const reportIssue = document.createElement("a");
        reportIssue.className = "text-button result-report-link";
        reportIssue.href = `/support?type=DICTATION&id=${encodeURIComponent(result.sessionId)}`;
        reportIssue.textContent = "Báo audio hoặc kết quả chưa đúng";
        resultActions.appendChild(reportIssue);
        const dashboardButton = resultActions.querySelector("[data-route='dashboard']");
        const catalogButton = document.querySelector(".result-details .text-button[data-route='catalog']");
        if (isHistoryReview) {
            if (dashboardButton) dashboardButton.hidden = true;
            if (catalogButton) catalogButton.hidden = true;
            const backButton = document.createElement("a");
            backButton.className = "history-back-link";
            backButton.href = router.toPath("history", {level: state.historyFilter || "all", page: 1});
            backButton.innerHTML = `← Quay lại lịch sử`;
            backButton.addEventListener("click", event => {
                if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                event.preventDefault();
                navigate("history", { level: state.historyFilter || "all" });
            });
            resultActions.appendChild(backButton);
        } else {
            if (dashboardButton) dashboardButton.hidden = false;
            if (catalogButton) catalogButton.hidden = false;
        }

        const technical = document.querySelector("#result-technical");
        technical.open = false;
        elements.reviewSentences.innerHTML = "";
        let loaded = false;
        technical.ontoggle = () => {
            if (!technical.open || loaded || state.lastResult !== result) return;
            loaded = true;
            renderLessonReview(result);
            loadReviewAudioMetrics(result).catch(() => {
                if (state.lastResult === result) elements.reviewAudioMetrics.textContent = "Không đo được thời lượng audio";
            });
        };
        window.DictaLabCoach?.show(result);
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
            if (state.lastResult !== result) return;
            const batch = withoutSegments.slice(start, start + 5);
            const durations = await Promise.all(batch.map(sentence => readAudioDuration(sentence.audioUrl)));
            if (state.lastResult !== result) return;
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
            let authRetried = false;
            audio.onerror = async () => {
                if (authRetried || settled) {finish(0); return;}
                authRetried = true;
                try {await api("/api/auth/me"); if (!settled) audio.load();}
                catch (_) {finish(0);}
            };
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
        const errorPanel = document.querySelector("#dashboard-load-error");
        const view = document.querySelector("#dashboard-view");
        errorPanel.hidden = true;
        view.setAttribute("aria-busy", "true");
        try {
            const dashboard = await api("/api/v1/dashboard");
            renderDashboard(dashboard);
        } catch (error) {
            errorPanel.hidden = false;
            if (showErrors) showToast(error.message, true);
        } finally { view.setAttribute("aria-busy", "false"); }
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
        renderDashboardActivity(dashboard.trend);
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

    function renderDashboardActivity(trend) {
        const welcome = document.querySelector("#dashboard-welcome");
        const recent = document.querySelector("#dashboard-recent");
        const latest = trend.at(-1);
        if (!latest) {
            welcome.textContent = "Chưa có buổi chép chính tả cá nhân hoàn thành. Bắt đầu một bài để xem tiến bộ của bạn.";
            recent.innerHTML = '<p>Buổi học đầu tiên của bạn sẽ xuất hiện ở đây.</p><a class="text-button" href="/catalog?level=A1&page=1">Chọn bài luyện đầu tiên →</a>';
            return;
        }
        const previous = trend.slice(0, -1).reverse().find(point => point.level === latest.level);
        if (previous) {
            const change = Math.round((latest.firstAttemptAccuracy - previous.firstAttemptAccuracy) * 10) / 10;
            welcome.textContent = `Buổi ${latest.level} gần nhất: đúng lần đầu ${formatPercent(latest.firstAttemptAccuracy)}%. `
                + (change === 0 ? "Không đổi so với buổi trước cùng trình độ."
                    : `${change > 0 ? "Tăng" : "Giảm"} ${formatPercent(Math.abs(change))} điểm phần trăm so với buổi trước cùng trình độ.`)
                + " Hai bài có thể khác độ khó; đây là so sánh kết quả từng buổi.";
        } else {
            welcome.textContent = `Buổi ${latest.level} gần nhất: đúng lần đầu ${formatPercent(latest.firstAttemptAccuracy)}%. Chưa có buổi trước cùng trình độ trong 8 buổi gần đây để so sánh.`;
        }
        recent.innerHTML = `<ul class="dashboard-recent-list">${trend.slice(-3).reverse().map(point => `
            <li><a href="${router.toPath("result", {sessionId: point.sessionId})}">
                <strong>${escapeHtml(point.lessonTitle)}</strong>
                <span>${escapeHtml(point.level)} · <time datetime="${escapeAttribute(point.date)}">${escapeHtml(point.date.split("-").reverse().join("/"))}</time> · Lần đầu ${formatPercent(point.firstAttemptAccuracy)}%</span>
                <span>Xem lại →</span>
            </a></li>`).join("")}</ul>`;
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
        elements.trendChart.setAttribute("role", "img");
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
                </div>
                <div class="trend-bar final" style="height: ${point.finalAccuracy}%">
                    <span>${Math.round(point.finalAccuracy)}</span>
                </div>
                <small class="trend-label">${escapeHtml(point.level)}<br><time datetime="${escapeAttribute(point.date)}">${escapeHtml(point.date.split("-").slice(1).reverse().join("/"))}</time></small>
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
        const ui = window.DictaLabAdmin;
        const request = ++adminListRequest;
        const saved = ui.capture(elements.adminEditorContent);
        const savedId = state.selectedAdminLessonId;
        const filters = ui.query({q:"",level:"",status:"",topic:"",origin:"",sort:"updated",page:1});
        const form = document.querySelector("#admin-lesson-filters");
        elements.adminLessonList.setAttribute("aria-busy", "true");
        try {
            const [result, options] = await Promise.all([api(`/api/admin/lessons/page?${new URLSearchParams({...filters,page: Math.max(0,Number(filters.page)-1)})}`), api("/api/admin/lessons/filters")]);
            if (request !== adminListRequest || document.body.dataset.route !== "admin") return;
            state.adminLessons = result.items.map(item => ({...item,contentOrigin:item.content_origin,contentLocked:item.content_locked,sentenceCount:item.sentence_count}));
            state.adminPage = result;
            form.elements.topic.innerHTML = '<option value="">Tất cả</option>' + [...new Set(options.map(item=>item.topic))].map(topic=>`<option value="${escapeAttribute(topic)}">${escapeHtml(topic)}</option>`).join("");
            Object.entries(filters).forEach(([key,value])=>{ if(form.elements.namedItem(key)) form.elements.namedItem(key).value=value; });
            const corrected=new URL(location.href); corrected.searchParams.set("page",result.page+1); history.replaceState(history.state,"",corrected); ui.rememberUrl();
            if (preferredLessonId !== null) {
                state.selectedAdminLessonId = preferredLessonId;
                state.adminEditingNew = false;
            }
            renderAdminLessonList();
            if (!state.adminEditingNew && state.selectedAdminLessonId !== null && (preferredLessonId !== null || !selectedAdminLesson())) {
                try {
                    const lessonId = state.selectedAdminLessonId;
                    const lesson = await api(`/api/admin/lessons/${lessonId}`);
                    if (request !== adminListRequest || document.body.dataset.route !== "admin" || state.selectedAdminLessonId !== lessonId) return;
                    state.adminSelectedLesson = lesson;
                    renderAdminEditor(state.adminSelectedLesson);
                    if (savedId === lesson.id) ui.restore(elements.adminEditorContent,saved);
                } catch (error) {
                    setAdminStatus(error.message,true);
                }
            }
        } catch (error) {
            if (request === adminListRequest) ui.failure(elements.adminLessonList,error.message,()=>refreshAdminLessons());
        } finally {
            if (request === adminListRequest) elements.adminLessonList.setAttribute("aria-busy", "false");
        }
    }
    let adminListRequest = 0;

    function renderAdminLessonList() {
        const lessons = state.adminLessons;
        elements.adminLessonList.innerHTML = lessons.length ? lessons.map(lesson => `
            <a class="admin-lesson-item${lesson.id === state.selectedAdminLessonId ? " is-selected" : ""}"
                    href="/admin/lessons/${lesson.id}${location.search}"
                    data-admin-lesson-id="${lesson.id}">
                <span class="admin-lesson-meta">
                    <span>${escapeHtml(lesson.level)} · ${lesson.sentenceCount} câu</span>
                    <span class="${lesson.published ? "is-published" : ""}">${lesson.published ? "Đã xuất bản" : "Bản nháp"}</span>
                </span>
                <strong>${escapeHtml(lesson.title)}</strong>
                <small>${escapeHtml(lesson.slug)}</small>
            </a>
        `).join("") : '<p class="admin-help">Không có bài nào khớp tìm kiếm.</p>';
        elements.adminLessonList.innerHTML += window.DictaLabAdmin.pager(state.adminPage);
        elements.adminLessonList.querySelectorAll("[data-page]").forEach(button=>button.addEventListener("click",()=>{
            window.DictaLabAdmin.url({...Object.fromEntries(new URLSearchParams(location.search)),page:button.dataset.page}); refreshAdminLessons();
        }));
    }

    function selectedAdminLesson() {
        return state.adminSelectedLesson?.id === state.selectedAdminLessonId ? state.adminSelectedLesson : null;
    }

    async function selectAdminLesson(lessonId) {
        if (!window.DictaLabAdmin.canLeave()) return;
        const lesson = state.adminLessons.find(item => item.id === lessonId);
        if (!lesson) return;
        state.selectedAdminLessonId = lessonId;
        state.adminEditingNew = false;
        renderAdminLessonList();
        // Cập nhật URL
        history.pushState({},"",`/admin/lessons/${lessonId}${location.search}`); window.DictaLabAdmin.rememberUrl();
        try {
            const selected = await api(`/api/admin/lessons/${lessonId}`);
            if (state.selectedAdminLessonId !== lessonId || document.body.dataset.route !== "admin") return;
            state.adminSelectedLesson = selected;
            renderAdminEditor(state.adminSelectedLesson);
            elements.adminEditorTitle.focus({preventScroll: true});
        } catch (error) {
            setAdminStatus(error.message, true);
        }
    }

    function showNewLessonEditor() {
        if (!window.DictaLabAdmin.canLeave()) return;
        state.selectedAdminLessonId = null;
        state.adminSelectedLesson = null;
        state.adminEditingNew = true;
        elements.adminCancelCreate.href = router.toPath("admin", {page: new URLSearchParams(location.search).get("page") || 1});
        renderAdminLessonList();
        elements.adminEmptyState.hidden = true;
        elements.adminEditorContent.hidden = false;
        elements.adminEditorKicker.textContent = "Bài mới · lưu dưới dạng bản nháp";
        elements.adminEditorTitle.textContent = "Thông tin bài nghe";
        elements.adminPublicationBadge.textContent = "Chưa lưu";
        elements.adminPublicationBadge.className = "publication-badge";
        document.querySelector("#admin-copy-lesson").hidden=true;
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
        // Cập nhật URL
        router.push("admin-new", {});
        window.DictaLabAdmin.track(elements.adminEditorContent);
        window.requestAnimationFrame(() => elements.adminLessonForm.elements.slug.focus());
    }

    function hideAdminEditor() {
        state.adminEditingNew = false;
        state.selectedAdminLessonId = null;
        state.adminSelectedLesson = null;
        elements.adminEditorContent.hidden = true;
        elements.adminEmptyState.hidden = false;
        // Cập nhật URL về danh sách admin
        router.replace("admin", {});
        window.DictaLabAdmin.rememberUrl();
        renderAdminLessonList();
    }

    function nextAdminDisplayOrder() {
        return Math.max(0, ...state.adminLessons.map(lesson => Number(lesson.displayOrder) || 0)) + 10;
    }

    function renderAdminEditor(lesson) {
        document.querySelector("#admin-copy-lesson").hidden=lesson.contentOrigin==="BRITISH_COUNCIL_PERSONAL";
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
            elements.adminLockNotice.textContent = "Bài đã có lịch sử học hoặc được dùng làm nguồn nghe hiểu. Transcript, audio và thứ tự câu được khóa để bảo toàn nội dung. Bạn có thể sửa tên/mô tả, gỡ xuất bản hoặc tạo bản sửa mới.";
        } else if (temporarilyLocked) {
            elements.adminLockNotice.textContent = "Bài đang được xuất bản. Hãy gỡ xuất bản trước khi sửa câu hoặc thay audio.";
        }
        renderAdminSentences(lesson);
        renderAdminPublication(lesson);
        window.DictaLabAdmin.track(elements.adminEditorContent);
        api(`/api/admin/lessons/${lesson.id}/events`).then(events=>{
            if(state.selectedAdminLessonId===lesson.id) document.querySelector("#admin-content-events").innerHTML=window.DictaLabAdmin.events(events);
        }).catch(error=>{ document.querySelector("#admin-content-events").textContent=error.message; });
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
                <article class="admin-sentence-card" data-sentence-id="${sentence.id}" data-admin-dirty="sentence-${sentence.id}">
                    <span class="admin-sentence-number"><span class="sr-only">Câu </span>${sentence.position}</span>
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
            window.DictaLabAdmin.clean(elements.adminLessonForm);
            state.adminEditingNew = false;
            await refreshAdminLessons(updated.id);
            // Sau khi tạo mới, thay URL /admin/lessons/new → /admin/lessons/{id}
            if (creating) {
                router.replace("admin-lesson", { lessonId: updated.id });
            }
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
            window.DictaLabAdmin.clean(elements.adminAddSentenceForm);
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

        if (action === "delete" && !window.confirm(`Xóa câu ${sentence.position}? Audio tải lên chỉ được dọn khi không còn bài hoặc câu nào sử dụng.`)) {
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
                if(action === "save" || action === "delete") window.DictaLabAdmin.clean(card);
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
        if (!confirm(lesson.published ? "Gỡ xuất bản bài này? Lịch sử học vẫn được giữ." : "Xuất bản nội dung đã lưu trên máy chủ? Các thay đổi chưa lưu trong form chưa được xuất bản.")) return;
        elements.adminPublicationButton.disabled = true;
        try {
            const updated = await api(`/api/admin/lessons/${lesson.id}/publication`, {
                method: "PUT", body: {published: !lesson.published}
            });
            await refreshAdminLessons(updated.id);
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

    /* ─── History Functions ─── */

    async function loadHistory(requestedPage = new URLSearchParams(window.location.search).get("page"), focusPage = false) {
        const numericPage = Number(requestedPage);
        const page = Number.isSafeInteger(numericPage) ? Math.min(100001, Math.max(1, numericPage)) : 1;
        const level = state.historyFilter;
        const generation = ++state.historyLoadGeneration;
        const query = new URLSearchParams({page: page - 1, size: 10});
        if (level !== "all") query.set("level", level);
        const focusLevel = document.activeElement?.dataset?.historyLevel;
        elements.historyList.setAttribute("aria-busy", "true");
        const errorPanel = document.querySelector("#history-load-error");
        if (errorPanel) errorPanel.hidden = true;
        try {
            const result = await api(`/api/v1/history?${query}`);
            if (generation !== state.historyLoadGeneration || document.body.dataset.route !== "history") return;
            if (result.totalPages > 0 && result.page >= result.totalPages) {
                router.replace("history", {level, page: result.totalPages});
                await loadHistory(result.totalPages, focusPage);
                return;
            }
            state.historySessions = result.items;
            state.historyPage = result;
            router.replace("history", {level, page: result.totalPages ? result.page + 1 : 1});
            renderHistoryLevelTabs();
            renderHistoryList();
            renderHistoryPagination(focusPage);
            if (focusLevel) elements.historyLevelTabs.querySelector(`[data-history-level="${level}"]`)?.focus();
        } catch (error) {
            if (generation === state.historyLoadGeneration && document.body.dataset.route === "history") {
                elements.historyEmpty.hidden = true;
                if (errorPanel) errorPanel.hidden = false;
                showToast(error.message, true);
            }
        } finally {
            if (generation === state.historyLoadGeneration) elements.historyList.setAttribute("aria-busy", "false");
        }
    }

    function renderHistoryLevelTabs() {
        const levels = ["all", "A1", "A2", "B1", "B2"];
        const levelLabels = { all: "Tất cả", A1: "A1", A2: "A2", B1: "B1", B2: "B2" };
        elements.historyLevelTabs.innerHTML = levels.map(level => `
            <button class="level-tab${level === state.historyFilter ? " is-active" : ""}" type="button"
                    role="tab" aria-selected="${level === state.historyFilter}" tabindex="${level === state.historyFilter ? 0 : -1}"
                    data-history-level="${escapeAttribute(level)}">
                <strong>${escapeHtml(levelLabels[level] || level)}</strong>
            </button>
        `).join("");

        const tabs = [...elements.historyLevelTabs.querySelectorAll("[role='tab']")];
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
            selectHistoryLevel(tabs[nextIndex].dataset.historyLevel);
        }));
    }

    function selectHistoryLevel(level) {
        const restoreFocus = Boolean(document.activeElement?.dataset?.historyLevel);
        state.historyFilter = level;
        const historyLink = document.querySelector('a[data-route="history"]');
        if (historyLink) historyLink.href = router.toPath("history", {level, page: 1});
        // Cập nhật URL query param
        router.replace("history", { level });
        renderHistoryLevelTabs();
        if (restoreFocus) elements.historyLevelTabs.querySelector(`[data-history-level="${level}"]`)?.focus();
        loadHistory(1);
    }

    function renderHistoryList() {
        const filtered = state.historySessions;

        elements.historyEmpty.hidden = filtered.length > 0;
        const levelFiltered = state.historyFilter !== "all";
        elements.historyEmpty.querySelector("p").textContent = levelFiltered
            ? `Chưa có buổi luyện ở trình độ ${state.historyFilter}. Thử xem tất cả trình độ hoặc bắt đầu một bài mới.`
            : "Chưa có buổi luyện nào. Hoàn thành bài đầu tiên để bắt đầu nhật ký.";
        elements.historyEmpty.querySelector("button").hidden = !levelFiltered;
        elements.historyEmpty.querySelector("a").href = router.toPath("catalog", {level: levelFiltered ? state.historyFilter : "A1", page: 1});
        elements.historyCount.textContent = state.historyPage.totalElements
            ? `${state.historyPage.totalElements} buổi luyện`
            : "";

        elements.historyList.innerHTML = filtered.map(session => {
            const score = Math.round(session.overallScore);
            const scoreClass = score >= 85 ? "is-excellent" : score >= 65 ? "is-good" : score >= 40 ? "is-fair" : "is-needs-work";
            const date = session.completedAt ? new Date(session.completedAt) : new Date();
            const dateStr = date.toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit", year: "numeric" });
            const timeStr = date.toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" });
            const practiceMinutes = Math.max(1, Math.round(session.practiceSeconds / 60));
            return `
                <a class="history-card" href="${router.toPath("result", {sessionId: session.sessionId})}"
                         data-history-session-id="${escapeAttribute(session.sessionId)}" aria-label="Xem lại ${escapeAttribute(session.lessonTitle)}">
                    <div class="history-score-badge ${scoreClass}">${score}</div>
                    <div class="history-card-body">
                        <h3>
                            <span class="history-level-badge" data-level="${escapeAttribute(session.level)}">${escapeHtml(session.level)}</span>
                            ${escapeHtml(session.lessonTitle)}
                        </h3>
                        <p class="history-card-meta">
                            <span>${escapeHtml(dateStr)} ${escapeHtml(timeStr)}</span>
                            <span>${practiceMinutes} phút</span>
                            <span>${session.totalReplays} lượt nghe</span>
                            <span class="history-exposure-badge ${session.firstExposure ? "is-first" : "is-redo"}">
                                ${session.firstExposure ? "Lần đầu" : "Làm lại"}
                            </span>
                        </p>
                    </div>
                    <div class="history-card-stats">
                        <div class="history-stat">
                            <strong>${formatPercent(session.firstAttemptAccuracy)}%</strong>
                            <small>First attempt</small>
                        </div>
                        <div class="history-stat">
                            <strong>${formatPercent(session.finalAccuracy)}%</strong>
                            <small>Final</small>
                        </div>
                    </div>
                </a>
            `;
        }).join("");
    }

    function renderHistoryPagination(focus = false) {
        if (elements.historyList.nextElementSibling?.matches(".pagination")) elements.historyList.nextElementSibling.remove();
        const result = state.historyPage;
        const page = result.page + 1;
        const nav = document.createElement("nav");
        nav.className = "pagination";
        nav.setAttribute("aria-label", "Phân trang lịch sử luyện nghe");
        nav.hidden = result.totalPages <= 1;
        nav.innerHTML = `<button type="button" class="text-button" data-page="${page - 1}" ${page <= 1 ? "disabled" : ""}>← Trang trước</button>
            <span role="status" tabindex="-1">Trang ${page}/${Math.max(1, result.totalPages)} · ${result.totalElements} buổi luyện</span>
            <button type="button" class="text-button" data-page="${page + 1}" ${page >= result.totalPages ? "disabled" : ""}>Trang sau →</button>`;
        elements.historyList.after(nav);
        nav.querySelectorAll("button").forEach(button => button.addEventListener("click", () => {
            router.push("history", {level: state.historyFilter, page: button.dataset.page});
            loadHistory(Number(button.dataset.page), true);
        }));
        if (focus && !nav.hidden) nav.querySelector("[role='status']").focus({preventScroll: true});
    }

    async function viewHistorySession(sessionId) {
        try {
            const result = await api(`/api/v1/history/${encodeURIComponent(sessionId)}`);
            renderResult(result, true);
            // Push URL /history/{sessionId}
            router.push("result", { sessionId });
            _showView("result");
        } catch (error) {
            showToast(error.message, true);
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

    let tokenRefreshPromise = null;

    async function refreshAuthentication() {
        if (tokenRefreshPromise) return tokenRefreshPromise;
        // Web Locks coordinates refresh rotation across tabs without storing tokens in JavaScript.
        if (!navigator.locks) {
            const error = new Error("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
            error.status = 401;
            throw error;
        }
        tokenRefreshPromise = navigator.locks.request("dictalab-token-refresh", async () => {
            // Another tab/request may already have refreshed while this tab waited for the lock.
            const probe = await fetch("/api/auth/me", {credentials: "same-origin", cache: "no-store"});
            if (probe.ok) return;
            if (probe.status !== 401) {
                const error = new Error("Không thể kiểm tra phiên đăng nhập. Vui lòng thử lại.");
                error.status = probe.status;
                throw error;
            }
            const response = await fetch("/api/auth/refresh", {
                method: "POST", credentials: "same-origin", cache: "no-store",
                headers: {[state.csrfHeader]: state.csrfToken}, signal: AbortSignal.timeout(10000)
            });
            if (!response.ok) {
                const payload = await response.json().catch(() => null);
                const error = new Error(payload?.message || "Không thể làm mới phiên đăng nhập.");
                error.status = response.status;
                throw error;
            }
        });
        try { return await tokenRefreshPromise; }
        finally { tokenRefreshPromise = null; }
    }

    async function authenticatedFetch(path, request, allowRefresh = true) {
        let response = await fetch(path, request);
        // Only a rejected authentication response is replayed; never retry network/503 failures.
        if (response.status === 401 && allowRefresh) {
            await refreshAuthentication();
            response = await fetch(path, request);
        }
        return response;
    }

    async function api(path, options = {}) {
        const request = {
            method: options.method || "GET",
            headers: {
                "Accept": "application/json"
            },
            credentials: "same-origin"
        };
        if (options.headers) Object.assign(request.headers, options.headers);
        if (isAdminArea && !["GET","HEAD","OPTIONS"].includes(request.method) && /^\/api\/admin\/lessons\/\d+(?:\/|$)/.test(path) && state.adminSelectedLesson?.updatedAt) {
            request.headers["X-Edit-Version"] = state.adminSelectedLesson.updatedAt;
        }
        if (options.formData !== undefined) {
            request.body = options.formData;
        } else if (options.body !== undefined) {
            request.headers["Content-Type"] = "application/json";
            request.body = JSON.stringify(options.body);
        }
        if (!["GET", "HEAD", "OPTIONS"].includes(request.method) && state.csrfToken) {
            request.headers[state.csrfHeader] = state.csrfToken;
        }
        let response;
        try {
            const send = () => authenticatedFetch(path, request,
                !path.startsWith("/api/auth/") || path === "/api/auth/me");
            response = navigator.locks && ["/api/auth/login", "/api/auth/register", "/api/auth/logout"].includes(path)
                ? await navigator.locks.request("dictalab-token-refresh", send) : await send();
        } catch (error) {
            if (error.status === 401 && options.redirectOnUnauthorized !== false)
                showAuthScreen("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
            throw error;
        }
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

    // ====================================================================
    // ROOM MODULE — Luyện cùng bạn (Voice Room 1-1)
    // ====================================================================

    // ---- Lobby ---------------------------------------------------------

    function roomInitLobby() {
        // Hiển thị lobby; ẩn call/pending panel
        elements.roomLobby.hidden = false;
        elements.roomCallPanel.hidden = true;
        elements.roomPendingPanel.hidden = true;
        elements.roomLobbyStatus.textContent = "";
        elements.roomInviteInput.value = "";
        elements.roomStudyStatus.textContent = "Chưa chọn bài học chung.";
        elements.roomChooseLesson.hidden = true;
        elements.roomReturnPractice.hidden = true;
    }

    function roomShowPending(room) {
        elements.roomLobby.hidden = true;
        elements.roomCallPanel.hidden = true;
        elements.roomPendingPanel.hidden = false;
        elements.roomPendingHostName.textContent = `Bạn đã gửi yêu cầu tham gia phòng của ${room.host.displayName}.`;
        elements.roomPendingStatus.textContent = "Đang chờ chủ phòng chấp nhận…";
    }

    function roomRenderPeerDrafts(words = []) {
        if (!state.sentence || !state.roomSharedSessionId) return;
        elements.peerWordSlots.replaceChildren(...state.sentence.words.map((word, index) => {
            const token = document.createElement("span");
            token.textContent = words[index] || word.mask;
            token.setAttribute("role", "group");
            token.setAttribute("aria-label", `Từ ${index + 1}: ${words[index] || "chưa nhập"}`);
            return token;
        }));
    }

    function roomRenderStudy(room) {
        if (!room || !state.room) return;
        const statusBefore = state.roomStatus;
        if (state.roomStudy?.lessonPosition === room.lessonPosition
            && (room.peerDraftSequence || 0) < (state.roomStudy.peerDraftSequence || 0)) {
            room.peerDraftSequence = state.roomStudy.peerDraftSequence;
            room.peerDrafts = state.roomStudy.peerDrafts;
        }
        if (state.roomDraftPosition !== room.lessonPosition) {
            state.roomDraftPosition = room.lessonPosition;
            state.roomDraftSequence = 0;
            state.roomPeerDraftSequence = 0;
        }
        state.roomPeerDraftSequence = Math.max(state.roomPeerDraftSequence, room.peerDraftSequence || 0);
        state.roomStatus = room.status;
        if (statusBefore !== "ACTIVE" && room.status === "ACTIVE" && !state.roomIsHost
            && (!state.roomWs || state.roomWs.readyState === WebSocket.CLOSED)
            && state.room?.roomId) roomConnectWebSocket(state.room.roomId);
        state.roomStudy = room.lessonId ? room : null;
        elements.roomReturnPractice.hidden = !state.roomSharedSessionId || state.roomSharedCompleted;
        if (state.roomSharedSessionId) {
            elements.roomReturnPractice.href = router.toPath("practice", {sessionId: state.roomSharedSessionId});
        }
        elements.roomChooseLesson.hidden = !state.roomIsHost || room.status !== "ACTIVE" || Boolean(room.lessonId);
        elements.roomStudyStatus.textContent = room.lessonId
            ? `Bài học chung: ${state.session?.lessonId === room.lessonId ? state.session.lessonTitle : `bài số ${room.lessonId}`}. Câu ${Math.min(room.lessonPosition, room.lessonSentenceCount)}/${room.lessonSentenceCount}.`
            : "Chưa chọn bài học chung. Chủ phòng chọn bài khi cả hai đã vào phòng.";
        const shared = !state.roomSharedCompleted && state.roomSharedSessionId === state.session?.sessionId && Boolean(room.lessonId);
        elements.sharedPracticeBar.hidden = !shared;
        elements.peerPracticePanel.hidden = !shared;
        if (!shared || !state.sentence) return;
        state.roomReady = state.roomIsHost ? room.hostReady : room.guestReady;
        const peerReady = state.roomIsHost ? room.guestReady : room.hostReady;
        const peerName = state.roomIsHost ? room.guest?.displayName : room.host?.displayName;
        elements.peerPracticeHeading.textContent = `${peerName || "Bạn học"} đang nhập`;
        elements.peerPracticeStatus.textContent = `${state.roomReady ? "Bạn đã sẵn sàng. " : ""}${peerReady ? "Bạn học đã sẵn sàng." : "Đang chờ bạn học hoàn thành."}`;
        elements.roomAdvanceSentence.hidden = !state.roomIsHost;
        elements.roomAdvanceSentence.disabled = !(room.hostReady && room.guestReady) || Boolean(state.roomPlayback?.playing);
        roomRenderPeerDrafts(room.peerDrafts);
        roomApplyPlayback(room.playback, room.serverNow);
        roomPrepareStudyAudio();
        updatePracticeControls();
    }

    async function roomEnableStudyAudio() {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) return setPracticeStatus("Trình duyệt này không hỗ trợ nghe chung. Hãy dùng Chrome hoặc Edge.", true);
        try {
            state.audioContext ||= new AudioContextClass();
            await state.audioContext.resume();
            if (state.audioContext.state !== "running") throw new Error("Chưa bật được âm thanh. Hãy bấm Bật nghe chung lại.");
            state.roomAudioEnabled = true;
            state.roomAudioPreparedKey = null;
            await roomPrepareStudyAudio();
        } catch (error) {
            setPracticeStatus(error.message, true);
        }
        updatePracticeControls();
    }

    async function roomPrepareStudyAudio() {
        if (!state.room || !state.roomStudy || !state.sentence || !state.roomAudioEnabled
            || state.audioContext?.state !== "running" || document.body.dataset.route !== "practice"
            || state.roomSharedSessionId !== state.session?.sessionId || state.roomWs?.readyState !== WebSocket.OPEN) return;
        const key = `${state.room.roomId}:${state.sentence.sentenceId}`;
        if (key === state.roomAudioPreparedKey || key === state.roomAudioPreparingKey) return;
        const position = state.sentence.position;
        const audioUrl = state.sentence.audioUrl;
        if (!audioUrl) {
            elements.roomStudyAudioStatus.textContent = "Bài này chưa có file audio để nghe chung. Chủ phòng hãy chọn bài có audio.";
            return;
        }
        state.roomAudioPreparingKey = key;
        try {
            await loadAudioBuffer(audioUrl);
            if (`${state.room?.roomId}:${state.sentence?.sentenceId}` !== key
                || document.body.dataset.route !== "practice" || state.audioContext?.state !== "running") return;
            if (roomSendWs({type: "audio-ready", position, ready: true})) state.roomAudioPreparedKey = key;
        } catch (error) {
            state.roomAudioEnabled = false;
            setPracticeStatus(error.message, true);
        } finally {
            if (state.roomAudioPreparingKey === key) state.roomAudioPreparingKey = null;
        }
    }

    function roomApplyPlayback(playback, serverNow) {
        if (!playback || !state.roomStudy || state.roomSharedSessionId !== state.session?.sessionId
            || playback.position !== state.sentence?.position
            || (state.roomPlayback && playback.version < state.roomPlayback.version)) return;
        state.roomPlayback = playback;
        if (!state.roomIsHost) {
            state.playbackSpeed = playback.speed;
            elements.playbackSpeed.value = String(playback.speed);
        }
        elements.replayCount.textContent = playback.replayCount;
        updatePracticeControls();
        if (!playback.startsAt || playback.sequence <= state.roomPlaybackSeen) return;
        state.roomPlaybackSeen = playback.sequence;
        if (!playback.playing) return; // Reconnect must not replay an already-finished command.
        const delay = Date.parse(playback.startsAt) - Date.parse(serverNow);
        if (!Number.isFinite(delay) || delay < -1500 || !state.roomAudioEnabled
            || state.audioContext?.state !== "running" || document.body.dataset.route !== "practice") {
            roomSendWs({type: "audio-ready", position: playback.position, ready: false});
            roomSendWs({type: "lesson-played", position: playback.position, sequence: playback.sequence});
            state.roomAudioPreparedKey = null;
            setPracticeStatus("Chưa nghe được lượt chung này. Bật nghe chung và nhờ chủ phòng phát lại.", true);
            return;
        }
        const localStartsAt = state.roomAudioClockOffset == null
            ? Date.now() + Math.max(0, delay) : Date.parse(playback.startsAt) - state.roomAudioClockOffset;
        playSentence({...playback, localStartsAt});
    }

    async function roomSyncStudy(room) {
        if (!state.room || !room?.lessonId || state.roomStudySyncing) return;
        if (state.roomSharedSessionId && !state.roomSharedCompleted
            && state.sentence?.position === room.lessonPosition && state.roomStudy?.lessonPosition === room.lessonPosition
            && state.roomStudy?.hostReady === room.hostReady && state.roomStudy?.guestReady === room.guestReady
            && state.roomPlayback?.version === room.playback?.version
            && JSON.stringify(state.roomStudy?.peerDrafts) === JSON.stringify(room.peerDrafts)) {
            roomPrepareStudyAudio();
            return;
        }
        state.roomStudySyncing = true;
        try {
            roomRenderStudy(room);
            roomPrepareStudyAudio();
            if (!state.roomSharedSessionId) {
                const session = await api(`/api/v1/rooms/${encodeURIComponent(state.room.roomId)}/practice`, {method: "POST"});
                state.roomSharedSessionId = session.sessionId;
                if (!session.sentence) {
                    state.roomSharedCompleted = true;
                    await viewHistorySession(session.sessionId);
                    return;
                }
                state.session = session;
                state.sentence = session.sentence;
                state.wordDrafts.clear();
                state.roomReady = state.roomIsHost ? room.hostReady : room.guestReady;
                state.roomStudy = room;
                renderPractice(true);
                router.replace("practice", {sessionId: session.sessionId});
                _showView("practice");
            } else if (state.roomSharedCompleted) {
                return;
            }
            if (!state.roomSharedCompleted && state.roomSharedSessionId === state.session?.sessionId
                && state.sentence?.position < room.lessonPosition) {
                await roomCatchUp(room);
            }
            roomRenderStudy(room);
        } catch (error) {
            elements.roomStudyStatus.textContent = error.message;
            if (document.body.dataset.route === "practice") setPracticeStatus(error.message, true);
        } finally {
            state.roomStudySyncing = false;
        }
    }

    async function roomCatchUp(room) {
        while (state.sentence && state.sentence.position < room.lessonPosition) {
            if (!state.sentence.canComplete) {
                throw new Error("Câu hiện tại chưa hoàn thành; không thể đồng bộ bài học chung.");
            }
            const oldPosition = state.sentence.position;
            await completeSentence(null, true);
            if (!state.roomSharedCompleted && state.sentence?.position === oldPosition) {
                throw new Error("Không thể chuyển sang câu tiếp theo. Hãy thử lại.");
            }
        }
    }

    async function roomMarkReady() {
        try {
            window.clearTimeout(state.roomDraftTimer);
            const words = state.sentence.words.map(word => word.revealed ? "" : (
                elements.wordSlots.querySelector(`[data-word-index="${word.index}"]`)?.value
                ?? state.wordDrafts.get(word.index) ?? word.userInput ?? ""
            ).slice(0, 80));
            roomSendWs({type: "draft", position: state.sentence.position, sequence: ++state.roomDraftSequence, words});
            const room = await api(`/api/v1/rooms/${encodeURIComponent(state.room.roomId)}/ready`, {method: "POST"});
            roomRenderStudy(room);
        } catch (error) {
            setPracticeStatus(error.message, true);
        }
    }

    async function roomAdvanceLesson() {
        elements.roomAdvanceSentence.disabled = true;
        try {
            const room = await api(`/api/v1/rooms/${encodeURIComponent(state.room.roomId)}/advance`, {method: "POST"});
            state.roomStudy = null;
            await roomSyncStudy(room);
        } catch (error) {
            showToast(error.message, true);
            elements.roomAdvanceSentence.disabled = false;
        }
    }

    function roomQueueDraft() {
        if (!state.room || state.roomSharedSessionId !== state.session?.sessionId || !state.sentence
            || state.roomReady) return;
        window.clearTimeout(state.roomDraftTimer);
        state.roomDraftTimer = window.setTimeout(() => {
            if (!state.room || state.roomReady || !state.sentence || state.roomStudy?.lessonPosition !== state.sentence.position) return;
            const words = state.sentence.words.map(word => {
                if (word.revealed) return "";
                return elements.wordSlots.querySelector(`[data-word-index="${word.index}"]`)?.value
                    ?? state.wordDrafts.get(word.index) ?? word.userInput ?? "";
            });
            roomSendWs({type: "draft", position: state.sentence.position,
                sequence: ++state.roomDraftSequence, words: words.map(word => word.slice(0, 80))});
        }, 150);
    }

    async function roomRestoreMine() {
        try {
            const room = await api("/api/v1/rooms/mine");
            if (!room) { roomInitLobby(); return; }
            state.room = {roomId: room.roomId, inviteCode: room.inviteCode};
            state.roomIsHost = room.host.userId === state.currentUser.id;
            router.replace("room-id", {roomId: room.roomId});
            if (room.status === "PENDING_APPROVAL" && room.pending?.userId === state.currentUser.id) {
                roomShowPending(room);
            } else {
                await roomEnterCallPanel(room.roomId);
                if (!state.roomWs || state.roomWs.readyState === WebSocket.CLOSED) roomConnectWebSocket(room.roomId);
            }
            roomStartPolling(room.roomId);
        } catch (error) {
            roomInitLobby();
            elements.roomLobbyStatus.textContent = error.message;
        }
    }

    async function roomCreateNew() {
        elements.roomCreateBtn.disabled = true;
        elements.roomLobbyStatus.textContent = "Đang tạo phòng…";
        try {
            const data = await api("/api/v1/rooms", {method: "POST"});
            state.room = {roomId: data.roomId, inviteCode: data.inviteCode};
            // Dùng /rooms/join/{inviteCode} thay vì /?invite= (backward compat giữ nguyên ở backend)
            const origin = window.location.origin;
            state.roomInviteUrl = `${origin}/rooms/join/${encodeURIComponent(data.inviteCode)}`;
            state.roomIsHost = true;
            // Cập nhật URL sang /rooms/{roomId}
            router.push("room-id", { roomId: data.roomId });
            await roomEnterCallPanel(data.roomId);
            roomConnectWebSocket(data.roomId);
            roomStartPolling(data.roomId);
        } catch (error) {
            if (error.status === 409) await roomRestoreMine();
            else elements.roomLobbyStatus.textContent = error.message;
        } finally {
            elements.roomCreateBtn.disabled = false;
        }
    }

    function parseRoomInvite(input) {
        let code = String(input).trim();
        let url;
        try { url = new URL(code); } catch (_) { /* mã mời thuần */ }
        if (url) {
            if (!/^https?:$/.test(url.protocol)) {
                return {error: "Link mời không hợp lệ."};
            }
            if (url.pathname === "/" && url.searchParams.has("invite")) {
                code = url.searchParams.get("invite");
            } else {
                const match = url.pathname.match(/^\/rooms\/join\/([^/]+)\/?$/);
                if (match) {
                    code = match[1];
                } else if (/^\/rooms\/[^/]+\/?$/.test(url.pathname)) {
                    return {error: "Đây là địa chỉ phòng. Hãy nhờ chủ phòng bấm Sao chép link mời."};
                } else {
                    return {error: "Link mời không hợp lệ."};
                }
            }
        }
        if (!/^[A-Za-z0-9_-]{22}$/.test(code)) {
            return {error: "Mã mời không hợp lệ. Hãy dùng link mời do chủ phòng chia sẻ."};
        }
        return {code};
    }

    async function roomOpenByInviteCode(inviteInput) {
        const {code: inviteCode, error} = parseRoomInvite(inviteInput);
        if (error) {
            elements.roomLobbyStatus.textContent = error;
            return;
        }
        if (state.room) {
            await resolveUrlAndNavigate({route: "room-id", params: {roomId: state.room.roomId}}, false);
            return;
        }
        router.push("room-join", {inviteCode});
        elements.roomLobbyStatus.textContent = "Đang tải thông tin phòng…";
        try {
            // Reloading an invitation must restore membership before checking capacity.
            const mine = await api("/api/v1/rooms/mine");
            if (mine) {
                await roomRestoreMine();
                return;
            }
            const info = await api(`/api/v1/rooms/join/${encodeURIComponent(inviteCode)}`);
            if (info.expired) {
                elements.roomLobbyStatus.textContent = "Lời mời đã hết hạn.";
                return;
            }
            if (info.full) {
                elements.roomLobbyStatus.textContent = "Phòng đã đủ 2 người.";
                return;
            }
            // Guest gửi yêu cầu tham gia
            const join = await api(`/api/v1/rooms/${encodeURIComponent(info.roomId)}/join`, {method: "POST"});
            state.room = {roomId: info.roomId, inviteCode};
            state.roomIsHost = false;
            // Hiển thị pending panel
            roomShowPending({host: {displayName: info.hostDisplayName}});
            // Kết nối WS sau khi chủ phòng duyệt.
            roomStartPolling(info.roomId);
        } catch (error) {
            if (error.status === 409) await roomRestoreMine();
            else elements.roomLobbyStatus.textContent = error.message;
        }
    }

    // ---- Call Panel UI -------------------------------------------------

    async function roomEnterCallPanel(roomId) {
        try {
            const state_ = await api(`/api/v1/rooms/${encodeURIComponent(roomId)}`);
            elements.roomLobby.hidden = true;
            elements.roomCallPanel.hidden = false;
            elements.roomPendingPanel.hidden = true;

            // Cập nhật header
            const shortId = roomId.substring(0, 8).toUpperCase();
            elements.roomCode.textContent = `Phòng ${shortId}`;

            // Host info
            elements.roomHostName.textContent = state_.host.displayName;
            elements.roomHostActivity.textContent = state.roomLocalStream && state.roomIsHost ? "Mic đang bật" : "Mic chưa bật";
            const myId = state.currentUser.id || 0;
            elements.roomHostCard.classList.remove("is-you");
            elements.roomGuestCard.classList.remove("is-you");
            if (state_.host.userId == myId || state.roomIsHost) {
                if (state_.inviteCode) state.roomInviteUrl = `${window.location.origin}/rooms/join/${encodeURIComponent(state_.inviteCode)}`;
                elements.roomHostTag.textContent = "Bạn · Chủ phòng";
                elements.roomHostCard.classList.add("is-you");
            } else {
                elements.roomHostTag.textContent = "Chủ phòng";
            }

            // Guest info
            if (state_.guest) {
                elements.roomGuestName.textContent = state_.guest.displayName;
                elements.roomGuestActivity.textContent = state.roomLocalStream && !state.roomIsHost ? "Mic đang bật" : "Mic chưa bật";
                if (!state.roomIsHost) {
                    elements.roomGuestTag.textContent = "Bạn";
                    elements.roomGuestCard.classList.add("is-you");
                } else {
                    elements.roomGuestTag.textContent = "Khách";
                }
            } else {
                elements.roomGuestName.textContent = "Chờ bạn tham gia…";
                elements.roomGuestTag.textContent = "Chưa có ai";
                elements.roomGuestActivity.textContent = "Chưa kết nối";
            }

            roomUpdateStatus(state_.status);
            roomSyncCallClock(state_);

            // Nút join call: hiện sau khi approved (ACTIVE)
            elements.roomJoinCallBtn.hidden = state_.status !== "ACTIVE" || Boolean(state.roomPc);
            // Mic button disabled cho đến khi join call
            elements.roomMicBtn.disabled = !state.roomLocalStream;

            // Approval panel
            elements.roomApprovalPanel.hidden = true;

            // Error box ẩn
            elements.roomErrorBox.hidden = true;
            if (!state.roomPc) elements.roomAutoplayNotice.hidden = true;
            roomRenderStudy(state_);
            if (state_.lessonId && !state.roomSharedSessionId) await roomSyncStudy(state_);

        } catch (error) {
            showToast(error.message, true);
        }
    }

    function roomUpdateStatus(status) {
        if (["CONNECTED", "CONNECTING_AUDIO", "DISCONNECTED", "ERROR"].includes(status)) {
            state.roomMediaStatus = status;
        } else {
            state.roomStatus = status;
        }
        const visibleStatus = state.roomPc && state.roomMediaStatus ? state.roomMediaStatus : state.roomStatus;
        const badge = elements.roomStatusBadge;
        const labels = {
            WAITING_GUEST: "Đang chờ bạn tham gia",
            PENDING_APPROVAL: "Đang chờ duyệt",
            ACTIVE: "Phòng sẵn sàng",
            CONNECTED: "Đang gọi",
            CONNECTING_AUDIO: "Đã kết nối, đang chờ âm thanh",
            DISCONNECTED: "Mất kết nối",
            ERROR: "Lỗi kết nối",
            CLOSED: "Phòng đã đóng"
        };
        badge.textContent = labels[visibleStatus] || visibleStatus || "Đang tải phòng";
        badge.dataset.status = visibleStatus || "";
        elements.sharedCallStatus.textContent = badge.textContent;
    }

    function roomShowError(message, hint = "") {
        elements.roomErrorBox.hidden = false;
        elements.roomErrorText.textContent = message;
        elements.roomErrorHint.textContent = hint;
    }

    function roomClearError() {
        elements.roomErrorBox.hidden = true;
        elements.roomErrorText.textContent = "";
        elements.roomErrorHint.textContent = "";
    }

    async function roomEnableAudio() {
        const pc = state.roomPc;
        const audio = elements.roomRemoteAudio;
        if (!pc || !audio.srcObject) return;
        audio.muted = false;
        audio.volume = 1;
        try {
            await audio.play();
            if (pc !== state.roomPc) return;
            elements.roomAutoplayNotice.hidden = true;
            elements.sharedEnableAudio.hidden = true;
            await roomCheckAudio(pc);
        } catch (_) {
            if (pc !== state.roomPc) return;
            elements.roomAutoplayNotice.hidden = false;
            elements.sharedEnableAudio.hidden = false;
            roomUpdateStatus("CONNECTING_AUDIO");
        }
    }

    // ---- WebSocket Signaling -------------------------------------------

    async function roomConnectWebSocket(roomId) {
        if (state.roomWsConnecting) return;
        state.roomWsConnecting = true;
        try {
            // Browser WebSocket uses the same HttpOnly access cookie; no token in the URL.
            await api("/api/auth/me");
        } catch (error) {
            roomShowError(error.message, "Thử kết nối lại sau khi phiên đăng nhập khả dụng.");
            roomUpdateStatus("DISCONNECTED");
            elements.roomReconnectBtn.hidden = false;
            elements.sharedReconnect.hidden = false;
            return;
        } finally { state.roomWsConnecting = false; }
        if (!state.room || state.room.roomId !== roomId) return;
        if (state.roomWs && state.roomWs.readyState !== WebSocket.CLOSED) {
            state.roomWs.close();
        }
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        const wsUrl = `${protocol}//${window.location.host}/ws/room/${encodeURIComponent(roomId)}`;
        const ws = new WebSocket(wsUrl);
        state.roomWs = ws;

        ws.addEventListener("open", () => {
            if (ws !== state.roomWs) return;
            state.roomWsReconnectCount = 0;
            roomWakePolling();
            roomClearError();
            if (state.roomPc) roomSendWs({type: "ready"});
            roomQueueDraft();
            roomSendMicState();
            state.roomAudioPreparedKey = null;
            roomPrepareStudyAudio();
            window.clearInterval(state.roomWsHeartbeatTimer);
            state.roomWsHeartbeatTimer = window.setInterval(() => roomSendWs({type: "ping"}), 20000);
        });

        ws.addEventListener("message", event => {
            if (ws !== state.roomWs) return;
            let msg;
            try { msg = JSON.parse(event.data); } catch (_) { return; }
            roomHandleWsMessage(msg);
        });

        ws.addEventListener("close", event => {
            if (ws !== state.roomWs) return;
            state.roomAudioPreparedKey = null;
            if (state.roomPlayback) {
                state.roomPlayback.hostAudioReady = false;
                state.roomPlayback.guestAudioReady = false;
            }
            if (state.roomStudy && state.sentence) updatePracticeControls();
            window.clearInterval(state.roomWsHeartbeatTimer);
            state.roomWsHeartbeatTimer = null;
            if (event.code === 1000 || !state.room) return; // đóng bình thường
            if (event.code === 4001) {
                roomShowError("Phòng đang mở ở tab khác.", "Đóng tab kia rồi bấm Kết nối lại.");
                roomUpdateStatus("DISCONNECTED");
                elements.roomReconnectBtn.hidden = false;
                elements.sharedReconnect.hidden = false;
                return;
            }
            // Thử reconnect tối đa 3 lần
            if (state.roomWsReconnectCount < 3) {
                state.roomWsReconnectCount++;
                state.roomWsReconnectTimer = window.setTimeout(() => {
                    if (state.room) roomConnectWebSocket(state.room.roomId);
                }, 3000 * state.roomWsReconnectCount);
            } else {
                roomUpdateStatus("DISCONNECTED");
                elements.roomReconnectBtn.hidden = false;
                elements.sharedReconnect.hidden = false;
            }
        });

        ws.addEventListener("error", () => {
            // Lỗi WS sẽ theo sau là "close" — không xử lý ở đây
        });
    }

    function roomHandleWsMessage(msg) {
        switch (msg.type) {
            case "room-changed":
                roomWakePolling();
                break;
            case "peer-joined":
                roomOnPeerJoined(msg);
                break;
            case "peer-disconnected":
                roomOnPeerDisconnected(msg);
                break;
            case "waiting-for-peer":
                roomUpdateStatus(state.roomIsHost ? "WAITING_GUEST" : "PENDING_APPROVAL");
                break;
            case "ready":
                // Cả hai đều gửi "ready". Host tạo offer khi nhận từ guest.
                if (state.roomIsHost) {
                    roomOnPeerReady();
                }
                break;
            case "offer":
                roomHandleOffer(msg);
                break;
            case "answer":
                roomHandleAnswer(msg);
                break;
            case "ice-candidate":
                roomHandleIceCandidate(msg);
                break;
            case "draft":
                if (msg.fromUserId !== state.currentUser?.id
                    && msg.position === state.sentence?.position
                    && msg.sequence > state.roomPeerDraftSequence
                    && Array.isArray(msg.words) && state.roomStudy) {
                    state.roomPeerDraftSequence = msg.sequence;
                    state.roomStudy.peerDrafts = msg.words;
                    state.roomStudy.peerDraftSequence = msg.sequence;
                    roomRenderPeerDrafts(msg.words);
                }
                break;
            case "mic-state":
                roomOnPeerMicState(msg);
                break;
            case "call-started":
                roomStartCallClock(msg.startedAt, msg.serverNow);
                break;
            case "lesson-audio":
                roomApplyPlayback(msg.playback, msg.serverNow);
                break;
            case "pong":
                break; // heartbeat OK
            case "error":
                roomShowError(msg.message || "Lỗi không xác định từ server.", "");
                break;
            default:
                break;
        }
    }

    function roomSendWs(data) {
        if (state.roomWs && state.roomWs.readyState === WebSocket.OPEN) {
            state.roomWs.send(JSON.stringify(data));
            return true;
        }
        return false;
    }

    // ---- Peer events ---------------------------------------------------

    function roomOnPeerJoined(msg) {
        const displayName = escapeHtml(msg.displayName || "Bạn");
        if (state.roomIsHost) {
            // Guest đã kết nối WS → cập nhật UI
            elements.roomGuestName.textContent = msg.displayName || "Khách";
            elements.roomGuestTag.textContent = "Khách";
            roomUpdateStatus("ACTIVE");
            elements.roomJoinCallBtn.hidden = Boolean(state.roomPc);
            // Ẩn approval panel (đã được approve rồi)
            elements.roomApprovalPanel.hidden = true;
        } else {
            // Host kết nối (sau khi guest approve) — guest nhận thông báo
            elements.roomHostName.textContent = msg.displayName || "Chủ phòng";
            roomUpdateStatus("ACTIVE");
            elements.roomJoinCallBtn.hidden = Boolean(state.roomPc);
        }
        showToast(`${displayName} đã vào phòng.`);
        if (state.roomPc) {
            roomSendWs({type: "ready"});
            roomSendMicState();
        }
    }

    function roomOnPeerDisconnected(msg) {
        if (state.roomPc?.connectionState !== "connected") roomUpdateStatus("DISCONNECTED");
        roomSetSpeaking(!state.roomIsHost, false);
        const activity = state.roomIsHost ? elements.roomGuestActivity : elements.roomHostActivity;
        activity.textContent = "Đang kết nối lại";
        showToast("Bạn kia vừa mất kết nối. Đang chờ kết nối lại…");
    }

    function roomOnPeerMicState(msg) {
        state.roomPeerMicEnabled = msg.enabled === true;
        // Cập nhật mic indicator của peer
        const isPeerHost = !state.roomIsHost;
        const indicator = isPeerHost
            ? elements.roomHostMicIndicator
            : elements.roomGuestMicIndicator;
        if (indicator) {
            indicator.classList.toggle("is-active", msg.enabled === true);
            indicator.classList.toggle("is-muted", msg.enabled === false);
        }
        const activity = isPeerHost ? elements.roomHostActivity : elements.roomGuestActivity;
        activity.textContent = msg.enabled === false ? "Đã tắt mic" : "Mic đang bật";
        if (msg.enabled === false) roomSetSpeaking(isPeerHost, false);
    }

    // ---- Approval (host only) ------------------------------------------

    async function roomApproveGuest() {
        if (!state.room) return;
        elements.roomApproveBtn.disabled = true;
        try {
            const approved = await api(`/api/v1/rooms/${encodeURIComponent(state.room.roomId)}/approve`, {method: "POST"});
            elements.roomApprovalPanel.hidden = true;
            if (approved.guest) {
                elements.roomGuestName.textContent = approved.guest.displayName;
                elements.roomGuestTag.textContent = "Khách";
                elements.roomGuestActivity.textContent = "Mic chưa bật";
            }
            roomUpdateStatus(approved.status);
        } catch (error) {
            showToast(error.message, true);
        } finally {
            elements.roomApproveBtn.disabled = false;
        }
    }

    async function roomRejectGuest() {
        if (!state.room) return;
        elements.roomRejectBtn.disabled = true;
        try {
            await api(`/api/v1/rooms/${encodeURIComponent(state.room.roomId)}/reject`, {method: "POST"});
            elements.roomApprovalPanel.hidden = true;
            roomUpdateStatus("WAITING_GUEST");
        } catch (error) {
            showToast(error.message, true);
        } finally {
            elements.roomRejectBtn.disabled = false;
        }
    }

    // ---- Polling (để host biết có pending request) ---------------------

    function roomStartPolling(roomId) {
        roomStopPolling();
        const request = {running: false, wake: false};
        state.roomPollingRequest = request;
        const poll = async () => {
            if (!state.room || state.room.roomId !== roomId || state.roomPollingRequest !== request) return;
            if (request.running) { request.wake = true; return; }
            request.running = true;
            try {
                const requestStartedAt = Date.now();
                const s = await api(`/api/v1/rooms/${encodeURIComponent(roomId)}`);
                if (!state.room || state.room.roomId !== roomId || state.roomPollingRequest !== request) return;
                const serverTime = Date.parse(s.serverNow);
                if (Number.isFinite(serverTime)) state.roomAudioClockOffset = serverTime - (requestStartedAt + Date.now()) / 2;
                // Nếu host: kiểm tra pending
                if (state.roomIsHost) {
                    elements.roomApprovalPanel.hidden = !s.pending;
                    if (s.pending) elements.roomApprovalText.textContent = `${s.pending.displayName} muốn tham gia phòng.`;
                    if (!s.guest) {
                        elements.roomGuestName.textContent = "Chờ bạn tham gia…";
                        elements.roomGuestTag.textContent = "Chưa có ai";
                        elements.roomGuestActivity.textContent = "Chưa kết nối";
                        state.roomPeerMicEnabled = null;
                        roomSetSpeaking(false, false);
                        if (state.roomPc) roomCleanupMedia();
                        elements.roomJoinCallBtn.hidden = true;
                    }
                }
                roomUpdateStatus(s.status);
                // Nếu guest: kiểm tra đã được approve chưa
                if (!state.roomIsHost && s.status === "ACTIVE" && !elements.roomPendingPanel.hidden) {
                    // Được approve → chuyển sang call panel
                    elements.roomPendingPanel.hidden = true;
                    await roomEnterCallPanel(roomId);
                    if (!state.roomWs || state.roomWs.readyState === WebSocket.CLOSED) roomConnectWebSocket(roomId);
                }
                // Phòng đã đóng
                if (s.status === "CLOSED") {
                    roomOnRoomClosed("Phòng đã đóng.");
                    return;
                } else if (s.lessonId) {
                    await roomSyncStudy(s);
                } else {
                    roomRenderStudy(s);
                }
                roomSyncCallClock(s);
            } catch (error) {
                if (state.roomPollingRequest !== request) return;
                if (error.status === 403 && !state.roomIsHost && !elements.roomPendingPanel.hidden) {
                    roomOnRoomClosed("Chủ phòng đã từ chối yêu cầu tham gia.");
                    return;
                }
                if (error.status === 404) {
                    roomOnRoomClosed("Phòng không còn tồn tại.");
                    return;
                }
            } finally {
                request.running = false;
                if (state.roomPollingRequest === request) {
                    const connected = state.roomWs?.readyState === WebSocket.OPEN;
                    state.roomPollingTimer = window.setTimeout(poll, request.wake ? 0 : connected ? 15000 : 2500);
                    request.wake = false;
                }
            }
        };
        request.poll = poll;
        state.roomPollingTimer = window.setTimeout(poll, 2500);
    }

    function roomWakePolling() {
        const request = state.roomPollingRequest;
        if (!request?.poll) return;
        window.clearTimeout(state.roomPollingTimer);
        if (request.running) request.wake = true;
        else state.roomPollingTimer = window.setTimeout(request.poll, 0);
    }

    function roomStopPolling() {
        if (state.roomPollingTimer) {
            window.clearTimeout(state.roomPollingTimer);
            state.roomPollingTimer = null;
        }
        state.roomPollingRequest = null;
    }

    async function roomCopyInviteLink() {
        if (!state.roomInviteUrl) {
            showToast("Không tìm thấy link mời cho phòng này.", true);
            return;
        }
        let copied = false;
        try {
            if (navigator.clipboard?.writeText && window.isSecureContext) {
                await navigator.clipboard.writeText(state.roomInviteUrl);
                copied = true;
            } else {
                copied = copyTextWithSelection(state.roomInviteUrl);
            }
        } catch (_) {
            try { copied = copyTextWithSelection(state.roomInviteUrl); } catch (_) { /* chọn thủ công */ }
        }
        if (copied) {
            showToast("Đã sao chép link mời vào clipboard.");
        } else {
            window.prompt("Sao chép link mời này:", state.roomInviteUrl);
        }
    }

    // ---- WebRTC --------------------------------------------------------

    async function roomJoinCall() {
        if (state.roomPc || state.roomJoinAttempt) return;
        if (!state.room || !state.roomWs || state.roomWs.readyState !== WebSocket.OPEN) {
            roomShowError("Chưa kết nối được phòng thoại.", "Đợi kết nối phòng rồi thử lại.");
            return;
        }
        elements.roomJoinCallBtn.hidden = true;
        elements.roomJoinCallBtn.disabled = true;
        roomClearError();

        const attempt = {};
        state.roomJoinAttempt = attempt;
        elements.sharedMicButton.disabled = true;
        try {
            // Lấy ICE config
            let iceServers = [{urls: ["stun:stun.l.google.com:19302"]}];
            try {
                const config = await api(`/api/v1/rooms/${encodeURIComponent(state.room.roomId)}/ice-config`);
                if (config && config.iceServers) {
                    iceServers = config.iceServers.map(s => {
                        const entry = {urls: s.urls};
                        if (s.username) entry.username = s.username;
                        if (s.credential) entry.credential = s.credential;
                        return entry;
                    });
                }
            } catch (_) { /* fallback sang STUN mặc định */ }

            if (state.roomJoinAttempt !== attempt) return;

            // Xin quyền mic
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                const isHttp = location.protocol !== "https:" && location.hostname !== "localhost" && location.hostname !== "127.0.0.1";
                const hint = isHttp
                    ? "Trình duyệt chặn mic qua HTTP. Hãy bật flag chrome://flags/#unsafely-treat-insecure-origin-as-secure cho origin này hoặc sử dụng HTTPS."
                    : "Trình duyệt không hỗ trợ truy cập microphone.";
                roomShowError("Không thể truy cập Microphone.", hint);
                elements.roomJoinCallBtn.hidden = false;
                elements.roomJoinCallBtn.disabled = false;
                return;
            }

            try {
                const stream = await navigator.mediaDevices.getUserMedia({audio: true, video: false});
                if (state.roomJoinAttempt !== attempt) {
                    stream.getTracks().forEach(track => track.stop());
                    return;
                }
                state.roomLocalStream = stream;
                if (window.AudioContext || window.webkitAudioContext) roomStartSpeechMonitor("local", stream);
                stream.getAudioTracks().forEach(track => track.addEventListener("ended", () => {
                    if (stream !== state.roomLocalStream) return;
                    state.roomMicEnabled = false;
                    roomUpdateMicButton();
                    roomSetSpeaking(state.roomIsHost, false);
                    roomShowError("Microphone đã ngắt kết nối.", "Kiểm tra thiết bị rồi bấm Kết nối lại.");
                    elements.roomReconnectBtn.hidden = false;
                    elements.sharedReconnect.hidden = false;
                }));
            } catch (err) {
                if (state.roomJoinAttempt !== attempt) return;
                let hint = "";
                if (err.name === "NotAllowedError" || err.name === "PermissionDeniedError") {
                    hint = "Trình duyệt đã chặn quyền mic. Vào Settings → Site Settings → Microphone để cấp lại.";
                } else if (err.name === "NotFoundError") {
                    hint = "Không tìm thấy mic. Hãy kiểm tra và cắm lại thiết bị âm thanh.";
                } else if (err.name === "NotReadableError") {
                    hint = "Mic đang được ứng dụng khác sử dụng. Hãy đóng tab/app đó và thử lại.";
                } else {
                    hint = `Lỗi: ${err.message}`;
                }
                roomShowError("Không truy cập được mic.", hint);
                elements.roomJoinCallBtn.hidden = false;
                elements.roomJoinCallBtn.disabled = false;
                return;
            }

            // Bật mic button
            elements.roomMicBtn.disabled = false;
            state.roomMicEnabled = true;
            roomUpdateMicButton();
            const myIndicator = state.roomIsHost ? elements.roomHostMicIndicator : elements.roomGuestMicIndicator;
            myIndicator.classList.add("is-active");
            myIndicator.classList.remove("is-muted");
            roomSetSpeaking(state.roomIsHost, false);

            // Tạo PeerConnection
            const pc = new RTCPeerConnection({iceServers});
            state.roomPc = pc;

            // Thêm local audio track
            state.roomLocalStream.getTracks().forEach(track => pc.addTrack(track, state.roomLocalStream));

            // Only attach a populated stream: attaching an empty stream can leave playback at HAVE_NOTHING.
            pc.ontrack = event => {
                if (pc !== state.roomPc) return;
                const audio = elements.roomRemoteAudio;
                const stream = event.streams[0] || new MediaStream([event.track]);
                if (audio.srcObject !== stream) audio.srcObject = stream;
                if (window.AudioContext || window.webkitAudioContext) roomStartSpeechMonitor("remote", stream);
                audio.onplaying = () => roomCheckAudio(pc);
                audio.onpause = () => {
                    if (pc !== state.roomPc) return;
                    elements.roomAutoplayNotice.hidden = false;
                    elements.sharedEnableAudio.hidden = false;
                    roomUpdateStatus("CONNECTING_AUDIO");
                };
                event.track.addEventListener("unmute", () => {
                    if (pc === state.roomPc) roomEnableAudio();
                });
                roomEnableAudio();
            };

            // ICE candidates
            pc.onicecandidate = event => {
                if (event.candidate) {
                    roomSendWs({type: "ice-candidate", candidate: event.candidate.toJSON()});
                }
            };

            pc.onconnectionstatechange = () => {
                if (pc !== state.roomPc) return;
                if (pc.connectionState === "connected") {
                    roomUpdateStatus("CONNECTING_AUDIO");
                    roomCheckAudio(pc);
                    roomClearError();
                    elements.roomReconnectBtn.hidden = true;
                    elements.sharedReconnect.hidden = true;
                } else if (pc.connectionState === "failed") {
                    roomUpdateStatus("ERROR");
                    roomShowError("Kết nối thất bại.", "Bấm 'Kết nối lại' để thử lại. Nếu gọi qua hai mạng khác nhau, cần TURN server.");
                    elements.roomReconnectBtn.hidden = false;
                    elements.sharedReconnect.hidden = false;
                } else if (pc.connectionState === "disconnected") {
                    roomUpdateStatus("DISCONNECTED");
                }
            };

            // Host là offerer, guest là answerer
            roomSendWs({type: "ready"});
            roomSendMicState();
            if (state.roomIsHost && state.roomPeerReady) roomOnPeerReady();
        } catch (error) {
            if (state.roomJoinAttempt === attempt) {
                roomCleanupMedia();
                roomShowError("Không tạo được cuộc gọi.", error.message);
            }
        } finally {
            if (state.roomJoinAttempt === attempt) {
                state.roomJoinAttempt = null;
                elements.sharedMicButton.disabled = false;
                elements.roomJoinCallBtn.disabled = false;
                elements.roomJoinCallBtn.hidden = Boolean(state.roomPc);
            }
        }
    }

    async function roomCheckAudio(pc) {
        if (pc !== state.roomPc || pc.connectionState !== "connected") return;
        window.clearTimeout(state.roomAudioCheckTimer);
        let stats;
        try { stats = await pc.getStats(); } catch (_) { return; }
        if (pc !== state.roomPc || pc.connectionState !== "connected") return;
        const incoming = [...stats.values()].some(report => report.type === "inbound-rtp"
            && (report.kind === "audio" || report.mediaType === "audio") && report.packetsReceived > 0);
        const audio = elements.roomRemoteAudio;
        if (incoming && audio.readyState >= 2 && !audio.paused && !audio.muted && audio.volume > 0) {
            roomUpdateStatus("CONNECTED");
            roomConfirmCallReady();
        } else {
            roomUpdateStatus("CONNECTING_AUDIO");
            state.roomAudioCheckTimer = window.setTimeout(() => roomCheckAudio(pc), 1500);
        }
    }

    async function roomHandleOffer(msg) {
        if (!state.roomPc) return;
        try {
            await state.roomPc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
            await roomFlushPendingIce();
            const answer = await state.roomPc.createAnswer();
            await state.roomPc.setLocalDescription(answer);
            roomSendWs({type: "answer", sdp: state.roomPc.localDescription});
        } catch (err) {
            roomShowError("Lỗi khi thiết lập kết nối (answer).", err.message);
        }
    }

    async function roomHandleAnswer(msg) {
        if (!state.roomPc) return;
        try {
            await state.roomPc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
            await roomFlushPendingIce();
        } catch (err) {
            roomShowError("Lỗi khi thiết lập kết nối (answer).", err.message);
        }
    }

    async function roomHandleIceCandidate(msg) {
        if (!msg.candidate) return;
        if (state.roomPc && state.roomPc.remoteDescription) {
            try {
                await state.roomPc.addIceCandidate(new RTCIceCandidate(msg.candidate));
            } catch (_) { /* bỏ ICE candidate lỗi */ }
        } else {
            state.roomPendingIce.push(msg.candidate);
        }
    }

    async function roomFlushPendingIce() {
        const candidates = state.roomPendingIce.splice(0);
        for (const c of candidates) {
            try {
                await state.roomPc.addIceCandidate(new RTCIceCandidate(c));
            } catch (_) {}
        }
    }

    // ---- Mic controls --------------------------------------------------

    function roomToggleMic() {
        if (!state.roomLocalStream) return;
        state.roomMicEnabled = !state.roomMicEnabled;
        state.roomLocalStream.getAudioTracks().forEach(track => {
            track.enabled = state.roomMicEnabled;
        });
        roomUpdateMicButton();
        roomSendMicState();
        // Cập nhật mic indicator của mình
        const myIndicator = state.roomIsHost
            ? elements.roomHostMicIndicator
            : elements.roomGuestMicIndicator;
        if (myIndicator) {
            myIndicator.classList.toggle("is-active", state.roomMicEnabled);
            myIndicator.classList.toggle("is-muted", !state.roomMicEnabled);
        }
        const activity = state.roomIsHost ? elements.roomHostActivity : elements.roomGuestActivity;
        activity.textContent = state.roomMicEnabled ? "Mic đang bật" : "Đã tắt mic";
        if (!state.roomMicEnabled) roomSetSpeaking(state.roomIsHost, false);
    }

    function roomSendMicState() {
        if (state.roomLocalStream) roomSendWs({type: "mic-state", enabled: state.roomMicEnabled});
    }

    function roomUpdateMicButton() {
        const on = state.roomMicEnabled;
        elements.roomMicBtn.setAttribute("aria-pressed", String(on));
        elements.roomMicLabel.textContent = on ? "Tắt mic" : "Bật mic";
        elements.sharedMicButton.setAttribute("aria-pressed", String(on));
        elements.sharedMicButton.textContent = state.roomLocalStream ? (on ? "Tắt mic" : "Bật mic") : "Tham gia cuộc gọi";
    }

    // ---- Voice activity + call timer ---------------------------------

    function roomStartSpeechMonitor(kind, stream) {
        roomStopSpeechMonitor(kind);
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass || !stream?.getAudioTracks().length) return;
        try {
            const context = state.roomSpeechContext || new AudioContextClass();
            state.roomSpeechContext = context;
            if (context.state === "suspended") context.resume().catch(() => {});
            const source = context.createMediaStreamSource(stream);
            const analyser = context.createAnalyser();
            analyser.fftSize = 512;
            analyser.smoothingTimeConstant = 0.55;
            source.connect(analyser);
            state.roomSpeechMonitors[kind] = {source, analyser, data: new Uint8Array(analyser.fftSize), loud: 0, quiet: 0, speaking: false};
            if (!state.roomSpeechTimer) state.roomSpeechTimer = window.setInterval(roomMeasureSpeech, 80);
        } catch (_) { /* chỉ báo hoạt động giọng nói; cuộc gọi vẫn tiếp tục nếu browser không hỗ trợ */ }
    }

    function roomMeasureSpeech() {
        for (const [kind, monitor] of Object.entries(state.roomSpeechMonitors)) {
            monitor.analyser.getByteTimeDomainData(monitor.data);
            let energy = 0;
            for (const sample of monitor.data) {
                const value = (sample - 128) / 128;
                energy += value * value;
            }
            const active = Math.sqrt(energy / monitor.data.length) > 0.055;
            monitor.loud = active ? monitor.loud + 1 : 0;
            monitor.quiet = active ? 0 : monitor.quiet + 1;
            const enabled = kind === "local" ? state.roomMicEnabled : state.roomPeerMicEnabled !== false;
            const next = enabled && (monitor.speaking ? monitor.quiet < 6 : monitor.loud >= 2);
            if (next !== monitor.speaking) {
                monitor.speaking = next;
                roomSetSpeaking(kind === "local" ? state.roomIsHost : !state.roomIsHost, next);
            }
        }
    }

    function roomSetSpeaking(isHost, speaking) {
        const card = isHost ? elements.roomHostCard : elements.roomGuestCard;
        const activity = isHost ? elements.roomHostActivity : elements.roomGuestActivity;
        const compactAvatar = isHost ? elements.sharedHostAvatar : elements.sharedGuestAvatar;
        card?.classList.toggle("is-speaking", speaking);
        compactAvatar?.classList.toggle("is-speaking", speaking);
        if (speaking) {
            activity.textContent = "Đang nói";
        } else {
            const local = isHost === state.roomIsHost;
            const micEnabled = local ? state.roomMicEnabled : state.roomPeerMicEnabled;
            activity.textContent = micEnabled === false ? "Đã tắt mic" : (micEnabled ? "Mic đang bật" : "Mic chưa bật");
        }
    }

    function roomStopSpeechMonitor(kind) {
        const monitor = state.roomSpeechMonitors[kind];
        if (!monitor) return;
        monitor.source.disconnect();
        monitor.analyser.disconnect();
        delete state.roomSpeechMonitors[kind];
        roomSetSpeaking(kind === "local" ? state.roomIsHost : !state.roomIsHost, false);
    }

    function roomConfirmCallReady() {
        if (state.roomCallReadySent) return;
        state.roomCallReadySent = roomSendWs({type: "call-ready"});
    }

    function roomSyncCallClock(roomState) {
        if (roomState?.callStartedAt) roomStartCallClock(roomState.callStartedAt, roomState.serverNow);
        else if (roomState && Object.prototype.hasOwnProperty.call(roomState, "callStartedAt")) roomStopCallClock();
    }

    function roomStartCallClock(startedAt, serverNow) {
        const start = Date.parse(startedAt);
        if (!Number.isFinite(start)) return;
        state.roomCallStartedAt = start;
        const server = Date.parse(serverNow);
        if (Number.isFinite(server)) state.roomServerTimeOffset = server - Date.now();
        roomRenderCallClock();
        if (!state.roomCallTimer) state.roomCallTimer = window.setInterval(roomRenderCallClock, 1000);
    }

    function roomRenderCallClock() {
        if (!state.roomCallStartedAt) return;
        const seconds = Math.max(0, Math.floor((Date.now() + state.roomServerTimeOffset - state.roomCallStartedAt) / 1000));
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        const rest = seconds % 60;
        const text = hours > 0
            ? `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`
            : `${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`;
        for (const timer of [elements.roomCallTimer, elements.sharedCallTimer]) {
            timer.textContent = text;
            timer.dateTime = `PT${seconds}S`;
        }
    }

    function roomStopCallClock() {
        window.clearInterval(state.roomCallTimer);
        state.roomCallTimer = null;
        state.roomCallStartedAt = null;
        state.roomServerTimeOffset = 0;
        for (const timer of [elements.roomCallTimer, elements.sharedCallTimer]) {
            timer.textContent = "00:00";
            timer.dateTime = "PT0S";
        }
    }

    function roomStopAllSpeechMonitors() {
        for (const kind of Object.keys(state.roomSpeechMonitors)) roomStopSpeechMonitor(kind);
        window.clearInterval(state.roomSpeechTimer);
        state.roomSpeechTimer = null;
        if (state.roomSpeechContext) state.roomSpeechContext.close().catch(() => {});
        state.roomSpeechContext = null;
    }

    // ---- Reconnect -----------------------------------------------------

    function roomReconnect() {
        elements.roomReconnectBtn.hidden = true;
        elements.sharedReconnect.hidden = true;
        if (state.room) {
            roomCleanupMedia();
            state.roomPendingIce = [];
            roomConnectWebSocket(state.room.roomId);
            state.roomWs.addEventListener("open", () => roomJoinCall(), {once: true});
        }
    }

    // ---- Leave / Cleanup -----------------------------------------------

    async function roomLeave() {
        const roomId = state.room?.roomId;
        const sharedSessionId = state.roomSharedSessionId;
        if (roomId) {
            try {
                await api(`/api/v1/rooms/${encodeURIComponent(roomId)}`, {method: "DELETE"});
            } catch (error) {
                if (error.status !== 403 && error.status !== 404) {
                    showToast(`Chưa rời phòng: ${error.message}`, true);
                    return false;
                }
            }
        }
        roomCleanupMedia();
        roomStopPolling();
        if (state.roomWs) {
            state.roomWs.close(1000, "leave");
            state.roomWs = null;
        }
        if (state.roomWsReconnectTimer) {
            window.clearTimeout(state.roomWsReconnectTimer);
            state.roomWsReconnectTimer = null;
        }
        state.room = null;
        state.roomIsHost = false;
        state.roomInviteUrl = null;
        state.roomPeerReady = false;
        state.roomStatus = null;
        state.roomMediaStatus = null;
        state.roomPeerMicEnabled = null;
        state.roomStudy = null;
        state.roomSharedSessionId = null;
        state.roomPlayback = null;
        state.roomPlaybackSeen = 0;
        state.roomAudioClockOffset = null;
        state.roomAudioPreparedKey = null;
        state.roomAudioPreparingKey = null;
        stopRecordedAudio();
        state.roomSharedCompleted = false;
        if (sharedSessionId && state.session?.sessionId === sharedSessionId) {
            state.session = null;
            state.sentence = null;
        }
        state.roomDraftSequence = 0;
        state.roomPeerDraftSequence = 0;
        state.roomReady = false;
        window.clearTimeout(state.roomDraftTimer);
        state.roomMicEnabled = false;
        state.roomWsReconnectCount = 0;
        state.roomPendingIce = [];
        window.clearInterval(state.roomWsHeartbeatTimer);
        state.roomWsHeartbeatTimer = null;
        roomStopCallClock();
        // Chuyển URL về /rooms
        router.replace("room", {});
        _showView("room");
        roomInitLobby();
        elements.sharedPracticeBar.hidden = true;
        elements.peerPracticePanel.hidden = true;
        return true;
    }

    function roomCleanupMedia() {
        state.roomJoinAttempt = null;
        window.clearTimeout(state.roomAudioCheckTimer);
        state.roomAudioCheckTimer = null;
        state.roomCallReadySent = false;
        roomStopAllSpeechMonitors();
        if (state.roomPc) {
            state.roomPc.ontrack = null;
            state.roomPc.onicecandidate = null;
            state.roomPc.onconnectionstatechange = null;
            state.roomPc.close();
            state.roomPc = null;
        }
        if (state.roomLocalStream) {
            const localStream = state.roomLocalStream;
            state.roomLocalStream = null;
            localStream.getTracks().forEach(t => t.stop());
        }
        if (elements.roomRemoteAudio) {
            elements.roomRemoteAudio.onplaying = null;
            elements.roomRemoteAudio.onpause = null;
            elements.roomRemoteAudio.srcObject = null;
            elements.roomRemoteAudio.pause();
        }
        elements.roomMicBtn.disabled = true;
        elements.roomMicBtn.setAttribute("aria-pressed", "false");
        state.roomMicEnabled = false;
        state.roomMediaStatus = null;
        for (const indicator of [elements.roomHostMicIndicator, elements.roomGuestMicIndicator]) {
            indicator.classList.remove("is-active", "is-muted");
        }
        elements.roomHostActivity.textContent = "Mic chưa bật";
        elements.roomGuestActivity.textContent = "Mic chưa bật";
        roomUpdateMicButton();
        elements.sharedMicButton.disabled = false;
        elements.roomJoinCallBtn.disabled = false;
        elements.roomJoinCallBtn.hidden = false;
        elements.roomAutoplayNotice.hidden = true;
        elements.sharedEnableAudio.hidden = true;
        elements.sharedReconnect.hidden = true;
    }

    function roomOnRoomClosed(reason) {
        const sharedSessionId = state.roomSharedSessionId;
        roomStopPolling();
        showToast(reason, true);
        roomCleanupMedia();
        if (state.roomWs) {
            state.roomWs.close(1000);
            state.roomWs = null;
        }
        state.room = null;
        state.roomIsHost = false;
        state.roomStatus = null;
        state.roomMediaStatus = null;
        state.roomPeerMicEnabled = null;
        state.roomStudy = null;
        state.roomSharedSessionId = null;
        state.roomPlayback = null;
        state.roomPlaybackSeen = 0;
        state.roomAudioClockOffset = null;
        state.roomAudioPreparedKey = null;
        state.roomAudioPreparingKey = null;
        stopRecordedAudio();
        state.roomSharedCompleted = false;
        if (sharedSessionId && state.session?.sessionId === sharedSessionId) {
            state.session = null;
            state.sentence = null;
        }
        state.roomDraftSequence = 0;
        state.roomPeerDraftSequence = 0;
        state.roomReady = false;
        window.clearTimeout(state.roomDraftTimer);
        window.clearInterval(state.roomWsHeartbeatTimer);
        state.roomWsHeartbeatTimer = null;
        roomStopCallClock();
        // Chuyển URL về /rooms
        router.replace("room", {});
        _showView("room");
        roomInitLobby();
        elements.sharedPracticeBar.hidden = true;
        elements.peerPracticePanel.hidden = true;
    }

    // ---- Offer/Ready (host creates offer when guest is ready) ----------

    // Host tạo offer khi nhận signal "ready" từ guest
    async function roomOnPeerReady() {
        if (!state.roomIsHost) return;
        state.roomPeerReady = true;
        if (!state.roomPc || state.roomMakingOffer || state.roomPc.signalingState !== "stable") return;
        state.roomMakingOffer = true;
        try {
            const offer = await state.roomPc.createOffer();
            await state.roomPc.setLocalDescription(offer);
            roomSendWs({type: "offer", sdp: state.roomPc.localDescription});
            state.roomPeerReady = false;
        } catch (err) {
            roomShowError("Lỗi khi tạo offer.", err.message);
        } finally {
            state.roomMakingOffer = false;
        }
    }

    // ==================================================================== END ROOM MODULE
    window.DictaLabListeningBridge = {api, navigate, playRecordedAudio, loadAudioBuffer, stopRecordedAudio, paginateList,
        showToast, isAdmin: () => state.currentUser?.role === "ADMIN",
        currentUser: () => state.currentUser, refreshUser: async () => {
            state.currentUser = await api("/api/auth/me");
            elements.accountName.textContent = state.currentUser.displayName;
            elements.accountEmail.textContent = state.currentUser.email;
            return state.currentUser;
        }, requireLogin: () => showAuthScreen("Vui lòng đăng nhập lại.")};
})();
