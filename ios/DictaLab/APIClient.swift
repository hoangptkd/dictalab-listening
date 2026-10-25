import Foundation
import Combine
import Security

// Cookies stay in an ephemeral URLSession; only authentication cookies enter Keychain.
enum SessionVault {
    private static let service = "com.dictalab.ios.session"

    struct SavedCookie: Codable {
        let name: String
        let value: String
        let domain: String
        let path: String
        let expires: Date
        let secure: Bool

        var cookie: HTTPCookie? {
            HTTPCookie(properties: [.name: name, .value: value, .domain: domain,
                .path: path, .expires: expires, .secure: secure ? "TRUE" : "FALSE"])
        }
    }

    static func load(for origin: String) throws -> [SavedCookie] {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: origin,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let data = result as? Data else {
            throw AppError(message: "Không thể mở phiên đăng nhập trong Keychain. Hãy mở khóa iPhone và thử lại.")
        }
        return try JSONDecoder().decode([SavedCookie].self, from: data)
    }

    static func save(_ cookies: [HTTPCookie], for origin: String) throws {
        let saved = cookies.compactMap { cookie -> SavedCookie? in
            guard ["DICTALAB_ACCESS", "DICTALAB_REFRESH"].contains(cookie.name),
                  let expires = cookie.expiresDate, expires > Date() else { return nil }
            return SavedCookie(name: cookie.name, value: cookie.value, domain: cookie.domain,
                               path: cookie.path, expires: expires, secure: cookie.isSecure)
        }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service, kSecAttrAccount as String: origin]
        let data = try JSONEncoder().encode(saved)
        var status = SecItemUpdate(query as CFDictionary, [kSecValueData: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            status = SecItemAdd(item as CFDictionary, nil)
        }
        guard status == errSecSuccess else {
            throw AppError(message: "Không thể lưu phiên đăng nhập an toàn vào Keychain.")
        }
    }
}

final class HTTPSRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        guard let target = request.url, target.scheme == "https",
              let original = task.originalRequest?.url else { completionHandler(nil); return }
        // API redirects must never carry authentication to another origin.
        if original.path.hasPrefix("/api/"), !APIClient.sameOrigin(original, target) {
            completionHandler(nil)
        } else { completionHandler(request) }
    }
}

@MainActor
final class APIClient: ObservableObject {
    @Published private(set) var account: Account?
    let baseURL: URL
    private let session: URLSession
    private let persistSession: Bool
    private var csrf: (header: String, token: String)?
    private var refreshTask: Task<Void, Error>?

