// PracticeView.swift — DictaLab Academic Editorial redesign
// Full-screen dictation practice room with waveform hero

import SwiftUI
import AVFoundation

struct PracticeView: View {
    @StateObject private var store: PracticeStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmExit = false
    @State private var confirmReveal = false
    @State private var correctFlash: Int? = nil   // word index that just turned correct

    init(session: PracticeSession, api: APIClient) {
        _store = StateObject(wrappedValue: PracticeStore(session: session, api: api))
    }

    var body: some View {
        ZStack {
            Color.dlBackground.ignoresSafeArea()

            if let result = store.result {
                // Finished — show result inline
                ResultView(result: result)
                    .transition(.opacity.combined(with: .move(edge: .trailing)))
            } else if let sentence = store.session.sentence {
                practiceRoom(sentence)
                    .transition(.opacity)
            } else {
                Text("Phiên học không còn câu. Xem kết quả trong Lịch sử.")
                    .foregroundStyle(Color.dlInkSecondary)
                    .multilineTextAlignment(.center)
                    .padding()
            }
        }
        .navigationBarHidden(false)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { practiceToolbar }
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

    // MARK: - Practice Room Layout

    @ViewBuilder
    private func practiceRoom(_ sentence: Sentence) -> some View {
        VStack(spacing: 0) {

            // ── Progress bar (full width, below nav) ─────────────────────
            TealProgressBar(
                value: Double(sentence.position - 1) / Double(max(1, sentence.totalSentences))
            )
            .padding(.horizontal, 0)
            .animation(.spring(response: 0.5, dampingFraction: 0.8), value: sentence.position)

            ScrollView {
                VStack(spacing: 24) {

                    // ── Waveform Hero ─────────────────────────────────────
                    waveformHero(sentence)

                    // ── Error notice ──────────────────────────────────────
                    if let error = store.error {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.circle")
                                .foregroundStyle(.red)
                            Text(error)
                                .font(.caption)
                                .foregroundStyle(Color.dlInk)
                            Spacer()
                        }
                        .padding(12)
                        .background(Color.red.opacity(0.07), in: RoundedRectangle(cornerRadius: 10))
                        .padding(.horizontal, 16)
                    }

                    // ── Word Grid ─────────────────────────────────────────
                    wordGrid(sentence)

                    // ── Hint Row ──────────────────────────────────────────
                    hintRow(sentence)

                    // ── Next/Complete Button ───────────────────────────────
                    if sentence.canComplete {
                        nextButton(sentence)
                            .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }

                    Spacer(minLength: 40)
                }
                .padding(.top, 20)
                .animation(.spring(response: 0.35, dampingFraction: 0.8), value: sentence.canComplete)
            }
        }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var practiceToolbar: some ToolbarContent {
        // Leading: back / close
        ToolbarItem(placement: .cancellationAction) {
            Button {
                if store.result != nil { dismiss() } else { confirmExit = true }
            } label: {
                Image(systemName: store.result != nil ? "xmark" : "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.dlInk)
            }
            .disabled(store.busy)
        }

        // Center: sentence counter
        ToolbarItem(placement: .principal) {
            if let sentence = store.session.sentence {
                Text("\(sentence.position) / \(sentence.totalSentences)")
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color.dlInk)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .background(Color.dlSurface, in: Capsule())
                    .overlay(Capsule().stroke(Color.dlBorder.opacity(0.5), lineWidth: 1))
            }
        }

        // Trailing: loading indicator
        ToolbarItem(placement: .primaryAction) {
            if store.busy {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: Color.dlTeal))
                    .scaleEffect(0.75)
            }
        }
    }

    // MARK: - Waveform Hero

    private func waveformHero(_ sentence: Sentence) -> some View {
        VStack(spacing: 16) {
            // Tappable waveform
            Button {
                if store.playing { store.stop() } else { store.play() }
            } label: {
                ZStack {
                    Circle()
                        .fill(Color.dlTeal.opacity(store.playing ? 0.10 : 0.05))
                        .frame(width: 130, height: 130)
                        .scaleEffect(store.playing ? 1.06 : 1.0)
                        .animation(
                            store.playing
                                ? .easeInOut(duration: 1.2).repeatForever(autoreverses: true)
                                : .easeOut(duration: 0.3),
                            value: store.playing
                        )

                    WaveformVisualizer(isPlaying: store.playing)
                        .frame(width: 90, height: 64)
                }
            }
            .disabled(store.busy)
            .accessibilityLabel(store.playing ? "Dừng phát" : "Nghe câu này")

            // Listen count hint
            if sentence.replayCount > 0 {
                Text("Đã nghe \(sentence.replayCount) lượt")
                    .font(.system(size: 12))
                    .foregroundStyle(Color.dlInkSecondary)
                    .transition(.opacity)
            }

            // Speed chips
            SpeedChipRow(speed: $store.speed, disabled: store.playing || store.busy)
        }
        .padding(.horizontal, 16)
    }

    // MARK: - Word Grid

    private func wordGrid(_ sentence: Sentence) -> some View {
        FlowLayout(spacing: 8) {
            ForEach(sentence.words) { word in
                wordTile(word, sentence: sentence)
            }
        }
        .padding(.horizontal, 16)
    }

    @ViewBuilder
    private func wordTile(_ word: WordSlot, sentence: Sentence) -> some View {
        if word.resolved {
            // Correct / revealed tile
            Text((word.punctuationBefore ?? "") + word.text + (word.punctuationAfter ?? ""))
                .font(.system(size: 15, weight: .bold, design: .serif))
                .foregroundStyle(word.revealed ? Color.dlInkSecondary : Color.dlTeal)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(
                    word.revealed
                        ? Color.dlSurface
                        : Color.dlTealLight,
                    in: RoundedRectangle(cornerRadius: 10)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(word.revealed ? Color.dlBorder : Color.dlTeal.opacity(0.4), lineWidth: 1)
                )
                .scaleEffect(correctFlash == word.index ? 1.08 : 1.0)
                .animation(.spring(response: 0.25, dampingFraction: 0.6), value: correctFlash)
                .accessibilityLabel("Từ \(word.index + 1): \(word.text), \(word.revealed ? "đã mở đáp án" : "đúng")")
        } else {
            // Input tile
            WordInputTile(
                word: word,
                sentence: sentence,
                answer: Binding(
                    get: { store.answers[word.index] ?? "" },
                    set: { store.answers[word.index] = String($0.prefix(100)) }
                ),
                busy: store.busy || store.playing,
                onSubmit: { Task {
                    await store.check(word)
                    if word.correct {
                        withAnimation { correctFlash = word.index }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            withAnimation { correctFlash = nil }
                        }
                    }
                }},
                onHint: { hintType in Task { await store.hint(hintType, index: word.index) } },
                onReveal: { Task { await store.hint("REVEAL_WORD", index: word.index) } }
            )
        }
    }

    // MARK: - Hint Row

    private func hintRow(_ sentence: Sentence) -> some View {
        HStack(spacing: 8) {
            // Reveal sentence button (destructive, hidden unless eligible)
            if !sentence.canComplete {
                Button {
                    guard sentence.hasListened else { return }
                    confirmReveal = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "lightbulb")
                            .font(.system(size: 12))
                        Text("Gợi ý")
                            .font(.system(size: 13))
                        if sentence.hintCount > 0 {
                            Text("\(sentence.hintCount)")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(Color.dlBackground)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.dlTeal, in: Capsule())
                        }
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10))
                    }
                    .foregroundStyle(Color.dlInkSecondary)
                }
                .disabled(store.busy || store.playing || !sentence.hasListened)
            }

            Spacer()

            // Word count
            let resolved = sentence.words.filter(\.resolved).count
            Text("\(resolved)/\(sentence.words.count) từ")
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.dlInkSecondary)
        }
        .padding(.horizontal, 16)
    }

    // MARK: - Next Button

    private func nextButton(_ sentence: Sentence) -> some View {
        Button {
            Task { await store.complete() }
        } label: {
            HStack(spacing: 8) {
                Text(sentence.position == sentence.totalSentences
                     ? "Hoàn thành bài nghe"
                     : "Câu tiếp theo")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Color.dlBackground)
                Spacer()
                if store.busy {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: Color.dlBackground))
                        .scaleEffect(0.8)
                } else {
                    Image(systemName: "arrow.right")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Color.dlBackground)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .background(Color.dlInk, in: RoundedRectangle(cornerRadius: 14))
        }
        .disabled(store.busy || store.playing)
        .padding(.horizontal, 16)
    }
}

