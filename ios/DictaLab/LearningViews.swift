// LearningViews.swift — DictaLab Academic Editorial redesign
// CatalogView, HistoryView, ProgressScreen, ResultView, AccountView

import SwiftUI

// MARK: - Root Tab Container

struct MainTabView: View {
    @EnvironmentObject private var api: APIClient
    @State private var selectedTab = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.dlBackground.ignoresSafeArea()

            // Page content
            TabView(selection: $selectedTab) {
                NavigationStack { CatalogView(api: api) }
                    .toolbar(.hidden, for: .tabBar).tag(0)
                NavigationStack { HistoryView() }
                    .toolbar(.hidden, for: .tabBar).tag(1)
                NavigationStack { ProgressScreen() }
                    .toolbar(.hidden, for: .tabBar).tag(2)
                NavigationStack { AccountView() }
                    .toolbar(.hidden, for: .tabBar).tag(3)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            FloatingTabBar(selectedTab: $selectedTab)
                .padding(.vertical, 12)
        }
        // Hide the system tab bar (we draw our own)
        .toolbar(.hidden, for: .tabBar)
    }
}

// MARK: - CatalogView

@MainActor
final class CatalogStore: ObservableObject {
    @Published var level = "A1"
    @Published private(set) var lessons: [Lesson] = []
    @Published private(set) var loading = false
    @Published var error: String?
    private var loadedLevel: String?
    private var requestID = UUID()
    private let api: APIClient

    init(api: APIClient) { self.api = api }

    var needsLoad: Bool { loadedLevel != level }

    func load() async {
        let selected = level
        let request = UUID()
        requestID = request
        if loadedLevel != selected { lessons = []; loadedLevel = nil }
        loading = true; error = nil
        defer { if requestID == request { loading = false } }
        do {
            let rows: [Lesson] = try await api.get("/api/v1/catalog/lessons?level=\(selected)")
            try Task.checkCancellation()
            guard requestID == request, selected == level else { return }
            lessons = rows
            loadedLevel = selected
        } catch is CancellationError { }
        catch {
            if requestID == request, selected == level { self.error = error.localizedDescription }
        }
    }
}

struct CatalogView: View {
    @EnvironmentObject private var api: APIClient
    @StateObject private var catalog: CatalogStore
    @State private var opening = false
    @State private var practice: PracticeSession?

    private let levels = ["A1", "A2", "B1", "B2"]

    init(api: APIClient) {
        _catalog = StateObject(wrappedValue: CatalogStore(api: api))
    }

