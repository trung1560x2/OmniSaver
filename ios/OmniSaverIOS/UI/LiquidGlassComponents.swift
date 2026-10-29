import SwiftUI
import UIKit
import AVKit

// MARK: - Platform Visual Theme Helpers

extension SocialPlatform {
    var accentColor: Color {
        switch self {
        case .facebook:
            return Color(red: 0.23, green: 0.51, blue: 0.96) // #3B82F6
        case .instagram:
            return Color(red: 0.96, green: 0.45, blue: 0.71) // #F472B6
        case .tiktok:
            return Color(red: 0.13, green: 0.83, blue: 0.93) // #22D3EE
        case .xTwitter:
            return Color(red: 0.58, green: 0.64, blue: 0.72) // #94A3B8
        case .threads:
            return Color(red: 0.66, green: 0.55, blue: 0.98) // #A78BFA
        case .unknown:
            return Color(red: 0.22, green: 0.74, blue: 0.97) // #38BDF8
        }
    }

    var sfSymbolName: String {
        switch self {
        case .facebook:
            return "person.2.wave.2.fill"
        case .instagram:
            return "camera.aperture"
        case .tiktok:
            return "music.note.tv.fill"
        case .xTwitter:
            return "bubble.left.and.text.bubble.right.fill"
        case .threads:
            return "at.circle.fill"
        case .unknown:
            return "globe"
        }
    }
}

// MARK: - iOS 27 Liquid Glass Background View

struct LiquidGlassBackgroundView: View {
    @State private var animateOrbs = false

    var body: some View {
        ZStack {
            // Deep-space dark #040711 base
            Color(red: 0.016, green: 0.027, blue: 0.067)
                .ignoresSafeArea()

            GeometryReader { geo in
                let width = geo.size.width
                let height = geo.size.height

                ZStack {
                    // Cyan Aurora Orb (#38BDF8)
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [
                                    Color(red: 0.22, green: 0.74, blue: 0.97).opacity(0.42),
                                    Color(red: 0.22, green: 0.74, blue: 0.97).opacity(0.02)
                                ],
                                center: .center,
                                startRadius: 10,
                                endRadius: width * 0.48
                            )
                        )
                        .frame(width: width * 0.85, height: width * 0.85)
                        .offset(
                            x: animateOrbs ? -width * 0.18 : -width * 0.28,
                            y: animateOrbs ? -height * 0.22 : -height * 0.14
                        )

                    // Violet Aurora Orb (#818CF8)
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [
                                    Color(red: 0.51, green: 0.55, blue: 0.97).opacity(0.38),
                                    Color(red: 0.51, green: 0.55, blue: 0.97).opacity(0.02)
                                ],
                                center: .center,
                                startRadius: 12,
                                endRadius: width * 0.50
                            )
                        )
                        .frame(width: width * 0.90, height: width * 0.90)
                        .offset(
                            x: animateOrbs ? width * 0.28 : width * 0.16,
                            y: animateOrbs ? -height * 0.05 : height * 0.06
                        )

                    // Magenta Aurora Orb (#F472B6)
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [
                                    Color(red: 0.96, green: 0.45, blue: 0.71).opacity(0.30),
                                    Color(red: 0.96, green: 0.45, blue: 0.71).opacity(0.01)
                                ],
                                center: .center,
                                startRadius: 10,
                                endRadius: width * 0.45
                            )
                        )
                        .frame(width: width * 0.82, height: width * 0.82)
                        .offset(
                            x: animateOrbs ? -width * 0.15 : width * 0.05,
                            y: animateOrbs ? height * 0.30 : height * 0.20
                        )

                    // Royal Blue Orb (#3B82F6)
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [
                                    Color(red: 0.23, green: 0.51, blue: 0.96).opacity(0.34),
                                    Color(red: 0.23, green: 0.51, blue: 0.96).opacity(0.01)
                                ],
                                center: .center,
                                startRadius: 8,
                                endRadius: width * 0.45
                            )
                        )
                        .frame(width: width * 0.78, height: width * 0.78)
                        .offset(
                            x: animateOrbs ? width * 0.20 : width * 0.30,
                            y: animateOrbs ? height * 0.38 : height * 0.45
                        )
                }
                .blur(radius: 65)
            }
            .ignoresSafeArea()

            // Subtle top atmospheric vignette
            LinearGradient(
                colors: [
                    Color.white.opacity(0.04),
                    Color.clear,
                    Color.black.opacity(0.38)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 9.0).repeatForever(autoreverses: true)) {
                animateOrbs = true
            }
        }
    }
}

