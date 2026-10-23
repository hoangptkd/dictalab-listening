(function (root) {
    "use strict";
    const copy = value => structuredClone(value);
    const javaTrim = value => String(value).replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, "");

    function normalize(answer) {
        const value = javaTrim(answer).normalize("NFKC").replaceAll("’", "'");
        return /^[\x20-\x7e]*$/.test(value) ? value.toLowerCase() : null;
    }

    // One record commits the snapshot, draft, ordered events and immutable in-flight batch together.
    function database() {
        let db;
        async function open() {
            if (db) return db;
            db = await new Promise((resolve, reject) => {
                const request = root.indexedDB.open("dictalab-practice", 1);
                request.onupgradeneeded = () => request.result.createObjectStore("sessions", {keyPath: "key"});
                request.onsuccess = () => resolve(request.result);
                request.onerror = () => reject(request.error);
                request.onblocked = () => reject(new Error("Không thể mở bộ nhớ luyện tập. Hãy đóng tab cũ rồi thử lại."));
            });
            db.onversionchange = () => {db.close(); db = null;};
            return db;
        }
        async function transaction(mode, run) {
            const connection = await open();
            return new Promise((resolve, reject) => {
                const tx = connection.transaction("sessions", mode);
                let result;
                const request = run(tx.objectStore("sessions"));
                if (request) request.onsuccess = () => {result = request.result;};
                tx.oncomplete = () => resolve(result);
                tx.onabort = tx.onerror = () => reject(tx.error || new Error("Không thể lưu dữ liệu trên thiết bị."));
            });
        }
        return {get: key => transaction("readonly", store => store.get(key)),
            put: record => transaction("readwrite", store => store.put(record)),
            remove: key => transaction("readwrite", store => store.delete(key)),
            all: () => transaction("readonly", store => store.getAll()),
            close() {db?.close(); db = null;}};
    }

    class PracticeSync {
        static supported() {return Boolean(root.indexedDB && root.navigator?.locks && root.crypto?.randomUUID);}
        constructor({owner, sessionId, send, onChange = () => {}, onStatus = () => {}, onAuth = () => {},
            store = database(), locks = root.navigator?.locks, crypto = root.crypto,
            schedule = root.setTimeout.bind(root), cancel = root.clearTimeout.bind(root), random = Math.random}) {
            Object.assign(this, {owner, sessionId, send, onChange, onStatus, onAuth, store, locks, crypto, schedule, cancel, random});
            this.key = `${owner}:${sessionId}`;
            this.writes = Promise.resolve();
            this.phase = "opening";
            this.closed = false;
            this.retryCount = 0;
        }
        get session() {return {...this.record.session, sentence: this.view()};}
        get draft() {return this.record?.draft || {drafts: [], elapsedSeconds: 0};}
        get completion() {return this.record?.completion;}
        get pending() {return this.record?.events.length || 0;}
        get blocked() {return this.closed || ["auth", "conflict", "storage"].includes(this.phase);}
        get barrier() {return Boolean(this.completion || this.record?.events.some(item => item.event.hint || item.event.complete));}
        view() {
            if (!this.record?.snapshot) return null;
            const sentence = copy(this.record.snapshot);
            for (const item of this.record.events) {
                const event = item.event;
                if (event.check) {
                    const word = sentence.words[event.wordIndex];
                    if (!word || word.correct || word.revealed) continue;
                    word.userInput = javaTrim(event.check.answer);
                    if (item.correct != null) {
                        word.correct = item.correct;
                        word.classificationAvailable ||= !item.correct;
                    } else word.pendingGrade = true;
                } else if (event.replay?.event === "STARTED" && !sentence.canComplete) {
                    sentence.replayCount++;
                    sentence.hasListened = true;
                } else if (event.replay?.event === "COMPLETED" && sentence.requiresReplayAfterReveal) {
                    sentence.requiresReplayAfterReveal = false;
                } else if (event.classification) {
                    sentence.words[event.classification.wordIndex].errorType = event.classification.errorType;
                }
                sentence.canComplete = sentence.words.every(word => word.correct || word.revealed)
                    && !sentence.requiresReplayAfterReveal;
            }
            return sentence;
        }
        status(phase, error) {
            this.phase = phase;
            this.onStatus({phase, pending: this.pending, barrier: this.barrier, error});
        }
        mutate(change, notify = true) {
            const operation = this.writes.then(async () => {
                const next = copy(this.record);
                const result = await change(next);
                try {await this.store.put(next);}
                catch (error) {this.status("storage", error); throw error;}
                this.record = next;
                if (notify && !this.closed) this.onChange(this.view());
                return result;
            });
            this.writes = operation.catch(() => {});
            return operation;
        }
        async open(session = null) {
            await new Promise((resolve, reject) => {
                this.lockTask = this.locks.request(`dictalab-practice:${this.key}`, {ifAvailable: true}, async lock => {
                    if (!lock) {reject(new Error("Phiên này đang mở trong tab khác. Hãy đóng tab đó rồi thử lại.")); return;}
                    await new Promise(release => {this.release = release; resolve();});
                }).catch(reject);
            });
            try {
                this.record = await this.store.get(this.key);
                if (!this.record && !session) {await this.close(); return null;}
                if (!this.record) {
                    this.record = {key: this.key, owner: this.owner, sessionId: this.sessionId, session: copy(session),
                        revision: 0, snapshot: session.sentence, grading: null, events: [], batch: null,
                        draft: {drafts: [], elapsedSeconds: 0}, completion: null};
                    // Check durable storage before enabling the protocol on the server.
                    await this.store.put(this.record);
                }
                if (this.record.owner !== this.owner || this.record.sessionId !== this.sessionId)
                    throw new Error("Dữ liệu cục bộ không khớp tài khoản hoặc phiên luyện.");
                if (this.pending) {
                    try {await this.flush();}
                    catch (error) {
                        if (this.phase === "auth" || this.phase === "storage") throw error;
                        return this.session;
                    }
                }
                if (!this.completion) await this.refresh();
                return this.session;
            } catch (error) {
                if (this.phase === "conflict" && this.record?.grading) return this.session;
                if (error.status === 404 && !this.record?.grading && !this.pending) await this.store.remove(this.key);
                await this.close(); throw error;
            }
        }
        async refresh(requireFresh = false) {
            try {
                const state = await this.send(`/api/v1/practice-sessions/${encodeURIComponent(this.sessionId)}/sync/open`,
                    {method: "POST", timeoutMs: 15000, redirectOnUnauthorized: false});
                if (this.pending) throw new Error("Cần đồng bộ lượt thử cũ trước khi tải trạng thái mới.");
                await this.mutate(next => {
                    const changed = next.grading && next.grading.contentVersion !== state.grading.contentVersion;
                    if (changed && next.snapshot?.sentenceId === state.sentence.sentenceId && next.draft.drafts.length)
                        throw Object.assign(new Error("Nội dung đã thay đổi. Bản nháp cũ được giữ để kiểm tra."), {status: 409});
                    if (changed || next.snapshot?.sentenceId !== state.sentence.sentenceId)
                        next.draft = {drafts: [], elapsedSeconds: 0};
                    Object.assign(next, {revision: state.revision, snapshot: state.sentence,
                        grading: state.grading, completion: null});
                });
                this.status("saved");
            } catch (error) {
                this.handleFailure(error);
                if (requireFresh || this.blocked || !this.record.grading) throw error;
            }
        }
        async hash(index, answer) {
            const manifest = this.record.grading;
            const normalized = normalize(answer);
            if (manifest?.normalization !== "ascii-nfkc-v1" || !manifest.hashes[index]
                || normalized == null || !this.crypto.subtle) return null;
            const bytes = new TextEncoder().encode(`${manifest.salt}\n${index}\n${normalized}`);
            const digest = await this.crypto.subtle.digest("SHA-256", bytes);
            return [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, "0")).join("") === manifest.hashes[index];
        }
        async check(index, answer, isCurrent = () => true, responseTimeMs = 0) {
            if (this.blocked || this.barrier) throw new Error("Hãy chờ thao tác trước được đồng bộ hoặc đăng nhập lại.");
            if (!javaTrim(answer) || answer.length > 100) return null;
            const verdict = this.hash(index, answer).catch(() => null);
            const result = await this.mutate(async next => {
                const correct = await verdict;
                if (!isCurrent()) throw new DOMException("Answer changed", "AbortError");
                const word = this.view().words[index];
                if (!word || word.correct || word.revealed || !this.view().hasListened) return null;
                const sequence = next.revision + next.events.length + 1;
                next.events.push({event: {sequence, wordIndex: index, check: {answer,
                    responseTimeMs: Math.min(300000, Math.max(0, responseTimeMs))}}, correct});
                next.draft.drafts = next.draft.drafts.filter(([i, value]) => i !== index || value !== answer);
                return {correct, sequence};
            });
            if (!result) return null;
            this.status("pending");
            this.kick(result.correct == null);
            if (result.correct == null) {
                await this.flush();
                result.correct = this.view().words[index].correct;
            }
            return result;
        }
        saveDraft(draft) {
            if (!this.record || this.closed || this.phase === "storage" || this.completion) return Promise.resolve();
            return this.mutate(next => {
                next.draft = copy(draft);
                const words = this.view()?.words;
                if (words) next.draft.drafts = next.draft.drafts.filter(([index]) => words[index] && !words[index].correct && !words[index].revealed);
            }, false);
        }
        async command(command) {
            if (this.blocked) throw new Error("Chưa thể đồng bộ. Dữ liệu trên thiết bị vẫn được giữ.");
            if (command.complete && this.completion) return this.completion;
            const sequence = await this.mutate(next => {
                const last = next.events.at(-1);
                const existing = !command.replay && last
                    && JSON.stringify({...last.event, sequence: undefined}) === JSON.stringify(command) ? last : null;
                if (existing) return existing.event.sequence;
                if (this.barrier) throw Object.assign(new Error("Hãy chờ gợi ý hoặc hoàn thành câu được xác nhận."), {status: 409});
                const sequence = next.revision + next.events.length + 1;
                next.events.push({event: {sequence, ...command}});
                return sequence;
            });
            this.status("pending");
            if (command.replay) {this.kick(true); return this.view();}
            while (this.record.revision < sequence) await this.flush();
            return command.complete ? this.completion : this.view();
        }
        kick(immediate = false) {
            if (this.blocked || !this.pending) return;
            if (this.retryCount && this.timer) return;
            if (immediate || this.pending >= 8) {
                this.cancel(this.timer); this.timer = null;
                this.flush().catch(() => {});
            } else if (!this.timer) {
                this.timer = this.schedule(() => {this.timer = null; this.flush().catch(() => {});}, 2000);
            }
        }
        flush() {
            if (this.flight) return this.flight;
            if (this.blocked) return Promise.reject(new Error("Đồng bộ đang tạm dừng; dữ liệu chưa gửi được giữ trên thiết bị."));
            this.cancel(this.timer); this.timer = null;
            this.flight = this.drain().finally(() => {
                this.flight = null;
                if (this.pending && !this.blocked && this.phase !== "offline") this.kick(true);
            });
            return this.flight;
        }
        async drain() {
            await this.writes;
            try {
                while (this.pending && !this.closed) {
                    if (!this.record.batch) await this.mutate(next => {
                        const first = next.events[0].event;
                        const events = first.check ? next.events.slice(0, 32).map(item => item.event) : [first];
                        const barrier = events.findIndex(event => !event.check);
                        const selected = barrier > 0 ? events.slice(0, barrier) : events;
                        const body = {sentenceId: next.snapshot.sentenceId, baseRevision: next.revision,
                            contentVersion: next.grading.contentVersion, events: selected};
                        if (new TextEncoder().encode(JSON.stringify(body)).length > 65536)
                            throw new Error("Lô đồng bộ vượt giới hạn lưu trữ.");
                        next.batch = {key: this.crypto.randomUUID(), body};
                    });
                    const batch = copy(this.record.batch);
                    this.status("syncing");
                    const result = await this.send(`/api/v1/practice-sessions/${encodeURIComponent(this.sessionId)}/sync/batches`, {
                        method: "POST", timeoutMs: 15000, redirectOnUnauthorized: false,
                        headers: {"Idempotency-Key": batch.key, "X-Practice-Events": String(batch.body.events.length)}, body: batch.body});
                    if (result.revision !== batch.body.baseRevision + batch.body.events.length)
                        throw Object.assign(new Error("Biên nhận đồng bộ không khớp. Dữ liệu cục bộ được giữ."), {status: 409});
                    await this.mutate(next => {
                        next.events = next.events.filter(item => item.event.sequence > result.revision);
                        next.revision = result.revision;
                        next.batch = null;
                        next.completion = result.completion;
                        if (result.sentence) {next.snapshot = result.sentence; next.grading = result.grading;}
                        if (result.completion) next.draft = {drafts: [], elapsedSeconds: 0};
                    });
                    this.retryCount = 0;
                }
                if (!this.closed) this.status(this.pending ? "pending" : "saved");
            } catch (error) {this.handleFailure(error); throw error;}
        }
        handleFailure(error) {
            if (this.phase === "storage" || this.closed) return;
            if (error.status === 401) {this.status("auth", error); this.onAuth();}
            else if (error.status && error.status < 500 && error.status !== 429) this.status("conflict", error);
            else {
                this.status("offline", error);
                if (this.pending && !this.timer) {
                    const delay = Math.max(error.retryAfterMs || 0,
                        Math.min(30000, 1000 * 2 ** Math.min(this.retryCount++, 5)) * (0.8 + this.random() * 0.4));
                    this.timer = this.schedule(() => {this.timer = null; this.flush().catch(() => {});}, delay);
                }
            }
        }
        async nextSentence() {
            if (this.pending) throw new Error("Còn thao tác chưa được đồng bộ.");
            await this.refresh(true);
            return this.view();
        }
        async close(discard = false) {
            if (this.closing) return this.closing;
            this.closed = true;
            this.cancel(this.timer);
            this.closing = (async () => {
                try {
                    await this.writes;
                    await this.flight?.catch(() => {});
                    if (discard) await this.store.remove(this.key);
                } finally {this.store.close(); this.release?.(); await this.lockTask;}
            })();
            return this.closing;
        }
    }
    PracticeSync.normalize = normalize;
    PracticeSync.database = database;
    root.DictaLabPracticeSync = PracticeSync;
    if (typeof module !== "undefined") module.exports = PracticeSync;
})(typeof globalThis === "undefined" ? window : globalThis);