    var body: some View {
        ZStack {
            Color.dlBackground.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    // ── Header ───────────────────────────────────────────
                    catalogHeader

                    // ── Level Filter Pills ────────────────────────────────
                    levelFilterStrip
                        .padding(.horizontal, 16)
                        .padding(.top, 8)

                    // ── Content ───────────────────────────────────────────
                    VStack(spacing: 12) {
                        if let error = catalog.error {
                            errorBanner(error)
                        }
                        if catalog.loading {
                            skeletonCards
                        } else if catalog.lessons.isEmpty && catalog.error == nil {
                            emptyState
                        } else {
                            ForEach(catalog.lessons) { lesson in
                                LessonCard(lesson: lesson, disabled: opening || catalog.loading) {
                                    Task { await open(lesson) }
                                }
                                .transition(.opacity.combined(with: .move(edge: .bottom)))
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                    .padding(.bottom, 16)
                    .animation(.spring(response: 0.4, dampingFraction: 0.85), value: catalog.lessons.count)
                    .animation(.spring(response: 0.4, dampingFraction: 0.85), value: catalog.loading)
                }
            }
            .refreshable { await catalog.load() }

            // Opening overlay — thin teal ring, top-right
            if opening {
                VStack {
                    HStack {
                        Spacer()
                        ProgressView()
                            .tint(Color.dlTeal)
                            .scaleEffect(0.8)
                            .padding(14)
                            .background(.ultraThinMaterial, in: Circle())
                            .padding(.top, 56)
                            .padding(.trailing, 16)
                    }
                    Spacer()
                }
            }
        }
        .navigationBarHidden(true)
        .task(id: catalog.level) {
            if catalog.needsLoad { await catalog.load() }
        }
        .fullScreenCover(
            isPresented: Binding(
                get: { practice != nil },
                set: { if !$0 { practice = nil } }
            )
        ) {
            if let practice {
                NavigationStack {
                    PracticeView(session: practice, api: api)
                }
            }
        }
        .onChange(of: practice == nil) { closed in
            if closed { Task { await catalog.load() } }
        }
    }

    // ── Subviews ─────────────────────────────────────────────────────────

    private var catalogHeader: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Image(systemName: "waveform")
                        .font(.system(.body, weight: .semibold))
                        .foregroundStyle(Color.dlInk)
                    Text("DictaLab")
                        .font(.system(.title3, design: .serif, weight: .bold))
                        .foregroundStyle(Color.dlInk)
                }
                Text("\(catalog.lessons.count > 0 ? "\(catalog.lessons.count) bài" : "Bài nghe") · \(catalog.level)")
                    .font(.system(.caption))
                    .foregroundStyle(Color.dlInkSecondary)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 8)
    }

    private var levelFilterStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(levels, id: \.self) { lvl in
                    Button {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.75)) {
                            catalog.level = lvl
                        }
                    } label: {
                        Text(lvl)
                            .font(.system(.subheadline, design: .monospaced, weight: catalog.level == lvl ? .bold : .medium))
                            .foregroundStyle(catalog.level == lvl ? Color.dlInk : Color.dlInkSecondary)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 8)
                            .frame(minHeight: 44)
                            .background(
                                catalog.level == lvl ? Color.dlTeal : Color.dlSurface,
                                in: Capsule()
                            )
                            .overlay(
                                Capsule().stroke(
                                    catalog.level == lvl ? Color.clear : Color.dlBorder,
                                    lineWidth: 1
                                )
                            )
                    }
                    .accessibilityAddTraits(catalog.level == lvl ? .isSelected : [])
                    .disabled(opening)
                    .animation(.spring(response: 0.3, dampingFraction: 0.75), value: catalog.level)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private var skeletonCards: some View {
        VStack(spacing: 12) {
            ForEach(0..<3, id: \.self) { _ in
                LessonCardSkeleton()
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "headphones")
                .font(.system(.largeTitle))
                .foregroundStyle(Color.dlBorder)
            Text("Chưa có bài nghe ở trình độ \(catalog.level).")
                .font(.subheadline)
                .foregroundStyle(Color.dlInkSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, 40)
    }

    private func errorBanner(_ msg: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.circle")
                .foregroundStyle(.red)
            Text(msg)
                .font(.caption)
                .foregroundStyle(Color.dlInk)
                .lineLimit(2)
            Spacer()
            Button("Thử lại") { Task { await catalog.load() } }
                .font(.caption.bold())
                .foregroundStyle(Color.dlInk)
        }
        .padding(12)
        .background(Color.red.opacity(0.06), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.red.opacity(0.15), lineWidth: 1))
    }

    // ── Data ──────────────────────────────────────────────────────────────

    private func open(_ lesson: Lesson) async {
        opening = true; catalog.error = nil
        defer { opening = false }
        do { practice = try await api.post("/api/v1/practice-sessions", StartPractice(lessonId: lesson.id)) }
        catch { catalog.error = error.localizedDescription }
    }
}

// MARK: - Lesson Card

struct LessonCard: View {
    let lesson: Lesson
    let disabled: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                // Top: level badge + status
                HStack {
                    LevelBadge(level: lesson.level)
                    Spacer()
                    if lesson.completed {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(Color.dlInk)
                            .font(.system(.body))
                    }
                }
                .padding(.bottom, 10)

                // Title
                Text(lesson.title)
                    .font(.system(.body, design: .serif, weight: .semibold))
                    .foregroundStyle(Color.dlInk)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 8)

                // Metadata row
                HStack(spacing: 12) {
                    Label("\(lesson.sentenceCount)", systemImage: "text.bubble")
                        .font(.system(.caption))
                        .foregroundStyle(Color.dlInkSecondary)
                    Label("~\(lesson.estimatedMinutes) phút", systemImage: "clock")
                        .font(.system(.caption))
                        .foregroundStyle(Color.dlInkSecondary)
                    Spacer()
                }
                .padding(.bottom, 10)

                // Progress bar (only in-progress lessons)
                if lesson.inProgress {
                    TealProgressBar(value: Double(lesson.progressPercent) / 100.0)
                        .padding(.bottom, 0)
                }
            }
            .padding(16)
            .background(Color.dlSurface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        lesson.inProgress ? Color.dlTeal.opacity(0.3) : Color.dlBorder.opacity(0.5),
                        lineWidth: lesson.inProgress ? 1.5 : 1
                    )
            )
            .shadow(color: Color.dlInk.opacity(0.04), radius: 6, x: 0, y: 2)
        }
        .buttonStyle(LessonCardButtonStyle())
        .disabled(disabled)
    }
}

