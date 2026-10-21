using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace DustoreLauncherV.Mac.Controls;

/// <summary>
/// The picture at the top of the ULTRA card, the same as the Windows launcher's: the three
/// steps smart ULTRA moves a game between, the share of pixels each one leaves out and a bar
/// for the frames that buys, with the way down and back up between them.
/// </summary>
public sealed class UltraGraphic : Control
{
    private static readonly int[] Steps = { 100, 85, 75 };
    private static readonly string[] Names = { "Чётко", "Баланс", "Больше кадров" };

    public UltraGraphic()
    {
        Height = 190;
        IsHitTestVisible = false;
    }

    private static IBrush Resource(string key, Color fallback)
        => Application.Current?.TryGetResource(key, Application.Current.ActualThemeVariant, out object? value) == true && value is IBrush brush ? brush : new SolidColorBrush(fallback);

    private static FormattedText Text(string text, double size, FontWeight weight, IBrush brush)
        => new(text, System.Globalization.CultureInfo.CurrentCulture, FlowDirection.LeftToRight, new Typeface(FontFamily.Default, FontStyle.Normal, weight), size, brush);

    public override void Render(DrawingContext context)
    {
        double width = Bounds.Width, height = Bounds.Height;
        if (width < 360) return;
        IBrush primary = Resource("TextPrimary", Color.FromRgb(0xF5, 0xF4, 0xFA));
        IBrush secondary = Resource("TextSecondary", Color.FromRgb(0xC8, 0xC5, 0xD3));
        IBrush dim = Resource("TextDim", Color.FromRgb(0xA2, 0x9E, 0xAF));
        IBrush accent = Resource("Orchid", Color.FromRgb(0xB7, 0x8C, 0xFF));
        IBrush action = Resource("Accent", Color.FromRgb(0xFF, 0x9A, 0x3D));

        context.DrawText(Text("УМНЫЙ ULTRA", 11, FontWeight.Bold, accent), new Point(0, 0));
        context.DrawText(Text("Разрешение подбирается каждой игре само", 15, FontWeight.SemiBold, primary), new Point(0, 18));

        double top = 54, cardHeight = height - top - 4, gap = 36, cardWidth = (width - gap * 2) / 3;
        var track = new SolidColorBrush(Color.FromArgb(46, 0xFF, 0xFF, 0xFF));
        var bar = new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative), EndPoint = new RelativePoint(1, 0, RelativeUnit.Relative),
            GradientStops = { new GradientStop(Color.FromRgb(0xB7, 0x8C, 0xFF), 0), new GradientStop(Color.FromRgb(0xFF, 0xD3, 0x6B), 1) }
        };
        for (int i = 0; i < 3; i++)
        {
            var card = new Rect(i * (cardWidth + gap), top, cardWidth, cardHeight);
            bool middle = i == 1;
            context.DrawRectangle(new SolidColorBrush(middle ? Color.FromArgb(58, 0xB7, 0x8C, 0xFF) : Color.FromArgb(22, 0xFF, 0xFF, 0xFF)),
                new Pen(new SolidColorBrush(middle ? Color.FromArgb(150, 0xB7, 0x8C, 0xFF) : Color.FromArgb(40, 0xFF, 0xFF, 0xFF)), 1), card, 12, 12);
            double x = card.X + 14, y = card.Y + 10;
            context.DrawText(Text(Steps[i] + " %", 22, FontWeight.Bold, middle ? action : primary), new Point(x, y));
            context.DrawText(Text(Names[i], 12, FontWeight.SemiBold, secondary), new Point(x, y + 34));
            int saved = 100 - (int)Math.Round(Steps[i] * Steps[i] / 100.0);
            context.DrawText(Text(saved == 0 ? "все точки" : "на " + saved + " % меньше точек", 11, FontWeight.Normal, dim), new Point(x, y + 54));
            var line = new Rect(x, card.Bottom - 20, card.Width - 28, 6);
            context.DrawRectangle(track, null, line, 3, 3);
            double share = (100.0 * 100 / (Steps[i] * Steps[i])) / (100.0 * 100 / (75 * 75));
            context.DrawRectangle(bar, null, new Rect(line.X, line.Y, Math.Max(line.Height, line.Width * share), line.Height), 3, 3);
            if (i < 2)
            {
                var pen = new Pen(secondary, 1.4);
                double x0 = card.Right + 6, x1 = card.Right + gap - 6, yDown = card.Y + cardHeight / 2 - 7, yUp = yDown + 14;
                context.DrawLine(pen, new Point(x0, yDown), new Point(x1, yDown));
                context.DrawLine(pen, new Point(x1 - 5, yDown - 4), new Point(x1, yDown));
                context.DrawLine(pen, new Point(x1 - 5, yDown + 4), new Point(x1, yDown));
                context.DrawLine(pen, new Point(x1, yUp), new Point(x0, yUp));
                context.DrawLine(pen, new Point(x0 + 5, yUp - 4), new Point(x0, yUp));
                context.DrawLine(pen, new Point(x0 + 5, yUp + 4), new Point(x0, yUp));
            }
        }
    }
}
