import XCTest
import Security
@testable import DictaLab

final class SessionVaultTests: XCTestCase {
    func testSignedAppCanPersistAndClearSessionInKeychain() throws {
        let origin = "https://keychain-test-\(UUID().uuidString).invalid/"
        defer {
            SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: "com.dictalab.ios.session",
                kSecAttrAccount as String: origin] as CFDictionary)
        }
        XCTAssertTrue(try SessionVault.load(for: origin).isEmpty)
        let cookie = try XCTUnwrap(HTTPCookie(properties: [
            .name: "DICTALAB_ACCESS", .value: "test-session", .domain: "dictalab.example",
            .path: "/", .expires: Date().addingTimeInterval(3600), .secure: "TRUE"]))
        try SessionVault.save([cookie], for: origin)
        let saved = try SessionVault.load(for: origin)
        XCTAssertEqual(saved.count, 1)
        XCTAssertEqual(saved.first?.value, "test-session")
        XCTAssertEqual(saved.first?.cookie?.isSecure, true)
        try SessionVault.save([], for: origin)
        XCTAssertTrue(try SessionVault.load(for: origin).isEmpty)
    }
}

final class AudioSegmentTests: XCTestCase {
    func testSentenceBoundariesAreClampedToFileEnd() throws {
        let segment = try AudioSegment.resolve(start: 2.5, end: 12, duration: 10)
        XCTAssertEqual(segment.start, 2.5)
        XCTAssertEqual(segment.end, 10)
    }

    func testInvalidBoundariesNeverFallBackToPlayingTheEntireConversation() {
        for (start, end) in [(-1.0, 2.0), (4.0, 3.0), (10.0, 11.0), (.nan, 2.0), (1.0, .infinity)] {
            XCTAssertThrowsError(try AudioSegment.resolve(start: start, end: end, duration: 10))
        }
        XCTAssertThrowsError(try AudioSegment.resolve(start: 1, end: nil, duration: 10))
        XCTAssertThrowsError(try AudioSegment.resolve(start: nil, end: nil, duration: 0))
    }

    func testWholeFileOnlyWhenNoSegmentWasSupplied() throws {
        let segment = try AudioSegment.resolve(start: nil, end: nil, duration: 8)
        XCTAssertEqual(segment.start, 0)
        XCTAssertEqual(segment.end, 8)
    }
}

final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, [String: String], Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            guard let handler = Self.handler else { throw URLError(.badServerResponse) }
            let (status, headers, body) = try handler(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}

    static func body(_ request: URLRequest) -> Data {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(contentsOf: buffer.prefix(count))
        }
        return data
    }
}

