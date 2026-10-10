import SwiftUI
import UIKit

/// The app dresses up twice a year and takes the costume off by itself: Halloween for the
/// whole of October, New Year from 1 December to 31 January. A season is a palette and one
/// quiet ornament behind the screens; layout and controls stay where they are.
enum Season: Equatable {
    case regular, halloween, newYear

    static func of(month: Int) -> Season {
        month == 10 ? .halloween : (month == 12 || month == 1) ? .newYear : .regular
    }

    /// An explicit request wins over the switch, the switch wins over the date.
    static func decide(month: Int, enabled: Bool, forced: String?) -> Season {
        switch forced?.lowercased() {
        case "halloween": return .halloween
        case "newyear": return .newYear
        case "none": return Season.regular
        default: return enabled ? of(month: month) : Season.regular
        }
    }

    /// Decided once per launch: the palette below is read into constants.
    static let current: Season = {
        let arguments = ProcessInfo.processInfo.arguments
        var forced: String?
        if let index = arguments.firstIndex(of: "-dustoreSeason"), index + 1 < arguments.count { forced = arguments[index + 1] }
        // An owned UI-test launch keeps the ordinary look unless it asks for a season,
        // so its pictures do not depend on the month.
        if forced == nil && arguments.contains("-dustoreUITest") { return Season.regular }
        let enabled = UserDefaults.standard.object(forKey: Preferences.seasonal) as? Bool ?? true
        return decide(month: Calendar.current.component(.month, from: Date()), enabled: enabled, forced: forced)
    }()

    var note: String {
        switch self {
        case .halloween: return "Сейчас: Хеллоуин — до конца октября."
        case .newYear: return "Сейчас: Новый год — по 31 января."
        case .regular: return "Сейчас: обычное оформление."
        }
    }

    func pick(_ ordinary: UInt32, _ halloween: UInt32, _ newYear: UInt32) -> UInt32 {
        switch self {
        case .halloween: return halloween
        case .newYear: return newYear
        case .regular: return ordinary
        }
    }
}

/// Shared Prime roles: obsidian surfaces, lavender selection and pale citrus actions.
/// Each role names its ordinary colour, then Halloween's, then New Year's.
enum Theme {
    static let background = Color(hex: Season.current.pick(0x111116, 0x0E0A10, 0x0A0F1A))
    static let card = Color(hex: Season.current.pick(0x1C1C25, 0x201725, 0x151E31))
    static let raised = Color(hex: Season.current.pick(0x242430, 0x2A1E31, 0x1C2740))
    static let inset = Color(hex: Season.current.pick(0x15151D, 0x150F19, 0x0E1524))
    static let line = Color(hex: Season.current.pick(0x353541, 0x45324D, 0x30405F))
    static let selection = Color(hex: Season.current.pick(0xBEB0FF, 0xB78CFF, 0x9ED8FF))
    static let action = Color(hex: Season.current.pick(0xECF39A, 0xFF9A3D, 0xFFD36B))
    static let ink = Color(hex: Season.current.pick(0x1A1C16, 0x1B1206, 0x1A1606))
    static let text = Color(hex: 0xF5F4FA)
    static let muted = Color(hex: Season.current.pick(0xC8C5D3, 0xD9CBD6, 0xC9D6EA))
    static let dim = Color(hex: Season.current.pick(0xA29EAF, 0xA38FA6, 0x8898B4))
    static let good = Color(hex: 0x8DDBB3)
    static let warn = Color(hex: 0xEDC68C)
    // Names kept for the existing web view and other native components.
    static let pink = selection
    static let yellow = action
}

private extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 255) / 255,
                  green: Double((hex >> 8) & 255) / 255,
                  blue: Double(hex & 255) / 255)
    }
}

enum Preferences {
    static let motion = "dustore.prime.motion"
    static let haptics = "dustore.prime.haptics"
    static let compactCards = "dustore.prime.compact-cards"
    static let seasonal = "dustore.seasonal-looks"
}