struct LessonCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.975 : 1.0)
            .opacity(configuration.isPressed ? 0.85 : 1.0)
            .animation(.spring(response: 0.2, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

// MARK: - Skeleton Card

struct LessonCardSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                SkeletonShape().frame(width: 36, height: 20).cornerRadius(10)
                Spacer()
            }
            SkeletonRect(height: 18, widthFraction: 0.85)
            SkeletonRect(height: 14, widthFraction: 0.55)
            SkeletonRect(height: 3, cornerRadius: 2)
        }
        .padding(16)
        .background(Color.dlSurface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.dlBorder.opacity(0.4), lineWidth: 1))
    }
}

// MARK: - HistoryView (preserved logic, updated styling)

struct HistoryView: View {
    @EnvironmentObject private var api: APIClient
    @State private var items: [HistoryEntry] = []
    @State private var nextPage = 0
    @State private var totalPages = 1
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ZStack { Color.dlBackground.ignoresSafeArea()
            List {
                if let error {
                    ErrorNotice(message: error)
                    Button("Thử lại") { Task { await load(reset: false) } }
                }
                if items.isEmpty, !busy, error == nil {
                    Text("Hoàn thành bài nghe đầu tiên để xem lịch sử.")
                        .foregroundStyle(Color.dlInkSecondary)
                }
                ForEach(items) { item in
                    NavigationLink { ResultLoader(sessionID: item.sessionId) } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                LevelBadge(level: item.level)
                                Spacer()
                                Text(historyDate(item.completedAt))
                                    .font(.caption).foregroundStyle(Color.dlInkSecondary)
                            }
                            Text(item.lessonTitle).font(.system(.subheadline, design: .serif, weight: .semibold))
                                .foregroundStyle(Color.dlInk)
                            Text("Đúng lần đầu \(item.firstAttemptAccuracy.formatted(.number.precision(.fractionLength(1))))%")
                                .font(.caption).foregroundStyle(Color.dlInkSecondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
                if busy {
                    HStack { Spacer(); ProgressView().tint(Color.dlTeal); Spacer() }
                } else if nextPage < totalPages {
                    Button("Xem thêm") { Task { await load(reset: false) } }
                        .foregroundStyle(Color.dlInk)
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle("Lịch sử")
            .navigationBarTitleDisplayMode(.inline)
            .task { await load(reset: true) }
            .refreshable { await load(reset: true) }
        }
    }

    private func historyDate(_ raw: String) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = f.date(from: raw) ?? ISO8601DateFormatter().date(from: raw)
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

// MARK: - ResultLoader + ResultView (preserved logic, minimal style update)

struct ResultLoader: View {
    @EnvironmentObject private var api: APIClient
    let sessionID: String
    @State private var result: LessonResult?
    @State private var error: String?

    var body: some View {
        Group {
            if let result { ResultView(result: result) }
            else if let error {
                VStack(spacing: 12) {
                    ErrorNotice(message: error)
                    Button("Thử lại") { Task { await load() } }
                        .foregroundStyle(Color.dlInk)
                }.padding()
            } else {
                ProgressView().tint(Color.dlTeal)
            }
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
        ZStack { Color.dlBackground.ignoresSafeArea()
            List {
                Section {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.dlInk)
                        Text("Hoàn thành").font(.system(.subheadline, weight: .semibold)).foregroundStyle(Color.dlInk)
                    }
                    Text(result.lessonTitle)
                        .font(.system(.title3, design: .serif, weight: .bold)).foregroundStyle(Color.dlInk)
                    Text("\(result.level) · \(result.practiceSeconds / 60) phút \(result.practiceSeconds % 60) giây")
                        .foregroundStyle(Color.dlInkSecondary)
                }
                Section("Kết quả") {
                    MetricRow(title: "Đúng lần đầu", value: result.firstAttemptAccuracy, suffix: "%")
                    MetricRow(title: "Đúng cuối cùng", value: result.finalAccuracy, suffix: "%")
                    MetricRow(title: "Điểm tổng", value: result.overallScore, suffix: "/100")
                }
                if let review = result.review {
                    Section("Xem lại câu đã nghe") {
                        ForEach(review.sentences) { s in
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Câu \(s.position)").font(.caption).foregroundStyle(Color.dlInkSecondary)
                                Text(s.transcript).textSelection(.enabled).font(.system(.subheadline, design: .serif))
                            }.padding(.vertical, 4)
                        }
                    }
                    if let src = review.sourceName {
                        Section { Text("Nguồn: \(src)").font(.caption).foregroundStyle(Color.dlInkSecondary) }
                    }
                }
            }
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Kết quả").navigationBarTitleDisplayMode(.inline)
    }
}

struct MetricRow: View {
    let title: String; let value: Double; let suffix: String
    var body: some View {
        HStack {
            Text(title).foregroundStyle(Color.dlInkSecondary)
            Spacer()
            Text(value.formatted(.number.precision(.fractionLength(1))) + suffix)
                .bold().monospacedDigit().foregroundStyle(Color.dlInk)
        }
    }
}

// MARK: - ProgressScreen

struct ProgressScreen: View {
    @EnvironmentObject private var api: APIClient
    @State private var dashboard: Dashboard?
    @State private var error: String?

    var body: some View {
        ZStack { Color.dlBackground.ignoresSafeArea()
            List {
                if let error { ErrorNotice(message: error); Button("Thử lại") { Task { await load() } }.foregroundStyle(Color.dlInk) }
                if let d = dashboard {
                    Section("Thói quen học tập") {
                        MetricRow(title: "Bài hoàn thành", value: Double(d.summary.completedLessons), suffix: "")
                        MetricRow(title: "Phút luyện nghe", value: Double(d.summary.learningMinutes), suffix: "")
                        MetricRow(title: "Đúng lần đầu", value: d.summary.averageFirstAttemptAccuracy, suffix: "%")
                        MetricRow(title: "Điểm trung bình", value: d.summary.averageScore, suffix: "/100")
                    }
                    if let a = d.assessment {
                        Section("Năng lực nghe") {
                            if let lvl = a.estimatedLevel {
                                Text(lvl).font(.system(.largeTitle, design: .serif, weight: .bold)).foregroundStyle(Color.dlInk)
                            }
                            Text(a.summary)
                            if a.provisional {
                                Text("Ước lượng tạm thời; cần thêm bài học để tăng độ tin cậy.")
                                    .font(.caption).foregroundStyle(Color.dlInkSecondary)
                            }
                        }
                    }
                } else if error == nil {
                    HStack { Spacer(); ProgressView().tint(Color.dlTeal); Spacer() }
                }
            }
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Tiến bộ").task { await load() }.refreshable { await load() }
    }

    private func load() async {
        error = nil
        do { dashboard = try await api.get("/api/v1/dashboard") }
        catch { self.error = error.localizedDescription }
    }
}

// MARK: - AccountView (preserved, light style update)

struct AccountView: View {
    @EnvironmentObject private var api: APIClient
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ZStack { Color.dlBackground.ignoresSafeArea()
            List {
                Section("Tài khoản học tập") {
                    Text(api.account?.displayName ?? "").foregroundStyle(Color.dlInk)
                    Text(api.account?.email ?? "").foregroundStyle(Color.dlInkSecondary)
                }
                Section("DictaLab cho iPhone") {
                    Text("Giao diện SwiftUI · phiên bản 1.2")
                    Text("Bài nghe, chấm từ, lịch sử và tiến bộ dùng chung dữ liệu với website.")
                    Text("Bản đầu tập trung luyện chép chính tả. Nghe hiểu, Conversation, Coach và phòng học cùng bạn chưa có giao diện iOS.")
                        .font(.footnote).foregroundStyle(Color.dlInkSecondary)
                    Link("Quyền riêng tư", destination: api.baseURL.appendingPathComponent("privacy"))
                        .foregroundStyle(Color.dlInk)
                    Link("Hỗ trợ", destination: api.baseURL.appendingPathComponent("help"))
                        .foregroundStyle(Color.dlInk)
                }
                Section {
                    if let error { ErrorNotice(message: error) }
                    Button("Đăng xuất", role: .destructive) {
                        Task {
                            busy = true; error = nil
                            defer { busy = false }
                            do { try await api.logout() }
                            catch { self.error = error.localizedDescription }
                        }
                    }.disabled(busy)
                }
            }
            .scrollContentBackground(.hidden)
        }
        .navigationTitle("Tài khoản")
    }
}
