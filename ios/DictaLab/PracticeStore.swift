import Foundation
import Combine
import CryptoKit

@MainActor
final class PracticeStore: ObservableObject {
    enum PlaybackPhase: Equatable { case idle, preparing, playing, confirming }
    private struct Pending: Codable { let key: String; let body: Data }
    @Published var session: PracticeSession
    @Published var answers: [Int: String] = [:]
    @Published var busy = false
    @Published private(set) var playbackPhase = PlaybackPhase.idle
    var playing: Bool { playbackPhase == .playing }
    var audioActive: Bool { playbackPhase != .idle }
    @Published var error: String?
    @Published var result: LessonResult?
    @Published var speed = 1.0
    private let api: APIClient
    private let audio: any AudioPlaying
    private var playbackTask: Task<Void, Never>?
    private var sentenceStarted = Date()
    private var wordStarted = Date()
    private let defaults: UserDefaults

    init(session: PracticeSession, api: APIClient, defaults: UserDefaults = .standard,
         audio: (any AudioPlaying)? = nil) {
        self.session = session
        self.api = api
        self.defaults = defaults
        self.audio = audio ?? AudioPlayback()
        loadDraft()
    }

    private var draftKey: String {
        "dictalab.draft.\(api.account?.id ?? 0).\(session.sessionId).\(session.sentence?.sentenceId ?? 0)"
    }

    func saveDraft() {
        if let data = try? JSONEncoder().encode(answers) { defaults.set(data, forKey: draftKey) }
    }

    private func loadDraft() {
        answers = defaults.data(forKey: draftKey)
            .flatMap { try? JSONDecoder().decode([Int: String].self, from: $0) } ?? [:]
        for word in session.sentence?.words ?? [] where answers[word.index] == nil || word.resolved {
            answers[word.index] = word.text
        }
    }

    private var path: String {
        "/api/v1/practice-sessions/\(session.sessionId)/sentences/\(session.sentence?.sentenceId ?? 0)"
    }

    private func command<T: Decodable, B: Encodable>(_ suffix: String, _ body: B) async throws -> T {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let encoded = try encoder.encode(body)
        var identity = try JSONSerialization.jsonObject(with: encoded) as? [String: Any] ?? [:]
        identity.removeValue(forKey: "responseTimeMs")
        identity.removeValue(forKey: "timeSpentSeconds")
        let fingerprint = Data((path + suffix).utf8) + (try JSONSerialization.data(withJSONObject: identity, options: .sortedKeys))
        let digest = SHA256.hash(data: fingerprint).map { String(format: "%02x", $0) }.joined()
        let storageKey = "dictalab.receipt.\(api.account?.id ?? 0).\(digest)"
        // Keep the same operation identity after a lost response or app restart.
        let pending = defaults.data(forKey: storageKey).flatMap { try? JSONDecoder().decode(Pending.self, from: $0) }
            ?? Pending(key: UUID().uuidString, body: encoded)
        defaults.set(try encoder.encode(pending), forKey: storageKey)
        do {
            let response: T = try await api.postData(path + suffix, data: pending.body, idempotencyKey: pending.key)
            defaults.removeObject(forKey: storageKey)
            return response
        } catch let failure as APIStatus {
            if (400..<500).contains(failure.status) { defaults.removeObject(forKey: storageKey) }
            throw failure
        }
    }

    func check(_ word: WordSlot) async {
        guard let answer = answers[word.index]?.trimmingCharacters(in: .whitespacesAndNewlines), !answer.isEmpty else { return }
        await update {
            let elapsed = min(300_000, max(0, Int(Date().timeIntervalSince(self.wordStarted) * 1000)))
            return try await self.command("/words/\(word.index)/checks", WordAnswer(answer: answer, responseTimeMs: elapsed))
        }
        wordStarted = Date()
    }

    func hint(_ type: String, index: Int? = nil) async {
        await update { try await self.command("/hints", Hint(type: type, wordIndex: index)) }
    }

    func classify(_ word: WordSlot, as type: String) async {
        await update { try await self.command("/classifications", Classification(wordIndex: word.index, errorType: type)) }
    }

    private func update(_ operation: () async throws -> Sentence) async {
        guard !busy, !audioActive else { return }
        busy = true
        error = nil
        defer { busy = false }
        do {
            session.sentence = try await operation()
            for word in session.sentence?.words ?? [] where word.resolved { answers[word.index] = word.text }
            saveDraft()
        } catch { self.error = error.localizedDescription }
    }

    func play() {
        guard !busy, !audioActive, let sentence = session.sentence else { return }
        playbackPhase = .preparing
        error = nil
        let playbackSpeed = speed
        playbackTask = Task {
            defer { playbackPhase = .idle; playbackTask = nil }
            do {
                try await audio.play(sentence, speed: playbackSpeed, api: api, onStarted: {
                    self.session.sentence = try await self.command("/replay-events",
                        Replay(event: "STARTED", playbackSpeed: playbackSpeed))
                }, onPlaying: { self.playbackPhase = .playing })
                try Task.checkCancellation()
                playbackPhase = .confirming
                session.sentence = try await command("/replay-events", Replay(event: "COMPLETED", playbackSpeed: playbackSpeed))
                wordStarted = Date()
            } catch is CancellationError { }
            catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }

    func stop() {
        playbackTask?.cancel()
        audio.stop()
        saveDraft()
    }

    func complete() async {
        guard !busy, !audioActive, session.sentence?.canComplete == true else { return }
        busy = true
        error = nil
        defer { busy = false }
        let previousDraftKey = draftKey
        do {
            let elapsed = min(7200, max(0, Int(Date().timeIntervalSince(sentenceStarted))))
            let reply: SentenceCompletion = try await command("/complete", CompleteSentence(timeSpentSeconds: elapsed))
            defaults.removeObject(forKey: previousDraftKey)
            if reply.lessonCompleted {
                guard let result = reply.result else { throw AppError(message: "Máy chủ chưa trả kết quả bài học.") }
                self.result = result
            } else {
                guard let next = reply.nextSentence else { throw AppError(message: "Máy chủ chưa trả câu tiếp theo.") }
                session.sentence = next
                sentenceStarted = Date()
                wordStarted = Date()
                loadDraft()
            }
        } catch { self.error = error.localizedDescription }
    }
}
