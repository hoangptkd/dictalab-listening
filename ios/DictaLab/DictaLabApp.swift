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
        WindowGroup { RootView().environmentObject(api).tint(Color.accentColor) }
    }
}

struct RootView: View {
    @EnvironmentObject private var api: APIClient
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        Group {
            if loading { ProgressView("Đang mở DictaLab…") }
            else if api.account != nil {
                TabView {
                    NavigationStack { CatalogView() }
                        .tabItem { Label("Bài nghe", systemImage: "headphones") }
                    NavigationStack { HistoryView() }
                        .tabItem { Label("Lịch sử", systemImage: "clock") }
                    NavigationStack { ProgressScreen() }
                        .tabItem { Label("Tiến bộ", systemImage: "chart.bar") }
                    NavigationStack { AccountView() }
                        .tabItem { Label("Tài khoản", systemImage: "person.crop.circle") }
                }
            } else { AuthView() }
        }
        .task { await restore() }
        .alert("Chưa thể kết nối", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
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

struct AccountView: View {
    @EnvironmentObject private var api: APIClient
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Form {
            Section("Tài khoản học tập") {
                Text(api.account?.displayName ?? "")
                Text(api.account?.email ?? "").foregroundStyle(.secondary)
            }
            Section("DictaLab cho iPhone") {
                Text("Giao diện SwiftUI · phiên bản 1.0")
                Text("Bài nghe, chấm từ, lịch sử và tiến bộ dùng chung dữ liệu với website.")
                Text("Bản đầu tập trung luyện chép chính tả. Nghe hiểu, Conversation, Coach và phòng học cùng bạn chưa có giao diện iOS.")
                    .font(.footnote).foregroundStyle(.secondary)
                Link("Quyền riêng tư", destination: api.baseURL.appendingPathComponent("privacy"))
                Link("Hỗ trợ", destination: api.baseURL.appendingPathComponent("help"))
            }
            Section {
                if let error { ErrorNotice(message: error) }
                Button("Đăng xuất", role: .destructive) {
                    Task {
                        busy = true; error = nil
                        defer { busy = false }
                        do { try await api.logout() } catch { self.error = error.localizedDescription }
                    }
                }.disabled(busy)
            }
        }.navigationTitle("Tài khoản")
    }
}
