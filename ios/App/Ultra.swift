import SwiftUI
import UIKit

/// Smart ULTRA for the iPhone's web games (Prime).
///
/// A web game sizes its picture by the screen's pixel density: on an iPhone that is three
/// pixels per point each way, nine times the pixels of the same game on an ordinary monitor.
/// ULTRA asks the game to draw at a lower density and lets the system stretch the picture —
/// fewer pixels, more frames, a slightly softer image — and it decides how much for every
/// game separately: while the game runs, it counts the frames the page actually draws.
///
///   full density → ¾ → ½ (never below one pixel per point)
///
/// A session below 45 frames a second sends the game one step down next time. A session at
/// the display's own rate one step down is offered the sharper step once; if that turns out
/// slow again, the game settles where it was. Nothing is injected but the density and a
/// frame counter; the game's files are never touched.
enum UltraWeb {
    static let steps: [Double] = [1.0, 0.75, 0.5]
    static let slowFps = 45.0, smoothFps = 58.0
    static let fewestSamples = 20

    struct Note: Codable, Equatable {
        var step: Int
        var fps: Double
        var triedSharper: Bool
    }

    static var enabled: Bool { Edition.isPrime && (UserDefaults.standard.object(forKey: Preferences.ultra) as? Bool ?? true) }

    private static func key(_ game: UUID) -> String { "dustore.ultra.web." + game.uuidString }

    static func note(for game: UUID) -> Note? {
        guard let data = UserDefaults.standard.data(forKey: key(game)) else { return nil }
        return try? JSONDecoder().decode(Note.self, from: data)
    }

    static func remember(_ note: Note, for game: UUID) {
        if let data = try? JSONEncoder().encode(note) { UserDefaults.standard.set(data, forKey: key(game)) }
    }

    /// The step this session plays at.
    static func step(for note: Note?) -> Int { min(steps.count - 1, max(0, note?.step ?? 0)) }

    /// What to remember after a session at <paramref name="step"/>.
    static func learn(_ note: Note?, playedAt step: Int, medianFps: Double, samples: Int) -> Note {
        let before = note ?? Note(step: step, fps: 0, triedSharper: false)
        guard samples >= fewestSamples, medianFps > 0 else { return before }
        if medianFps < slowFps {
            return Note(step: min(steps.count - 1, step + 1), fps: medianFps, triedSharper: before.triedSharper)
        }
        if medianFps >= smoothFps, step > 0, !before.triedSharper {
            return Note(step: step - 1, fps: medianFps, triedSharper: true)
        }
        return Note(step: step, fps: medianFps, triedSharper: before.triedSharper)
    }

    static func median(_ values: [Double]) -> Double {
        let real = values.filter { $0 > 0 }.sorted()
        return real.isEmpty ? 0 : real[real.count / 2]
    }

    /// The density to ask for: a share of the screen's own, never below one pixel per point.
    static func density(step: Int, screenScale: CGFloat) -> Double {
        max(1.0, Double(screenScale) * steps[min(steps.count - 1, max(0, step))])
    }

    /// Runs before the game's own scripts: the density it will see, and a frame counter that
    /// reports every five seconds through the page's existing bridge.
    static func script(density: Double) -> String {
        """
        (function () {
          var ratio = \(String(format: "%.3f", density));
          try { Object.defineProperty(window, 'devicePixelRatio', { get: function () { return ratio; }, configurable: true }); } catch (e) {}
          var frames = 0, start = 0, seconds = [];
          function tick(now) {
            if (!start) start = now;
            frames++;
            if (now - start >= 1000) {
              if (document.visibilityState === 'visible') seconds.push(frames * 1000 / (now - start));
              frames = 0; start = now;
              if (seconds.length >= 5) {
                try { window.webkit.messageHandlers.ex.postMessage({ type: 'fps', values: seconds }); } catch (e) {}
                seconds = [];
              }
            }
            requestAnimationFrame(tick);
          }
          requestAnimationFrame(tick);
        })();
        """
    }
}

/// The picture above the ULTRA explanation: the same three steps as on Windows and the Mac.
struct UltraStepsView: View {
    private let steps: [(String, String, String, Double)] = [
        ("100 %", "Чётко", "все точки", 0.31),
        ("75 %", "Баланс", "на 44 % меньше точек", 0.56),
        ("50 %", "Больше кадров", "на 75 % меньше точек", 1.0)
    ]
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("УМНЫЙ ULTRA").font(.caption.weight(.bold)).foregroundColor(Theme.selection)
            HStack(spacing: 8) {
                ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(step.0).font(.headline.weight(.bold)).foregroundColor(index == 1 ? Theme.action : Theme.text)
                        Text(step.1).font(.caption.weight(.semibold)).foregroundColor(Theme.muted)
                        Text(step.2).font(.caption2).foregroundColor(Theme.dim).lineLimit(2).minimumScaleFactor(0.8)
                        GeometryReader { geometry in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Theme.line)
                                Capsule().fill(LinearGradient(colors: [Theme.selection, Theme.action], startPoint: .leading, endPoint: .trailing))
                                    .frame(width: geometry.size.width * step.3)
                            }
                        }.frame(height: 5)
                    }
                    .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(index == 1 ? Theme.selection.opacity(0.14) : Theme.inset))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(index == 1 ? Theme.selection.opacity(0.6) : Theme.line, lineWidth: 1))
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Умный ULTRA: 100 процентов — чётко, 75 — баланс, 50 — больше кадров. Медленно — ступень ниже, кадров с запасом — ступень выше.")
    }
}