// MARK: - iOS 27 Liquid Glass Card

struct LiquidGlassCard<Content: View>: View {
    var cornerRadius: CGFloat = 24
    var padding: CGFloat = 18
    var tintOpacity: Double = 0.62
    @ViewBuilder let content: () -> Content

    var body: some View {
        content()
            .padding(padding)
            .background(
                ZStack {
                    // Frosted blur base
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(.ultraThinMaterial)

                    // Dark translucent glass tint
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(Color(red: 0.08, green: 0.12, blue: 0.22).opacity(tintOpacity))

                    // Top specular reflection sheen
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [
                                    Color.white.opacity(0.16),
                                    Color.white.opacity(0.04),
                                    Color.clear,
                                    Color(red: 0.22, green: 0.74, blue: 0.97).opacity(0.05)
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                }
            )
            .overlay(
                // 1.2pt diagonal 4-stop specular gradient border
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            stops: [
                                .init(color: Color.white.opacity(0.48), location: 0.0),
                                .init(color: Color.cyan.opacity(0.28), location: 0.35),
                                .init(color: Color.white.opacity(0.06), location: 0.70),
                                .init(color: Color.purple.opacity(0.32), location: 1.0)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1.2
                    )
            )
            .shadow(color: Color.black.opacity(0.36), radius: 18, x: 0, y: 10)
    }
}

// MARK: - Spring Scale Button Style

struct LiquidSpringButtonStyle: ButtonStyle {
    var scaleAmount: CGFloat = 0.96

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scaleAmount : 1.0)
            .brightness(configuration.isPressed ? 0.05 : 0.0)
            .animation(.spring(response: 0.26, dampingFraction: 0.72), value: configuration.isPressed)
    }
}

// MARK: - Liquid Glass Primary Button

struct LiquidGlassPrimaryButton: View {
    let title: String
    var systemImage: String? = nil
    var isDisabled: Bool = false
    var isLoading: Bool = false
    var gradientColors: [Color] = [
        Color(red: 0.16, green: 0.72, blue: 0.98), // Cyan
        Color(red: 0.39, green: 0.44, blue: 0.96), // Indigo
        Color(red: 0.62, green: 0.36, blue: 0.96)  // Violet
    ]
    let action: () -> Void

    var body: some View {
        Button(action: {
            guard !isDisabled && !isLoading else { return }
            action()
        }) {
            HStack(spacing: 10) {
                if isLoading {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        .scaleEffect(0.9)
                } else if let systemImage = systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 16, weight: .bold))
                }

                Text(title)
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .padding(.horizontal, 16)
            .background(
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: isDisabled
                                    ? [Color.gray.opacity(0.32), Color.gray.opacity(0.22)]
                                    : gradientColors,
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )

                    // Top inner specular highlight
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [
                                    Color.white.opacity(isDisabled ? 0.05 : 0.28),
                                    Color.clear
                                ],
                                startPoint: .top,
                                endPoint: .center
                            )
                        )
                }
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [
                                Color.white.opacity(isDisabled ? 0.14 : 0.62),
                                Color.cyan.opacity(isDisabled ? 0.08 : 0.34),
                                Color.white.opacity(0.12)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1.1
                    )
            )
            .shadow(
                color: isDisabled ? .clear : gradientColors.first!.opacity(0.36),
                radius: 12,
                x: 0,
                y: 6
            )
        }
        .buttonStyle(LiquidSpringButtonStyle())
        .disabled(isDisabled || isLoading)
    }
}

