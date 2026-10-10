using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;
using DustoreLauncherV.Mac.Services;

namespace DustoreLauncherV.Mac.Controls;

/// <summary>
/// The seasonal ornament, drawn behind the content: behind the pages the season's scene, at
/// the top of the sidebar a cobweb with its spider or a string of lights.
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
        else if (width >= 320 && height >= 240) DrawScene(context, width, height);
    }

    // The season's scene is the one the Windows launcher draws, kept as a picture: a moonlit
    // hill with its house, lanterns and ghosts, or a snowed-in clearing with a dressed tree.
    // It stands in the bottom right corner of the pages, where they are empty more often than not.
    private static Avalonia.Media.Imaging.Bitmap? _scene;
    private static SeasonKind _sceneFor = SeasonKind.None;

    private static Avalonia.Media.Imaging.Bitmap? Scene()
    {
        if (_sceneFor == Season.Current) return _scene;
        _sceneFor = Season.Current;
        _scene = null;
        try
        {
            string name = Season.Current == SeasonKind.Halloween ? "season-halloween.png" : "season-newyear.png";
            using var stream = Avalonia.Platform.AssetLoader.Open(new Uri("avares://DustoreLauncherV.Mac/Assets/" + name));
            _scene = new Avalonia.Media.Imaging.Bitmap(stream);
        }
        catch (Exception) { }   // no picture: the pages simply stay plain
        return _scene;
    }

    /// <summary>Where the scene stands in a page of the given size: bottom right, smaller in a short window.</summary>
    public static Rect ScenePlace(double width, double height, double pictureWidth, double pictureHeight)
    {
        // Drawn for a page about 820 points tall.
        double scale = Math.Clamp(height / 820.0, 0.55, 1.0);
        double sceneHeight = 720 * scale, sceneWidth = sceneHeight * pictureWidth / Math.Max(1, pictureHeight);
        return new Rect(width - sceneWidth, height - sceneHeight, sceneWidth, sceneHeight);
    }

    private static void DrawScene(DrawingContext context, double width, double height)
    {
        if (Scene() is not { } scene) return;
        // Game names and captions lie straight on the page, so the scene is held back a little.
        using (context.PushClip(new Rect(0, 0, width, height)))
        using (context.PushOpacity(0.72))
            context.DrawImage(scene, ScenePlace(width, height, scene.PixelSize.Width, scene.PixelSize.Height));
    }

    // ----------------------------------------------------------------- the rail ----

    private static void DrawWeb(DrawingContext context, double width)
    {
        const double reach = 96;
        const int spokes = 5;
        var corner = new Point(width - 1, 0);
        var pen = new Pen(new SolidColorBrush(Color.FromArgb(72, 0xFF, 0xFF, 0xFF)), 1.1);
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
        double x = width - 22, drop = 96, size = 4.4;
        var body = new SolidColorBrush(Color.FromArgb(150, 0xFF, 0xFF, 0xFF));
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
        const double step = 36, top = 2, dip = 6, radius = 3.4;
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
                    GradientStops = { new GradientStop(Color.FromArgb(140, colour.R, colour.G, colour.B), 0), new GradientStop(Color.FromArgb(0, colour.R, colour.G, colour.B), 1) }
                }, null, centre, radius * 4, radius * 4);
            context.DrawEllipse(new SolidColorBrush(lit ? colour : Color.FromArgb(110, colour.R, colour.G, colour.B)), null, centre, radius, radius * 1.15);
        }
    }
}
