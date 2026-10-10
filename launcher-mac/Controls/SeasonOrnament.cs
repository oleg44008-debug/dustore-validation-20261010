using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;
using DustoreLauncherV.Mac.Services;

namespace DustoreLauncherV.Mac.Controls;

/// <summary>
/// The seasonal ornament, drawn behind the content and never in its way: over the pages a
/// moon with bats or still snow, at the top of the sidebar a cobweb or a string of lights.
/// It draws nothing outside a season.
/// </summary>
public sealed class SeasonOrnament : Control
{
    public static readonly StyledProperty<bool> RailProperty = AvaloniaProperty.Register<SeasonOrnament, bool>(nameof(Rail));

    /// <summary>True for the sidebar's ornament, false for the sky behind the pages.</summary>
    public bool Rail { get => GetValue(RailProperty); set => SetValue(RailProperty, value); }

    private DispatcherTimer? _twinkle;
    private int _phase;

    static SeasonOrnament() { AffectsRender<SeasonOrnament>(RailProperty); }

    public SeasonOrnament() { IsHitTestVisible = false; }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        Season.Changed += OnSeasonChanged;
        OnSeasonChanged();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        Season.Changed -= OnSeasonChanged;
        _twinkle?.Stop(); _twinkle = null;
        base.OnDetachedFromVisualTree(e);
    }

    private void OnSeasonChanged()
    {
        // Only the lights move, slowly; a screenshot run and «уменьшить движение» keep them still.
        bool wanted = Rail && Season.Current == SeasonKind.NewYear && !Program.UiSmoke;
        if (wanted && _twinkle is null)
        {
            _twinkle = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(900) };
            _twinkle.Tick += (_, _) =>
            {
                if (TopLevel.GetTopLevel(this) is not Window window || !window.IsVisible
                    || window.WindowState == WindowState.Minimized || window.Classes.Contains("reducedMotion")) return;
                _phase++;
                InvalidateVisual();
            };
            _twinkle.Start();
        }
        else if (!wanted && _twinkle is not null) { _twinkle.Stop(); _twinkle = null; }
        InvalidateVisual();
    }

    public override void Render(DrawingContext context)
    {
        double width = Bounds.Width, height = Bounds.Height;
        if (Season.Current == SeasonKind.None || width < 40 || height < 40) return;
        if (Rail)
        {
            if (Season.Current == SeasonKind.Halloween) DrawWeb(context, width);
            else DrawLights(context, width);
        }
        else if (width >= 320 && height >= 240)
        {
            if (Season.Current == SeasonKind.Halloween) DrawMoon(context, width);
            else DrawSnow(context, width, height);
        }
    }

    // ------------------------------------------------------------------ the sky ----

    private static void DrawMoon(DrawingContext context, double width)
    {
        // In the header, between the page title and the search field; a narrow window has no room for it.
        if (width < 700) return;
        const double radius = 34;
        var centre = new Point(width - 348, 74);
        var glow = new RadialGradientBrush
        {
            GradientStops = { new GradientStop(Color.FromArgb(34, 0xFF, 0x9A, 0x3D), 0), new GradientStop(Color.FromArgb(0, 0xFF, 0x9A, 0x3D), 1) }
        };
        context.DrawEllipse(glow, null, centre, radius * 3.2, radius * 3.2);
        context.DrawEllipse(new SolidColorBrush(Color.FromArgb(30, 0xFF, 0xE2, 0xA8)), null, centre, radius, radius);
        var crater = new SolidColorBrush(Color.FromArgb(16, 0x0E, 0x0A, 0x10));
        context.DrawEllipse(crater, null, new Point(centre.X - radius * 0.25, centre.Y - radius * 0.17), radius * 0.21, radius * 0.21);
        context.DrawEllipse(crater, null, new Point(centre.X + radius * 0.29, centre.Y + radius * 0.25), radius * 0.15, radius * 0.15);
        context.DrawEllipse(crater, null, new Point(centre.X - radius * 0.10, centre.Y + radius * 0.48), radius * 0.10, radius * 0.10);

        var wing = new SolidColorBrush(Color.FromArgb(130, 0x55, 0x40, 0x75));
        DrawBat(context, wing, new Point(centre.X - radius * 1.55, centre.Y - radius * 0.62), 30, -9);
        DrawBat(context, wing, new Point(centre.X - radius * 0.20, centre.Y - radius * 1.48), 20, 7);
        DrawBat(context, wing, new Point(centre.X + radius * 1.30, centre.Y - radius * 0.20), 14, 14);
    }

    // Half a bat, head to tail, on a unit wingspan; the other half is its mirror.
    private static readonly Point[] BatHalf =
    {
        new(0, -0.10), new(0.035, -0.07), new(0.065, -0.17), new(0.095, -0.05), new(0.30, -0.15), new(0.50, -0.06),
        new(0.39, 0.02), new(0.30, -0.01), new(0.24, 0.08), new(0.15, 0.03), new(0.09, 0.11), new(0, 0.16)
    };

    private static readonly StreamGeometry Bat = BuildBat();

    private static StreamGeometry BuildBat()
    {
        var geometry = new StreamGeometry();
        using var figure = geometry.Open();
        figure.BeginFigure(BatHalf[0], true);
        for (int i = 1; i < BatHalf.Length; i++) figure.LineTo(BatHalf[i]);
        for (int i = BatHalf.Length - 2; i >= 1; i--) figure.LineTo(new Point(-BatHalf[i].X, BatHalf[i].Y));
        figure.EndFigure(true);
        return geometry;
    }

    private static void DrawBat(DrawingContext context, IBrush brush, Point centre, double span, double tilt)
    {
        var transform = Matrix.CreateScale(span, span) * Matrix.CreateRotation(tilt * Math.PI / 180) * Matrix.CreateTranslation(centre.X, centre.Y);
        using (context.PushTransform(transform)) context.DrawGeometry(brush, null, Bat);
    }

    private static void DrawSnow(DrawingContext context, double width, double height)
    {
        // The same flakes on every paint: a fixed seed, positions as fractions of the sky.
        var random = new Random(2027);
        for (int i = 0; i < 54; i++)
        {
            double x = random.NextDouble() * width;
            double fall = random.NextDouble();
            double y = fall * fall * height;
            double size = 1.4 + random.NextDouble() * 3.4;
            byte alpha = (byte)(22 + random.Next(36));
            var ink = Color.FromArgb(alpha, 0xD8, 0xEA, 0xFB);
            if (size < 3.2) context.DrawEllipse(new SolidColorBrush(ink), null, new Point(x, y), size / 2, size / 2);
            else
            {
                var pen = new Pen(new SolidColorBrush(Color.FromArgb((byte)(alpha + 16), 0xD8, 0xEA, 0xFB)), 1, lineCap: PenLineCap.Round);
                double radius = size * 1.5, turn = random.NextDouble() * 60;
                for (int arm = 0; arm < 3; arm++)
                {
                    double angle = (turn + arm * 60) * Math.PI / 180;
                    double dx = Math.Cos(angle) * radius, dy = Math.Sin(angle) * radius;
                    context.DrawLine(pen, new Point(x - dx, y - dy), new Point(x + dx, y + dy));
                }
            }
        }
    }

    // ----------------------------------------------------------------- the rail ----

    private static void DrawWeb(DrawingContext context, double width)
    {
        const double reach = 70;
        const int spokes = 5;
        var corner = new Point(width - 1, 0);
        var pen = new Pen(new SolidColorBrush(Color.FromArgb(40, 0xFF, 0xFF, 0xFF)), 1);
        var ends = new Point[spokes];
        for (int i = 0; i < spokes; i++)
        {
            // From straight down along the edge to straight left along the top.
            double angle = (90.0 + i * 90.0 / (spokes - 1)) * Math.PI / 180;
            double length = reach * (i % 2 == 0 ? 1 : 0.9);
            ends[i] = new Point(corner.X + Math.Cos(angle) * length, corner.Y + Math.Sin(angle) * length);
            context.DrawLine(pen, corner, ends[i]);
        }

        // Rings sag towards the corner between each pair of spokes.
        var rings = new StreamGeometry();
        using (var figure = rings.Open())
            for (double ring = 0.34; ring <= 1.001; ring += 0.22)
                for (int i = 0; i < spokes - 1; i++)
                {
                    Point from = Along(corner, ends[i], ring), to = Along(corner, ends[i + 1], ring);
                    Point sag = Along(new Point((from.X + to.X) / 2, (from.Y + to.Y) / 2), corner, 0.16);
                    figure.BeginFigure(from, false);
                    figure.CubicBezierTo(sag, sag, to);
                    figure.EndFigure(false);
                }
        context.DrawGeometry(null, pen, rings);

        // The tenant, on a thread of its own, clear of the brand's lettering.
        double x = width - 22, drop = 78, size = 3;
        var body = new SolidColorBrush(Color.FromArgb(78, 0xFF, 0xFF, 0xFF));
        var leg = new Pen(body, 1);
        context.DrawLine(pen, new Point(x, 0), new Point(x, drop - size));
        for (int side = -1; side <= 1; side += 2)
            for (int i = 0; i < 3; i++)
            {
                double lift = (i - 1) * size * 0.75;
                var knee = new Point(x + side * size * 1.3, drop + lift - size * 0.5);
                context.DrawLine(leg, new Point(x, drop + lift * 0.3), knee);
                context.DrawLine(leg, knee, new Point(x + side * size * 2.0, drop + lift + size * 0.5));
            }
        context.DrawEllipse(body, null, new Point(x, drop + size * 0.15), size, size * 1.15);
    }

    private static Point Along(Point from, Point to, double t) => new(from.X + (to.X - from.X) * t, from.Y + (to.Y - from.Y) * t);

    private static readonly Color[] Bulbs =
    {
        Color.FromRgb(0xFF, 0xD3, 0x6B), Color.FromRgb(0x9E, 0xD8, 0xFF), Color.FromRgb(0xFF, 0x7A, 0x7A), Color.FromRgb(0x8B, 0xE8, 0xB0)
    };

    private void DrawLights(DrawingContext context, double width)
    {
        const double step = 34, top = 3, dip = 7, radius = 2.6;
        int count = Math.Max(1, (int)((width - 8) / step));
        double left = (width - count * step) / 2;

        var wire = new StreamGeometry();
        using (var figure = wire.Open())
            for (int i = 0; i < count; i++)
            {
                double x0 = left + i * step, x1 = x0 + step, middle = (x0 + x1) / 2;
                figure.BeginFigure(new Point(x0, top), false);
                figure.CubicBezierTo(new Point(middle, top + dip * 2), new Point(middle, top + dip * 2), new Point(x1, top));
                figure.EndFigure(false);
            }
        context.DrawGeometry(null, new Pen(new SolidColorBrush(Color.FromArgb(90, 0x88, 0x98, 0xB4)), 1), wire);

        for (int i = 0; i < count; i++)
        {
            // A bulb hangs at the lowest point of each swag; every other one is lit, and the tick swaps which.
            var centre = new Point(left + i * step + step / 2, top + dip * 1.5 + radius);
            Color colour = Bulbs[i % Bulbs.Length];
            bool lit = ((i + _phase) & 1) == 0;
            if (lit)
                context.DrawEllipse(new RadialGradientBrush
                {
                    GradientStops = { new GradientStop(Color.FromArgb(84, colour.R, colour.G, colour.B), 0), new GradientStop(Color.FromArgb(0, colour.R, colour.G, colour.B), 1) }
                }, null, centre, radius * 4, radius * 4);
            context.DrawEllipse(new SolidColorBrush(lit ? colour : Color.FromArgb(110, colour.R, colour.G, colour.B)), null, centre, radius, radius * 1.15);
        }
    }
}