    init(baseURL: URL, configuration: URLSessionConfiguration = .ephemeral, persistSession: Bool = true) {
        self.baseURL = baseURL
        self.persistSession = persistSession
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration, delegate: HTTPSRedirects(), delegateQueue: nil)
    }

    nonisolated static func sameOrigin(_ left: URL, _ right: URL) -> Bool {
        left.scheme?.lowercased() == right.scheme?.lowercased()
            && left.host?.lowercased() == right.host?.lowercased()
            && (left.port ?? 443) == (right.port ?? 443)
    }

    func restore() async throws {
        if persistSession {
            for saved in try SessionVault.load(for: baseURL.absoluteString) where saved.expires > Date() {
                if let cookie = saved.cookie { session.configuration.httpCookieStorage?.setCookie(cookie) }
            }
        }
        do { account = try await get("/api/auth/me") }
        catch let error as APIStatus where error.status == 401 { account = nil }
    }

    func login(email: String, password: String) async throws {
        let user: Account = try await post("/api/auth/login", Credentials(email: email, password: password))
        csrf = nil
        account = user
    }

    func register(name: String, email: String, password: String) async throws {
        let user: Account = try await post("/api/auth/register",
            Registration(displayName: name, email: email, password: password))
        csrf = nil
        account = user
    }

    func logout() async throws {
        _ = try await send("/api/auth/logout", method: "POST", body: JSONEncoder().encode(EmptyBody()))
        session.configuration.httpCookieStorage?.cookies?.forEach {
            session.configuration.httpCookieStorage?.deleteCookie($0)
        }
        try saveCookies()
        csrf = nil
        account = nil
    }

    func get<T: Decodable>(_ path: String) async throws -> T {
        try JSONDecoder().decode(T.self, from: await send(path))
    }

    func post<T: Decodable, B: Encodable>(_ path: String, _ body: B, idempotencyKey: String? = nil) async throws -> T {
        try await postData(path, data: JSONEncoder().encode(body), idempotencyKey: idempotencyKey)
    }

    func postData<T: Decodable>(_ path: String, data: Data, idempotencyKey: String?) async throws -> T {
        try JSONDecoder().decode(T.self, from: await send(path, method: "POST", body: data, key: idempotencyKey))
    }

    private func saveCookies() throws {
        if persistSession {
            try SessionVault.save(session.configuration.httpCookieStorage?.cookies ?? [], for: baseURL.absoluteString)
        }
    }

    private func loadCSRF() async throws {
        struct Token: Decodable { let headerName: String; let token: String }
        let (data, response) = try await raw("/api/auth/csrf", method: "GET", body: nil, key: nil)
        try validate(data, response)
        let token = try JSONDecoder().decode(Token.self, from: data)
        csrf = (token.headerName, token.token)
    }

    private func refresh() async throws {
        if let task = refreshTask { return try await task.value }
        let task = Task { @MainActor in
            try await self.loadCSRF()
            var reply = try await self.raw("/api/auth/refresh", method: "POST", body: Data("{}".utf8), key: nil)
            if reply.1.statusCode == 403, reply.1.value(forHTTPHeaderField: "X-CSRF-Error") == "1" {
                try await self.loadCSRF()
                reply = try await self.raw("/api/auth/refresh", method: "POST", body: Data("{}".utf8), key: nil)
            }
            try self.validate(reply.0, reply.1)
            self.csrf = nil
        }
        refreshTask = task
        defer { refreshTask = nil }
        do { try await task.value }
        catch let error as APIStatus where error.status == 401 {
            account = nil
            throw error
        }
    }

    private func send(_ path: String, method: String = "GET", body: Data? = nil, key suppliedKey: String? = nil) async throws -> Data {
        let key = method == "POST" ? (suppliedKey ?? UUID().uuidString) : nil
        var retriedCSRF = false
        var refreshed = false
        while true {
            if method != "GET", csrf == nil { try await loadCSRF() }
            let (data, response) = try await raw(path, method: method, body: body, key: key)
            if response.statusCode == 403, response.value(forHTTPHeaderField: "X-CSRF-Error") == "1", !retriedCSRF {
                retriedCSRF = true
                try await loadCSRF()
                continue
            }
            if response.statusCode == 401, !path.hasPrefix("/api/auth/") || path == "/api/auth/me", !refreshed {
                refreshed = true
                try await refresh()
                continue
            }
            try validate(data, response)
            return data
        }
    }

    private func raw(_ path: String, method: String, body: Data?, key: String?) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL,
              Self.sameOrigin(baseURL, url), url.scheme == "https", url.user == nil, url.password == nil else {
            throw AppError(message: "Địa chỉ API không hợp lệ hoặc không dùng HTTPS.")
        }
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.httpMethod = method
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if method != "GET", let csrf { request.setValue(csrf.token, forHTTPHeaderField: csrf.header) }
        if let key { request.setValue(key, forHTTPHeaderField: "Idempotency-Key") }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        try saveCookies()
        return (data, http)
    }

    private func validate(_ data: Data, _ response: HTTPURLResponse) throws {
        guard (200..<300).contains(response.statusCode) else {
            let message = (try? JSONDecoder().decode(ServerError.self, from: data).message)
                ?? "Máy chủ trả lỗi \(response.statusCode). Vui lòng thử lại."
            throw APIStatus(status: response.statusCode, message: message)
        }
    }

    func audioData(_ path: String) async throws -> Data {
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL,
              url.scheme == "https", url.user == nil, url.password == nil else {
            throw AppError(message: "Đường dẫn audio không an toàn.")
        }
        // Media may redirect to an HTTPS S3 URL. Cookie matching stays with URLSession.
        var request = URLRequest(url: url, timeoutInterval: 60)
        request.setValue("audio/*", forHTTPHeaderField: "Accept")
        var (data, response) = try await session.data(for: request)
        if (response as? HTTPURLResponse)?.statusCode == 401, Self.sameOrigin(baseURL, url) {
            try await refresh()
            (data, response) = try await session.data(for: request)
        }
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        try validate(data, http)
        return data
    }
}

struct APIStatus: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}