// MARK: - Word Input Tile (compound subview)

struct WordInputTile: View {
    let word: WordSlot
    let sentence: Sentence
    @Binding var answer: String
    let busy: Bool
    let onSubmit: () -> Void
    let onHint: (String) -> Void
    let onReveal: () -> Void

    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 0) {
            // Length hint dots
            let placeholder = String(repeating: "·", count: min(word.length, 8))

            TextField(
                word.firstLetter.map { "· · ·  (\($0)…)" } ?? placeholder,
                text: $answer
            )
            .font(.system(size: 14, weight: .medium, design: .monospaced))
            .foregroundStyle(Color.dlInk)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.done)
            .focused($focused)
            .disabled(!sentence.hasListened || busy)
            .onSubmit(onSubmit)
            .frame(minWidth: CGFloat(word.length) * 9 + 20, maxWidth: 160)
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(
                ZStack {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.dlSurface)
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(
                            focused ? Color.dlTeal : (hasError ? Color.red.opacity(0.5) : Color.dlBorder.opacity(0.6)),
                            lineWidth: focused ? 1.5 : 1
                        )
                }
            )
            .shadow(
                color: focused ? Color.dlTeal.opacity(0.2) : .clear,
                radius: focused ? 4 : 0
            )
            .animation(.easeInOut(duration: 0.2), value: focused)

            // Hint button (word-level, only after listening)
            if sentence.hasListened && !busy {
                Menu {
                    Button("Chữ cái đầu") { onHint("FIRST_LETTER") }
                    Button("Mở từ này", role: .destructive) { onReveal() }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.dlInkSecondary)
                        .frame(width: 28, height: 28)
                }
            }
        }
        .accessibilityLabel("Từ \(word.index + 1), \(word.length) chữ cái")
    }

    private var hasError: Bool {
        guard let prev = word.userInput, !prev.isEmpty else { return false }
        return !word.correct && !word.revealed
    }
}

// MARK: - FlowLayout (wrapping HStack)

struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var maxX: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > width && x > 0 {
                y += rowHeight + spacing
                x = 0; rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            maxX = max(maxX, x)
        }
        return CGSize(width: maxX, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        var rowStart: CGFloat = bounds.minX          // track x at start of each row
        var rowViews: [(subview: LayoutSubview, size: CGSize)] = []

        func placeRow() {
            var rx = rowStart
            for (sub, size) in rowViews {
                sub.place(at: CGPoint(x: rx, y: y), proposal: ProposedViewSize(size))
                rx += size.width + spacing
            }
            rowViews.removeAll()
        }

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX && !rowViews.isEmpty {
                placeRow()
                y += rowHeight + spacing
                x = bounds.minX; rowStart = bounds.minX; rowHeight = 0
            }
            rowViews.append((subview, size))
            rowHeight = max(rowHeight, size.height)
            x += size.width + spacing
        }
        placeRow()
    }
}
