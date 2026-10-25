import SwiftUI

struct CatalogView: View {
    @EnvironmentObject private var api: APIClient
    @State private var level = "A1"
    @State private var lessons: [Lesson] = []
    @State private var loading = false
    @State private var opening = false
    @State private var error: String?
    @State private var practice: PracticeSession?

    var body: some View {
        List {
            Section {
                Text("Nghe kỹ. Điền từng từ. Nhớ lâu.").font(.title2.bold())
                Picker("Trình độ", selection: $level) {
                    ForEach(["A1", "A2", "B1", "B2"], id: \.self) { Text($0).tag($0) }
                }.pickerStyle(.segmented)
            }
            if let error { Section { ErrorNotice(message: error); Button("Thử lại") { Task { await load() } } } }
            if loading { ProgressView("Đang tải bài nghe…") }
            else if lessons.isEmpty, error == nil { Text("Chưa có bài nghe ở trình độ này.").foregroundStyle(.secondary) }
            ForEach(lessons) { lesson in
                Button { Task { await open(lesson) } } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(lesson.level).font(.caption.bold()).foregroundStyle(Color.accentColor)
                            Spacer()
                            if lesson.completed { Label("Đã học", systemImage: "checkmark.circle.fill").font(.caption).foregroundStyle(.secondary) }
                        }
                        Text(lesson.title).font(.headline).foregroundStyle(.primary)
                        Text(lesson.description).font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                        Text("\(lesson.sentenceCount) câu · khoảng \(lesson.estimatedMinutes) phút").font(.caption).foregroundStyle(.secondary)
                        if lesson.inProgress {
                            ProgressView(value: Double(lesson.progressPercent), total: 100)
                            Text("Tiếp tục · \(lesson.progressPercent)%").font(.caption).foregroundStyle(Color.accentColor)
                        }
                    }.padding(.vertical, 6).contentShape(Rectangle())
                }.buttonStyle(.plain).disabled(opening || loading)
            }
        }
        .navigationTitle("Bài nghe")
        .overlay { if opening { ProgressView("Đang mở bài…").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
        .task(id: level) { await load() }
        .refreshable { await load() }
        .fullScreenCover(isPresented: Binding(get: { practice != nil }, set: { if !$0 { practice = nil } })) {
            if let practice { NavigationStack { PracticeView(session: practice, api: api) } }
        }
        .onChange(of: practice == nil) { closed in if closed { Task { await load() } } }
    }

    private func load() async {
        loading = true; error = nil
        let selected = level
        defer { if selected == level { loading = false } }
        do {
            let rows: [Lesson] = try await api.get("/api/v1/catalog/lessons?level=\(selected)")
            try Task.checkCancellation()
            if selected == level { lessons = rows }
        } catch is CancellationError { }
        catch { if selected == level { self.error = error.localizedDescription } }
    }

    private func open(_ lesson: Lesson) async {
        opening = true; error = nil
        defer { opening = false }
        do { practice = try await api.post("/api/v1/practice-sessions", StartPractice(lessonId: lesson.id)) }
        catch { self.error = error.localizedDescription }
    }
}

struct HistoryView: View {
    @EnvironmentObject private var api: APIClient
    @State private var items: [HistoryEntry] = []
    @State private var nextPage = 0
    @State private var totalPages = 1
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        List {
            if let error { ErrorNotice(message: error); Button("Thử lại") { Task { await load(reset: false) } } }
            if items.isEmpty, !busy, error == nil { Text("Hoàn thành bài nghe đầu tiên để xem lịch sử.").foregroundStyle(.secondary) }
            ForEach(items) { item in
                NavigationLink { ResultLoader(sessionID: item.sessionId) } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.lessonTitle).font(.headline)
                        Text("\(item.level) · Lần đầu đúng \(item.firstAttemptAccuracy.formatted(.number.precision(.fractionLength(1))))%")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Text(historyDate(item.completedAt)).font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 4)
                }
            }
            if busy { ProgressView("Đang tải lịch sử…") }
            else if nextPage < totalPages { Button("Xem thêm") { Task { await load(reset: false) } } }
        }.navigationTitle("Lịch sử")
            .task { await load(reset: true) }
            .refreshable { await load(reset: true) }
    }

    private func historyDate(_ raw: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = formatter.date(from: raw) ?? ISO8601DateFormatter().date(from: raw)
        return date?.formatted(date: .abbreviated, time: .shortened) ?? ""
    }

    private func load(reset: Bool) async {
        guard !busy else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            let page: HistoryPage = try await api.get("/api/v1/history?page=\(reset ? 0 : nextPage)&size=10")
            if reset { items = page.items }
            else { items += page.items.filter { new in !items.contains(where: { $0.id == new.id }) } }
            nextPage = page.page + 1
            totalPages = page.totalPages
        } catch { self.error = error.localizedDescription }
    }
}

