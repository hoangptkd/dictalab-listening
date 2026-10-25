import SwiftUI
import AVFoundation

struct PracticeView: View {
    @StateObject private var store: PracticeStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmExit = false
    @State private var confirmReveal = false

    init(session: PracticeSession, api: APIClient) {
        _store = StateObject(wrappedValue: PracticeStore(session: session, api: api))
    }

    var body: some View {
        Group {
            if let result = store.result { ResultView(result: result) }
            else if let sentence = store.session.sentence {
                List {
                    Section {
                        Text(store.session.lessonTitle).font(.title2.bold())
                        Text("\(store.session.level) · Câu \(sentence.position)/\(sentence.totalSentences)").foregroundStyle(.secondary)
                        ProgressView(value: Double(sentence.position - 1), total: Double(max(1, sentence.totalSentences)))
                            .accessibilityLabel("Tiến độ bài nghe")
                    }
                    playbackControls(sentence)
                    if let error = store.error { Section { ErrorNotice(message: error) } }
                    Section {
                        ForEach(sentence.words) { word in wordRow(word, sentence: sentence) }
                    } header: { Text("Nghe và điền từng từ") } footer: {
                        Text(sentence.requiresReplayAfterReveal
                            ? "Bạn đã mở đáp án. Cần nghe lại trọn câu trước khi tiếp tục."
                            : "Từ được mở đáp án không tính là trả lời đúng. Phần đang nhập được lưu trên thiết bị này.")
                    }
                    Section {
                        Button("Mở đáp án cả câu", role: .destructive) { confirmReveal = true }
                            .disabled(store.busy || store.playing || !sentence.hasListened || sentence.canComplete)
                        Button { Task { await store.complete() } } label: {
                            HStack {
                                Text(sentence.position == sentence.totalSentences ? "Hoàn thành bài nghe" : "Câu tiếp theo")
                                Spacer()
                                if store.busy { ProgressView() } else { Image(systemName: "arrow.right") }
                            }.font(.headline)
                        }.disabled(store.busy || store.playing || !sentence.canComplete)
                    }
                }
            } else { Text("Phiên học không còn câu đang luyện. Hãy xem kết quả trong Lịch sử.").padding() }
        }
        .navigationTitle(store.result == nil ? "Phòng luyện" : "Kết quả")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(store.result == nil ? "Đóng" : "Xong") {
                    if store.result != nil { dismiss() } else { confirmExit = true }
                }.disabled(store.busy)
            }
        }
        .interactiveDismissDisabled()
        .confirmationDialog("Tạm dừng bài nghe?", isPresented: $confirmExit, titleVisibility: .visible) {
            Button("Lưu và đóng") { store.stop(); dismiss() }
            Button("Tiếp tục học", role: .cancel) { }
        } message: { Text("Kết quả đã chấm được giữ trên máy chủ. Phần chưa gửi được lưu trên iPhone này.") }
        .confirmationDialog("Mở đáp án cả câu?", isPresented: $confirmReveal, titleVisibility: .visible) {
            Button("Mở đáp án", role: .destructive) { Task { await store.hint("REVEAL_SENTENCE") } }
            Button("Hủy", role: .cancel) { }
        } message: { Text("Các từ mở đáp án không tính là trả lời đúng. Bạn cần nghe lại trọn câu.") }
        .onChange(of: store.answers) { _ in store.saveDraft() }
        .onChange(of: scenePhase) { phase in if phase != .active { store.stop() } }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { _ in store.stop() }
        .onDisappear { store.stop() }
    }

    private func playbackControls(_ sentence: Sentence) -> some View {
        Section {
            Picker("Tốc độ", selection: $store.speed) {
                Text("0.75×").tag(0.75)
                Text("1×").tag(1.0)
                Text("1.25×").tag(1.25)
            }.pickerStyle(.segmented).disabled(store.playing || store.busy)
            Button {
                if store.playing { store.stop() } else { store.play() }
            } label: {
                Label(store.playing ? "Dừng phát" : "Nghe câu này", systemImage: store.playing ? "stop.fill" : "play.fill")
                    .font(.headline).frame(maxWidth: .infinity, minHeight: 44)
            }.disabled(store.busy)
            Text("Đã nghe \(sentence.replayCount) lượt · \(sentence.hintCount) gợi ý")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func wordRow(_ word: WordSlot, sentence: Sentence) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text("\(word.index + 1)").font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(minWidth: 20)
                if word.resolved {
                    Label((word.punctuationBefore ?? "") + word.text + (word.punctuationAfter ?? ""),
                          systemImage: word.revealed ? "eye" : "checkmark.circle.fill")
                        .foregroundStyle(word.revealed ? Color.secondary : Color.accentColor)
                        .accessibilityLabel("Từ \(word.index + 1): \(word.text), \(word.revealed ? "đã mở đáp án" : "đúng")")
                } else {
                    TextField(word.firstLetter.map { "Bắt đầu bằng \($0) · \(word.length) chữ" } ?? "\(word.length) chữ cái", text: Binding(
                        get: { store.answers[word.index] ?? "" },
                        set: { store.answers[word.index] = String($0.prefix(100)) }))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .submitLabel(.done).accessibilityLabel("Từ \(word.index + 1), \(word.length) chữ cái")
                        .disabled(!sentence.hasListened || store.busy || store.playing)
                        .onSubmit { Task { await store.check(word) } }
                    Button { Task { await store.check(word) } } label: { Image(systemName: "checkmark.circle").frame(minWidth: 44, minHeight: 44) }
                        .buttonStyle(.borderless).accessibilityLabel("Chấm từ \(word.index + 1)")
                        .disabled(!sentence.hasListened || store.busy || store.playing || (store.answers[word.index] ?? "").trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            if !word.resolved, sentence.hasListened {
                Menu("Gợi ý từ \(word.index + 1)") {
                    Button("Chữ cái đầu") { Task { await store.hint("FIRST_LETTER", index: word.index) } }
                    Button("Mở từ này", role: .destructive) { Task { await store.hint("REVEAL_WORD", index: word.index) } }
                }.font(.caption).disabled(store.busy || store.playing)
            }
            if word.classificationAvailable {
                Menu(word.errorType == "UNKNOWN" ? "Từ chưa biết" : word.errorType == "KNOWN_BUT_MISSED" ? "Biết nhưng nghe lỡ" : "Phân loại từ đã sai") {
                    Button("Biết nhưng nghe lỡ") { Task { await store.classify(word, as: "KNOWN_BUT_MISSED") } }
                    Button("Từ chưa biết") { Task { await store.classify(word, as: "UNKNOWN") } }
                }.font(.caption).disabled(store.busy || store.playing)
            }
            if !word.correct, !word.revealed, let previous = word.userInput, !previous.isEmpty {
                Text("Chưa đúng. Hãy nghe và thử lại.").font(.caption).foregroundStyle(.red)
            }
        }.padding(.vertical, 4)
    }
}