enum MotionPreference: String, CaseIterable, Identifiable {
    case full, reduced, off
    var id: String { rawValue }
    var title: String {
        switch self { case .full: return "Полные"; case .reduced: return "Короткие"; case .off: return "Выключены" }
    }
    func animation(reduceMotion: Bool) -> Animation? {
        if self == .off { return nil }
        if reduceMotion || self == .reduced { return .easeOut(duration: 0.14) }
        return .spring(response: 0.42, dampingFraction: 0.86, blendDuration: 0)
    }
    func moves(reduceMotion: Bool) -> Bool { self == .full && !reduceMotion }
}

struct PrimeBackdrop: View {
    var body: some View {
        ZStack(alignment: .topTrailing) {
            ZStack(alignment: .topTrailing) {
                Theme.background
                RadialGradient(colors: [Theme.selection.opacity(0.1), .clear], center: .topTrailing,
                               startRadius: 10, endRadius: 480)
            }.ignoresSafeArea()
            // Inside the safe area, so that the scene stands on the tab bar instead of behind it.
            if Season.current != .regular { SeasonSky() }
        }.accessibilityHidden(true)
    }
}

/// The season's scene behind a screen: a moonlit hill with its house, lanterns and ghosts,
/// or a snowed-in clearing with a dressed tree. It is the scene the Windows launcher draws,
/// kept as a picture; it stands in the bottom trailing corner, never moves and never takes
/// a touch.
struct SeasonSky: View {
    private static let scene: UIImage? = {
        switch Season.current {
        case .halloween: return UIImage(named: "season-halloween")
        case .newYear: return UIImage(named: "season-newyear")
        default: return nil
        }
    }()

    /// Where the scene stands on a screen of the given size: no taller than 46 % of it.
    static func place(in size: CGSize, picture: CGSize) -> CGRect {
        let height = min(size.height * 0.46, 400)
        let width = height * picture.width / max(1, picture.height)
        return CGRect(x: size.width - width, y: size.height - height, width: width, height: height)
    }

    var body: some View {
        GeometryReader { geometry in
            if let scene = SeasonSky.scene {
                let place = SeasonSky.place(in: geometry.size, picture: scene.size)
                Image(uiImage: scene).resizable().interpolation(.high)
                    .frame(width: place.width, height: place.height)
                    .position(x: place.midX, y: place.midY)
                    .opacity(0.75)   // titles lie straight on the backdrop
            }
        }.clipped().allowsHitTesting(false)
    }
}

struct DisplayTitle: View {
    let text: String
    var size: CGFloat = 30
    @ScaledMetric(relativeTo: .title) private var factor: CGFloat = 1
    var body: some View {
        Text(text)
            .font(.system(size: size * factor, weight: .bold, design: .rounded))
            .foregroundColor(Theme.text)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct Eyebrow: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.caption2.weight(.bold)).tracking(1.8)
            .foregroundColor(Theme.selection).fixedSize(horizontal: false, vertical: true)
    }
}

struct Chip: View {
    let text: String
    var color: Color = Theme.selection
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: "circle.fill").font(.system(size: 5)).accessibilityHidden(true)
            Text(text).font(.caption.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
        }
        .foregroundColor(color).padding(.horizontal, 10).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(color.opacity(0.09)))
        .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).stroke(color.opacity(0.18), lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

struct Card<Content: View>: View {
    let content: Content
    @Environment(\.colorSchemeContrast) private var contrast
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) { content }
            .padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(LinearGradient(colors: [Theme.raised, Theme.card], startPoint: .topLeading, endPoint: .bottomTrailing)))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(.white.opacity(contrast == .increased ? 0.3 : 0.075), lineWidth: 1))
    }
}

enum ButtonTone: Equatable { case primary, secondary, quiet }

