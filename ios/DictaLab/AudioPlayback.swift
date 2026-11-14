import AVFoundation
import Foundation

struct AudioSegment {
    let start: Double
    let end: Double

    static func resolve(start: Double?, end: Double?, duration: Double) throws -> AudioSegment {
        guard duration.isFinite, duration > 0 else { throw AppError(message: "File audio không có thời lượng hợp lệ.") }
        if start == nil, end == nil { return AudioSegment(start: 0, end: duration) }
        guard let start, let end, start.isFinite, end.isFinite,
              start >= 0, end > start, start < duration else {
            throw AppError(message: "Mốc phát của câu không hợp lệ. Vui lòng chọn bài khác và báo hỗ trợ.")
        }
        return AudioSegment(start: start, end: min(end, duration))
    }
}

@MainActor
protocol AudioPlaying {
    func play(_ sentence: Sentence, speed: Double, api: APIClient,
              onStarted: () async throws -> Void, onPlaying: () -> Void) async throws
    func stop()
}

@MainActor
final class AudioPlayback: NSObject, AVAudioPlayerDelegate, AudioPlaying {
    private var player: AVAudioPlayer?
    private let speech = AVSpeechSynthesizer()
    private var stopped = false
    private var finishedNormally = false
    private var cachedAudio: (path: String, data: Data)?

    func play(_ sentence: Sentence, speed: Double, api: APIClient,
              onStarted: () async throws -> Void, onPlaying: () -> Void) async throws {
        stop()
        stopped = false
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try AVAudioSession.sharedInstance().setActive(true)
        defer {
            player?.stop()
            player = nil
            speech.stopSpeaking(at: .immediate)
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }

        if let path = sentence.audioUrl, !path.isEmpty {
            let data: Data
            if let cachedAudio, cachedAudio.path == path { data = cachedAudio.data }
            else {
                data = try await api.audioData(path)
                cachedAudio = (path, data)
            }
            try Task.checkCancellation()
            guard !stopped else { throw CancellationError() }
            let audio = try AVAudioPlayer(data: data)
            player = audio
            audio.delegate = self
            audio.enableRate = true
            audio.rate = Float(speed)
            let segment = try AudioSegment.resolve(start: sentence.audioStartSeconds,
                end: sentence.audioEndSeconds, duration: audio.duration)
            audio.currentTime = segment.start
            guard audio.prepareToPlay() else { throw AppError(message: "Không thể chuẩn bị audio.") }
            try await onStarted()
            try Task.checkCancellation()
            guard !stopped else { throw CancellationError() }
            finishedNormally = false
            guard audio.play() else { throw AppError(message: "Không thể phát audio. Hãy thử lại.") }
            onPlaying()
            while audio.isPlaying, audio.currentTime < segment.end {
                try await Task.sleep(nanoseconds: 20_000_000)
            }
            try Task.checkCancellation()
            guard !stopped else { throw CancellationError() }
            guard audio.currentTime >= segment.end - 0.06 || finishedNormally else {
                throw AppError(message: "Audio bị gián đoạn. Hãy nghe lại trọn câu.")
            }
        } else if let text = sentence.speechText, !text.isEmpty {
            try await onStarted()
            try Task.checkCancellation()
            guard !stopped else { throw CancellationError() }
            let utterance = AVSpeechUtterance(string: text)
            utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
            utterance.rate = Float(min(0.65, max(0.25, sentence.speechRate * speed)))
            speech.speak(utterance)
            onPlaying()
            while speech.isSpeaking { try await Task.sleep(nanoseconds: 40_000_000) }
            try Task.checkCancellation()
            guard !stopped else { throw CancellationError() }
        } else { throw AppError(message: "Bài này chưa có audio để phát.") }
    }

    func stop() {
        stopped = true
        player?.stop()
        speech.stopSpeaking(at: .immediate)
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        finishedNormally = flag
    }
}