@MainActor
final class APIClientTests: XCTestCase {
    private let origin = URL(string: "https://dictalab.example/")!
    private let accountJSON = Data(#"{"id":1,"email":"learner@example.com","displayName":"Learner"}"#.utf8)
    private let csrfJSON = Data(#"{"headerName":"X-XSRF-TOKEN","token":"safe-test-token"}"#.utf8)

    private func makeClient() -> APIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        return APIClient(baseURL: origin, configuration: configuration, persistSession: false)
    }

    func testLoginUsesCSRFAndDecodesAccount() async throws {
        let account = accountJSON
        let csrf = csrfJSON
        var paths: [String] = []
        MockURLProtocol.handler = { request in
            paths.append(request.url!.path)
            if request.url!.path == "/api/auth/csrf" { return (200, [:], csrf) }
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-XSRF-TOKEN"), "safe-test-token")
            let body = try JSONDecoder().decode(TestCredentials.self, from: MockURLProtocol.body(request))
            XCTAssertEqual(body.email, "learner@example.com")
            XCTAssertEqual(body.password, "example-password")
            return (200, [:], account)
        }
        defer { MockURLProtocol.handler = nil }
        let api = makeClient()
        try await api.login(email: "learner@example.com", password: "example-password")
        XCTAssertEqual(api.account?.id, 1)
        XCTAssertEqual(paths, ["/api/auth/csrf", "/api/auth/login"])
    }

    func testOnlyExplicitCSRFRejectionIsRetriedWithSameOperationKey() async throws {
        let csrf = csrfJSON
        let account = accountJSON
        var keys: [String?] = []
        var tokenFetches = 0
        MockURLProtocol.handler = { request in
            if request.url!.path == "/api/auth/csrf" { tokenFetches += 1; return (200, [:], csrf) }
            keys.append(request.value(forHTTPHeaderField: "Idempotency-Key"))
            if keys.count == 1 { return (403, ["X-CSRF-Error":"1"], Data()) }
            return (200, [:], account)
        }
        defer { MockURLProtocol.handler = nil }
        try await makeClient().login(email: "learner@example.com", password: "example-password")
        XCTAssertEqual(tokenFetches, 2)
        XCTAssertEqual(keys.count, 2)
        XCTAssertNotNil(keys[0])
        XCTAssertEqual(keys[0], keys[1])
    }

    func testAuthorizationFailureIsNotReplayed() async throws {
        let csrf = csrfJSON
        var mutations = 0
        MockURLProtocol.handler = { request in
            if request.url!.path == "/api/auth/csrf" { return (200, [:], csrf) }
            mutations += 1
            return (403, [:], Data(#"{"message":"Forbidden"}"#.utf8))
        }
        defer { MockURLProtocol.handler = nil }
        do { try await makeClient().login(email: "learner@example.com", password: "example-password"); XCTFail("Expected 403") }
        catch let error as APIStatus { XCTAssertEqual(error.status, 403) }
        XCTAssertEqual(mutations, 1)
    }

    func testExpiredSessionRefreshesAndRetriesTheProtectedRead() async throws {
        let csrf = csrfJSON
        let account = accountJSON
        var reads = 0
        var refreshes = 0
        MockURLProtocol.handler = { request in
            switch request.url!.path {
            case "/api/auth/csrf": return (200, [:], csrf)
            case "/api/auth/refresh":
                refreshes += 1
                XCTAssertEqual(request.value(forHTTPHeaderField: "X-XSRF-TOKEN"), "safe-test-token")
                return (204, [:], Data())
            default:
                reads += 1
                return reads == 1 ? (401, [:], Data()) : (200, [:], account)
            }
        }
        defer { MockURLProtocol.handler = nil }
        let user: Account = try await makeClient().get("/api/auth/me")
        XCTAssertEqual(user.id, 1)
        XCTAssertEqual(reads, 2)
        XCTAssertEqual(refreshes, 1)
    }

    func testRejectsForeignOriginAndHTTPBeforeSendingCredentials() async throws {
        var requests = 0
        MockURLProtocol.handler = { _ in requests += 1; return (200, [:], Data()) }
        defer { MockURLProtocol.handler = nil }
        let api = makeClient()
        for path in ["https://other.example/api/auth/me", "http://dictalab.example/api/auth/me"] {
            do { let _: Account = try await api.get(path); XCTFail("Expected origin rejection") }
            catch is AppError { }
        }
        XCTAssertEqual(requests, 0)
    }

    func testLostMutationResponseReusesBodyAndKeyAcrossRetry() async throws {
        let csrf = csrfJSON
        let session = try JSONDecoder().decode(PracticeSession.self, from: Data(Self.sessionJSON.utf8))
        let sentence = Data(Self.sentenceJSON.utf8)
        var attempts: [(String?, Data)] = []
        MockURLProtocol.handler = { request in
            if request.url!.path == "/api/auth/csrf" { return (200, [:], csrf) }
            attempts.append((request.value(forHTTPHeaderField: "Idempotency-Key"), MockURLProtocol.body(request)))
            if attempts.count == 1 { throw URLError(.networkConnectionLost) }
            return (200, [:], sentence)
        }
        defer { MockURLProtocol.handler = nil }
        let suiteName = "DictaLabTests." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let api = makeClient()
        let firstStore = PracticeStore(session: session, api: api, defaults: defaults)
        firstStore.answers[0] = "hello"
        await firstStore.check(session.sentence!.words[0])
        XCTAssertNotNil(firstStore.error)
        // Simulate reopening the app. The response-time field changes, but the request must not.
        let reopenedStore = PracticeStore(session: session, api: api, defaults: defaults)
        reopenedStore.answers[0] = "hello"
        await reopenedStore.check(session.sentence!.words[0])
        XCTAssertNil(reopenedStore.error)
        XCTAssertEqual(attempts.count, 2)
        XCTAssertNotNil(attempts[0].0)
        XCTAssertEqual(attempts[0].0, attempts[1].0)
        XCTAssertEqual(attempts[0].1, attempts[1].1)
    }

    private struct TestCredentials: Decodable { let email: String; let password: String }
    private static let sentenceJSON = #"{"sentenceId":1,"position":1,"totalSentences":2,"words":[{"index":0,"length":5,"mask":"_____","userInput":"hello","correct":true,"revealed":false,"prefilled":false,"classificationAvailable":false}],"speechText":null,"audioUrl":"/audio/test.mp3","audioStartSeconds":0.5,"audioEndSeconds":1.5,"speechRate":0.8,"replayCount":1,"hintCount":0,"revealCount":0,"hasListened":true,"requiresReplayAfterReveal":false,"canComplete":true}"#
    private static let sessionJSON = "{\"sessionId\":\"test-session\",\"lessonId\":1,\"lessonTitle\":\"Test\",\"level\":\"A1\",\"sentence\":" + sentenceJSON + "}"
}