struct ResultLoader: View {
    @EnvironmentObject private var api: APIClient
    let sessionID: String
    @State private var result: LessonResult?
    @State private var error: String?

    var body: some View {
        Group {
            if let result { ResultView(result: result) }
            else if let error { VStack { ErrorNotice(message: error); Button("Thử lại") { Task { await load() } } }.padding() }
            else { ProgressView("Đang tải kết quả…") }
        }.task { await load() }
    }

    private func load() async {
        error = nil
        do { result = try await api.get("/api/v1/history/\(sessionID)") }
        catch { self.error = error.localizedDescription }
    }
}

struct ResultView: View {
    let result: LessonResult
    var body: some View {
        List {
            Section {
                Label("Đã hoàn thành", systemImage: "checkmark.circle.fill").foregroundStyle(Color.accentColor)
                Text(result.lessonTitle).font(.title2.bold())
                Text("\(result.level) · \(result.practiceSeconds / 60) phút \(result.practiceSeconds % 60) giây").foregroundStyle(.secondary)
            }
            Section("Kết quả luyện nghe") {
                MetricRow(title: "Đúng lần đầu", value: result.firstAttemptAccuracy, suffix: "%")
                MetricRow(title: "Đúng cuối cùng", value: result.finalAccuracy, suffix: "%")
                MetricRow(title: "Điểm tổng", value: result.overallScore, suffix: "/100")
            }
            if let review = result.review {
                Section("Xem lại câu đã nghe") {
                    ForEach(review.sentences) { sentence in
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Câu \(sentence.position)").font(.caption).foregroundStyle(.secondary)
                            Text(sentence.transcript).textSelection(.enabled)
                        }.padding(.vertical, 4)
                    }
                }
                if let source = review.sourceName { Section { Text("Nguồn: \(source)").font(.caption).foregroundStyle(.secondary) } }
            }
        }.navigationTitle("Kết quả").navigationBarTitleDisplayMode(.inline)
    }
}

struct MetricRow: View {
    let title: String
    let value: Double
    let suffix: String
    var body: some View {
        HStack { Text(title); Spacer(); Text(value.formatted(.number.precision(.fractionLength(1))) + suffix).bold().monospacedDigit() }
    }
}

struct ProgressScreen: View {
    @EnvironmentObject private var api: APIClient
    @State private var dashboard: Dashboard?
    @State private var error: String?

    var body: some View {
        List {
            if let error { ErrorNotice(message: error); Button("Thử lại") { Task { await load() } } }
            if let dashboard {
                Section("Thói quen học tập") {
                    LabeledContent("Bài hoàn thành", value: String(dashboard.summary.completedLessons))
                    LabeledContent("Phút luyện nghe", value: String(dashboard.summary.learningMinutes))
                    MetricRow(title: "Đúng lần đầu", value: dashboard.summary.averageFirstAttemptAccuracy, suffix: "%")
                    MetricRow(title: "Điểm trung bình", value: dashboard.summary.averageScore, suffix: "/100")
                }
                if let assessment = dashboard.assessment {
                    Section("Năng lực nghe") {
                        if let level = assessment.estimatedLevel { Text(level).font(.largeTitle.bold()).foregroundStyle(Color.accentColor) }
                        Text(assessment.summary)
                        if assessment.provisional { Text("Ước lượng tạm thời; cần thêm bài học để tăng độ tin cậy.").font(.caption).foregroundStyle(.secondary) }
                    }
                }
            } else if error == nil { ProgressView("Đang tải tiến bộ…") }
        }.navigationTitle("Tiến bộ").task { await load() }.refreshable { await load() }
    }

    private func load() async {
        error = nil
        do { dashboard = try await api.get("/api/v1/dashboard") }
        catch { self.error = error.localizedDescription }
    }
}
