import SwiftUI

@main
struct DictaLabApp: App {
    @StateObject private var api: APIClient

    init() {
        let address = Bundle.main.object(forInfoDictionaryKey: "DictaLabServerURL") as? String ?? ""
        guard let url = URL(string: address), url.scheme == "https", url.host != nil,
              url.user == nil, url.password == nil else { fatalError("DictaLabServerURL must be an HTTPS origin") }
        _api = StateObject(wrappedValue: APIClient(baseURL: url))
    }

    var body: some Scene {
        WindowGroup { RootView().environmentObject(api).tint(Color.accentColor).preferredColorScheme(.light) }
    }
}

struct RootView: View {
    @EnvironmentObject private var api: APIClient
    @State private var loading = true
    @State private var error: String?
    @State private var splashDone = false

    var body: some View {
        ZStack {
            Color.dlBackground.ignoresSafeArea()

            if !splashDone {
                SplashView()
                    .transition(.opacity)
                    .onAppear {
                        Task {
                            await restore()
                            withAnimation(.easeOut(duration: 0.4)) { splashDone = true }
                        }
                    }
            } else if loading {
                // Brief secondary load (should be instant)
                VStack(spacing: 12) {
                    WaveformVisualizer(isPlaying: true)
                    Text("Đang kết nối…")
                        .font(.system(.footnote)).foregroundStyle(Color.dlInkSecondary)
                }
            } else if api.account != nil {
                MainTabView()
                    .transition(.opacity)
            } else {
                AuthView()
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.35), value: splashDone)
        .animation(.easeInOut(duration: 0.35), value: api.account != nil)
        .alert("Chưa thể kết nối", isPresented: Binding(
            get: { error != nil },
            set: { if !$0 { error = nil } }
        )) {
            Button("Thử lại") { Task { await restore() } }
            Button("Đóng", role: .cancel) { }
        } message: { Text(error ?? "") }
    }

    private func restore() async {
        loading = true
        defer { loading = false }
        do { try await api.restore() }
        catch { self.error = error.localizedDescription }
    }
}

// MARK: - Splash Screen

struct SplashView: View {
    @State private var playing = false
    @State private var opacity: Double = 0

    var body: some View {
        VStack(spacing: 16) {
            WaveformVisualizer(isPlaying: playing, color: .dlTeal, barCount: 7)
                .frame(width: 80, height: 48)

            Text("DictaLab")
                .font(.system(.title, design: .serif, weight: .bold))
                .foregroundStyle(Color.dlInk)

            Text("Một câu mỗi lần. Tiến bộ mỗi ngày.")
                .font(.system(.footnote))
                .foregroundStyle(Color.dlInkSecondary)
        }
        .opacity(opacity)
        .onAppear {
            withAnimation(.easeIn(duration: 0.4)) { opacity = 1 }
            withAnimation(.easeIn(duration: 0.3).delay(0.2)) { playing = true }
        }
    }
}

struct AuthView: View {
    @EnvironmentObject private var api: APIClient
    @State private var registering = false
    @State private var name = ""
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("DictaLab", systemImage: "waveform").font(.largeTitle.bold()).foregroundStyle(Color.accentColor)
                    Text("Một câu mỗi lần. Tiến bộ mỗi ngày.").foregroundStyle(.secondary)
                }
                Section(registering ? "Tạo tài khoản" : "Chào mừng trở lại") {
                    if registering {
                        TextField("Tên hiển thị", text: $name).textContentType(.name)
                    }
                    TextField("Email", text: $email)
                        .keyboardType(.emailAddress).textContentType(.username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Mật khẩu", text: $password)
                        .textContentType(registering ? .newPassword : .password)
                    if registering { Text("Từ 8 ký tự, có chữ cái và chữ số.").font(.caption).foregroundStyle(.secondary) }
                    if let error { ErrorNotice(message: error) }
                    Button { Task { await submit() } } label: {
                        HStack {
                            Text(registering ? "Tạo tài khoản" : "Đăng nhập")
                            Spacer()
                            if busy { ProgressView() } else { Image(systemName: "arrow.right") }
                        }
                    }.disabled(busy || email.isEmpty || password.isEmpty || (registering && name.trimmingCharacters(in: .whitespaces).count < 2))
                }
                Section {
                    Button(registering ? "Đã có tài khoản? Đăng nhập" : "Chưa có tài khoản? Đăng ký") {
                        registering.toggle(); password = ""; error = nil
                    }.disabled(busy)
                    Link("Quên mật khẩu?", destination: api.baseURL.appendingPathComponent("reset-password"))
                } footer: {
                    Text("Dùng tài khoản DictaLab hiện có. Lịch sử học được đồng bộ với website. Nếu trước đây đăng nhập bằng Google, hãy đặt mật khẩu qua liên kết Quên mật khẩu.")
                }
            }.navigationTitle("Luyện nghe tiếng Anh").navigationBarTitleDisplayMode(.inline)
        }
    }

    private func submit() async {
        busy = true; error = nil
        defer { busy = false }
        do {
            let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
            if registering { try await api.register(name: name.trimmingCharacters(in: .whitespacesAndNewlines), email: address, password: password) }
            else { try await api.login(email: address, password: password) }
            password = ""
        } catch { self.error = error.localizedDescription }
    }
}

struct ErrorNotice: View {
    let message: String
    var body: some View {
        Label(message, systemImage: "exclamationmark.circle")
            .font(.callout).foregroundStyle(.red).accessibilityAddTraits(.updatesFrequently)
    }
}
