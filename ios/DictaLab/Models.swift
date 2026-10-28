import Foundation

struct Account: Codable {
    let id: Int
    let email: String
    let displayName: String
}

struct Lesson: Decodable, Identifiable {
    let id: Int
    let title: String
    let description: String
    let level: String
    let estimatedMinutes: Int
    let sentenceCount: Int
    let progressPercent: Int
    let inProgress: Bool
    let completed: Bool
}

struct WordSlot: Decodable, Identifiable {
    var id: Int { index }
    let index: Int
    let length: Int
    let mask: String
    let punctuationBefore: String?
    let punctuationAfter: String?
    let firstLetter: String?
    let revealedText: String?
    let userInput: String?
    let correct: Bool
    let revealed: Bool
    let prefilled: Bool
    let errorType: String?
    let classificationAvailable: Bool

    var resolved: Bool { correct || revealed || prefilled }
    var text: String { revealedText ?? userInput ?? "" }
}

struct Sentence: Decodable {
    let sentenceId: Int
    let position: Int
    let totalSentences: Int
    let words: [WordSlot]
    let speechText: String?
    let audioUrl: String?
    let audioStartSeconds: Double?
    let audioEndSeconds: Double?
    let speechRate: Double
    let replayCount: Int
    let hintCount: Int
    let revealCount: Int
    let hasListened: Bool
    let requiresReplayAfterReveal: Bool
    let canComplete: Bool

    func nextUnresolvedWord(after index: Int) -> Int? {
        words.first(where: { !$0.resolved && $0.index > index })?.index
            ?? words.first(where: { !$0.resolved })?.index
    }
}

struct PracticeSession: Decodable {
    let sessionId: String
    let lessonId: Int
    let lessonTitle: String
    let level: String
    var sentence: Sentence?
}

struct SentenceCompletion: Decodable {
    let lessonCompleted: Bool
    let nextSentence: Sentence?
    let result: LessonResult?
}

struct LessonResult: Decodable, Identifiable {
    var id: String { sessionId }
    let sessionId: String
    let lessonTitle: String
    let level: String
    let firstAttemptAccuracy: Double
    let finalAccuracy: Double
    let overallScore: Double
    let practiceSeconds: Int
    let review: LessonReview?
}

struct LessonReview: Decodable {
    let sourceName: String?
    let sentences: [SentenceReview]
}

struct SentenceReview: Decodable, Identifiable {
    var id: Int { sentenceId }
    let sentenceId: Int
    let position: Int
    let transcript: String
}

struct HistoryPage: Decodable {
    let items: [HistoryEntry]
    let page: Int
    let totalPages: Int
}

struct HistoryEntry: Decodable, Identifiable {
    var id: String { sessionId }
    let sessionId: String
    let lessonTitle: String
    let level: String
    let overallScore: Double
    let firstAttemptAccuracy: Double
    let completedAt: String
}

struct Dashboard: Decodable {
    struct Summary: Decodable {
        let completedLessons: Int
        let learningMinutes: Int
        let averageFirstAttemptAccuracy: Double
        let averageScore: Double
    }
    struct Assessment: Decodable {
        let estimatedLevel: String?
        let summary: String
        let provisional: Bool
    }
    let summary: Summary
    let assessment: Assessment?
}

struct ServerError: Decodable {
    let message: String
}

struct AppError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

struct Credentials: Encodable {
    let email: String
    let password: String
}

struct Registration: Encodable {
    let displayName: String
    let email: String
    let password: String
}

struct EmptyBody: Encodable {}
struct StartPractice: Encodable { let lessonId: Int }
struct WordAnswer: Encodable { let answer: String; let responseTimeMs: Int }
struct Replay: Encodable { let event: String; let playbackSpeed: Double }
struct Hint: Encodable { let type: String; let wordIndex: Int? }
struct Classification: Encodable { let wordIndex: Int; let errorType: String }
struct CompleteSentence: Encodable { let timeSpentSeconds: Int }