struct PrimePressStyle: ButtonStyle {
    var tone: ButtonTone = .secondary
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isEnabled) private var enabled
    @AppStorage(Preferences.motion) private var motion = MotionPreference.full.rawValue
    @AppStorage(Preferences.haptics) private var haptics = true

    func makeBody(configuration: Configuration) -> some View {
        let policy = MotionPreference(rawValue: motion) ?? .full
        let pressed = configuration.isPressed && enabled
        let fill = tone == .primary ? Theme.action : tone == .secondary ? Theme.raised : Color.clear
        return configuration.label
            .font(.body.weight(.semibold))
            .foregroundColor(enabled ? (tone == .primary ? Theme.ink : Theme.text) : Theme.dim)
            .padding(.horizontal, tone == .quiet ? 10 : 18)
            .padding(.vertical, tone == .quiet ? 10 : 14)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 15, style: .continuous).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: 15, style: .continuous)
                .stroke(tone == .secondary ? Theme.line : Color.clear, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
            .scaleEffect(pressed && policy.moves(reduceMotion: reduceMotion) ? 0.974 : 1)
            .opacity(enabled ? (pressed ? 0.84 : 1) : 0.5)
            .animation(policy.animation(reduceMotion: reduceMotion), value: pressed)
            .onChange(of: configuration.isPressed) { down in
                if down && enabled && haptics { UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.6) }
            }
    }
}

struct YellowButton: View {
    let title: String
    var symbol: String = "arrow.right"
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 9) {
                Image(systemName: symbol).accessibilityHidden(true)
                Text(title).fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity)
        }.buttonStyle(PrimePressStyle(tone: .primary))
    }
}

struct EditionMark: View {
    var body: some View { Chip(text: Edition.name, color: Edition.isPrime ? Theme.selection : Theme.dim) }
}

struct BrandHeader: View {
    let title: String
    let subtitle: String
    @Environment(\.dynamicTypeSize) private var dynamicType
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            if dynamicType.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 10) {
                    brand
                    EditionMark()
                }
            } else {
                HStack(spacing: 10) {
                    brand
                    Spacer(minLength: 8)
                    EditionMark()
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                DisplayTitle(text: title, size: dynamicType.isAccessibilitySize ? 28 : 34)
                Text(subtitle).font(.subheadline).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
            }
        }.accessibilityElement(children: .contain)
    }

    private var brand: some View {
        HStack(spacing: 10) {
            Image("BrandMark").resizable().scaledToFit().frame(width: 33, height: 33).accessibilityHidden(true)
            Text("DUSTORE").font(.system(size: 17, weight: .heavy)).tracking(1.1).foregroundColor(Theme.text)
        }
    }
}

private struct PrimeArrival: ViewModifier {
    let delay: Double
    @State private var appeared = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(Preferences.motion) private var motion = MotionPreference.full.rawValue
    func body(content: Content) -> some View {
        let policy = MotionPreference(rawValue: motion) ?? .full
        return content
            .opacity(appeared ? 1 : 0)
            .offset(y: appeared || !policy.moves(reduceMotion: reduceMotion) ? 0 : 14)
            .onAppear {
                if !appeared {
                    withAnimation(policy.animation(reduceMotion: reduceMotion).map { $0.delay(delay) }) { appeared = true }
                }
            }
    }
}

extension View {
    func primeArrival(delay: Double = 0) -> some View { modifier(PrimeArrival(delay: delay)) }
}

struct EmptyLibraryArtwork: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18).fill(Theme.inset).overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.line))
                .frame(width: 104, height: 138).rotationEffect(.degrees(-11)).offset(x: -44, y: 9)
            RoundedRectangle(cornerRadius: 18).fill(Theme.inset).overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.line))
                .frame(width: 104, height: 138).rotationEffect(.degrees(11)).offset(x: 44, y: 5)
            VStack(spacing: 18) {
                Image("BrandMark").resizable().scaledToFit().frame(width: 60, height: 60)
                HStack(spacing: 6) {
                    Capsule().fill(Theme.selection.opacity(0.55)).frame(width: 35, height: 3)
                    Capsule().fill(Theme.line).frame(width: 16, height: 3)
                }
            }
            .frame(width: 114, height: 148)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Theme.raised))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.selection.opacity(0.28)))
            .shadow(color: .black.opacity(0.22), radius: 16, y: 10)
        }
        .frame(maxWidth: .infinity).frame(height: 180)
        .accessibilityHidden(true)
    }
}