// MARK: - Liquid Glass Pill Button

struct LiquidGlassPillButton: View {
    let title: String
    var systemImage: String? = nil
    var isSelected: Bool = false
    var statusDotColor: Color? = nil
    var tintColor: Color = Color(red: 0.22, green: 0.74, blue: 0.97)
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage = systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(isSelected ? .white : tintColor)
                }

                Text(title)
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundColor(.white.opacity(isSelected ? 1.0 : 0.90))
                    .lineLimit(1)

                if let dotColor = statusDotColor {
                    Circle()
                        .fill(dotColor)
                        .frame(width: 7, height: 7)
                        .shadow(color: dotColor.opacity(0.8), radius: 4, x: 0, y: 0)
                }
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 8)
            .background(
                ZStack {
                    Capsule(style: .continuous)
                        .fill(.ultraThinMaterial)

                    Capsule(style: .continuous)
                        .fill(
                            isSelected
                                ? tintColor.opacity(0.36)
                                : Color(red: 0.10, green: 0.15, blue: 0.28).opacity(0.65)
                        )

                    Capsule(style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [Color.white.opacity(0.18), Color.clear],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                }
            )
            .overlay(
                Capsule(style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [
                                isSelected ? tintColor.opacity(0.90) : Color.white.opacity(0.38),
                                isSelected ? Color.white.opacity(0.45) : tintColor.opacity(0.22),
                                Color.white.opacity(0.10)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: isSelected ? 1.3 : 1.0
                    )
            )
        }
        .buttonStyle(LiquidSpringButtonStyle(scaleAmount: 0.94))
    }
}

// MARK: - Liquid Glass Badge

struct LiquidGlassBadge: View {
    let text: String
    var systemImage: String? = nil
    var color: Color = Color(red: 0.16, green: 0.85, blue: 0.60)

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage = systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 10, weight: .bold))
            }
            Text(text)
                .font(.system(size: 10, weight: .heavy, design: .rounded))
        }
        .foregroundColor(.white)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            Capsule(style: .continuous)
                .fill(color.opacity(0.28))
        )
        .overlay(
            Capsule(style: .continuous)
                .strokeBorder(color.opacity(0.75), lineWidth: 1.0)
        )
    }
}

// MARK: - Share Sheet (UIActivityViewController)

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - Full HD Video Player Sheet (AVPlayerViewController)

struct VideoPlayerSheet: View {
    let url: URL
    var title: String = "Xem Video Full HD"
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?
    @State private var showShareSheet = false

    var body: some View {
        ZStack {
            LiquidGlassBackgroundView()

            VStack(spacing: 14) {
                // Header bar
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                            .lineLimit(1)
                        Text(url.lastPathComponent)
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundColor(.white.opacity(0.65))
                            .lineLimit(1)
                    }

                    Spacer()

                    LiquidGlassPillButton(
                        title: "Chia sẻ",
                        systemImage: "square.and.arrow.up",
                        tintColor: .cyan
                    ) {
                        showShareSheet = true
                    }

                    LiquidGlassPillButton(
                        title: "Đóng",
                        systemImage: "xmark",
                        tintColor: .pink
                    ) {
                        player?.pause()
                        dismiss()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)

                // AVPlayer container
                AVPlayerControllerRepresentable(player: player)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.25), lineWidth: 1)
                    )
                    .padding(.horizontal, 12)
                    .padding(.bottom, 16)
            }
        }
        .onAppear {
            let avPlayer = AVPlayer(url: url)
            self.player = avPlayer
            avPlayer.play()
        }
        .onDisappear {
            player?.pause()
            player = nil
        }
        .sheet(isPresented: $showShareSheet) {
            ShareSheet(items: [url])
        }
    }
}

private struct AVPlayerControllerRepresentable: UIViewControllerRepresentable {
    let player: AVPlayer?

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.entersFullScreenWhenPlaybackBegins = false
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {
        if uiViewController.player !== player {
            uiViewController.player = player
        }
    }
}
