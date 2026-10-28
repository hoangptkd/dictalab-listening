// DesignSystem.swift — DictaLab Academic Editorial Light
// Shared tokens, animations, and reusable components

import SwiftUI

// MARK: - Color Tokens

extension Color {
    static let dlBackground   = Color(red: 253/255, green: 249/255, blue: 239/255) // #FDF9EF warm cream
    static let dlSurface      = Color(red: 247/255, green: 243/255, blue: 233/255) // #F7F3E9 card
    static let dlInk          = Color(red:   0/255, green:  21/255, blue:  22/255) // #001516 deep ink
    static let dlInkSecondary = Color(red:  73/255, green:  96/255, blue:  94/255) // #49605E muted
    static let dlBorder       = Color(red: 207/255, green: 198/255, blue: 183/255) // #CFC6B7 rule
    static let dlTeal         = Color(red:  66/255, green: 211/255, blue: 176/255) // #42D3B0 accent
    static let dlTealLight    = Color(red: 230/255, green: 250/255, blue: 245/255) // #E6FAF5 teal tint
}

// MARK: - Shimmer / Skeleton

struct SkeletonShape: View {
    @State private var phase: CGFloat = -1.0

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            Rectangle()
                .fill(Color.dlBorder.opacity(0.35))
                .overlay(
                    LinearGradient(
                        gradient: Gradient(stops: [
                            .init(color: .clear,                        location: 0),
                            .init(color: Color.white.opacity(0.55),     location: 0.4),
                            .init(color: .clear,                        location: 0.8)
                        ]),
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: w * 2)
                    .offset(x: phase * w * 2)
                )
                .clipped()
        }
        .onAppear {
            withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: false)) {
                phase = 1.0
            }
        }
    }
}

struct SkeletonRect: View {
    var height: CGFloat = 16
    var cornerRadius: CGFloat = 6
    var widthFraction: CGFloat = 1.0   // 0.0–1.0; uses GeometryReader to honor fraction

    var body: some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                SkeletonShape()
                    .frame(width: geo.size.width * widthFraction, height: height)
                    .cornerRadius(cornerRadius)
                Spacer(minLength: 0)
            }
        }
        .frame(height: height)
    }
}

// MARK: - Waveform Visualizer

struct WaveformVisualizer: View {
    var isPlaying: Bool
    var color: Color = .dlTeal
    var barCount: Int = 7

    // Bar amplitude pattern (relative heights)
    private let pattern: [CGFloat] = [0.6, 0.35, 1.0, 0.55, 0.75, 0.30, 0.50]

    @State private var amplitudes: [CGFloat] = Array(repeating: 0.3, count: 7)
    @State private var timer: Timer?

    var body: some View {
        HStack(alignment: .center, spacing: 5) {
            ForEach(0..<barCount, id: \.self) { i in
                RoundedRectangle(cornerRadius: 3)
                    .fill(color)
                    .frame(width: 6, height: amplitudes[i])
                    .animation(
                        isPlaying
                            ? .easeInOut(duration: Double.random(in: 0.3...0.5))
                              .repeatForever(autoreverses: true)
                              .delay(Double(i) * 0.06)
                            : .easeOut(duration: 0.3),
                        value: amplitudes[i]
                    )
            }
        }
        .frame(height: 64)
        .onChange(of: isPlaying) { playing in
            if playing { startAnimation() } else { stopAnimation() }
        }
        .onAppear {
            resetAmplitudes()
            if isPlaying { startAnimation() }
        }
        .onDisappear { stopAnimation() }
    }

    private func resetAmplitudes() {
        for i in 0..<barCount {
            amplitudes[i] = pattern[i % pattern.count] * 20
        }
    }

    private func startAnimation() {
        timer?.invalidate()
        animateOnce()
        timer = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: true) { _ in
            animateOnce()
        }
    }

    private func animateOnce() {
        for i in 0..<barCount {
            amplitudes[i] = CGFloat.random(in: 14...58) * pattern[i % pattern.count]
        }
    }

    private func stopAnimation() {
        timer?.invalidate()
        timer = nil
        withAnimation(.easeOut(duration: 0.3)) { resetAmplitudes() }
    }
}

// MARK: - Floating Tab Bar

struct FloatingTabBar: View {
    @Binding var selectedTab: Int

    private let tabs: [(icon: String, label: String)] = [
        ("headphones", "Bài nghe"),
        ("clock", "Lịch sử"),
        ("chart.bar", "Tiến bộ"),
        ("person", "Tài khoản")
    ]

    var body: some View {
        HStack(spacing: 8) {
            ForEach(tabs.indices, id: \.self) { i in
                Button {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                        selectedTab = i
                    }
                } label: {
                    Image(systemName: i == selectedTab && i != 0
                          ? tabs[i].icon + ".fill" : tabs[i].icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(i == selectedTab ? Color.dlTeal : Color.dlInkSecondary)
                        .frame(width: 52, height: 44)
                        .background(
                            i == selectedTab
                                ? Color.dlTeal.opacity(0.12)
                                : Color.clear,
                            in: RoundedRectangle(cornerRadius: 12)
                        )
                }
                .accessibilityLabel(tabs[i].label)
                .accessibilityAddTraits(i == selectedTab ? .isSelected : [])
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().stroke(Color.dlBorder.opacity(0.4), lineWidth: 1))
        .shadow(color: Color.dlInk.opacity(0.10), radius: 12, x: 0, y: 4)
    }
}

// MARK: - Level Badge Pill

struct LevelBadge: View {
    let level: String
    var body: some View {
        Text(level)
            .font(.system(size: 11, weight: .bold, design: .monospaced))
            .foregroundStyle(Color.dlTeal)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.dlTeal.opacity(0.12), in: Capsule())
            .overlay(Capsule().stroke(Color.dlTeal.opacity(0.3), lineWidth: 1))
    }
}

// MARK: - Teal Progress Bar

struct TealProgressBar: View {
    var value: Double   // 0.0 – 1.0
    var height: CGFloat = 3

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.dlBorder.opacity(0.4)).frame(height: height)
                Capsule().fill(Color.dlTeal).frame(width: geo.size.width * max(0, min(1, value)), height: height)
            }
        }
        .frame(height: height)
    }
}

// MARK: - Speed Chip Row

struct SpeedChipRow: View {
    @Binding var speed: Double
    let disabled: Bool

    private let speeds: [Double] = [0.75, 1.0, 1.25]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(speeds, id: \.self) { s in
                Button {
                    withAnimation(.spring(response: 0.25, dampingFraction: 0.8)) { speed = s }
                } label: {
                    Text(s == 1.0 ? "1×" : (s < 1.0 ? "0.75×" : "1.25×"))
                        .font(.system(size: 13, weight: speed == s ? .bold : .regular, design: .monospaced))
                        .foregroundStyle(speed == s ? Color.dlTeal : Color.dlInkSecondary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background(
                            speed == s ? Color.dlTeal.opacity(0.1) : Color.clear,
                            in: Capsule()
                        )
                        .overlay(
                            Capsule().stroke(
                                speed == s ? Color.dlTeal : Color.dlBorder.opacity(0.5),
                                lineWidth: speed == s ? 1.5 : 1
                            )
                        )
                }
                .disabled(disabled)
            }
        }
    }
}
