namespace DustoreLauncherV.Mac.Services;

public enum SeasonKind { None, Halloween, NewYear }

/// <summary>
/// The launcher dresses up twice a year and takes the costume off by itself:
/// Halloween for the whole of October, New Year from 1 December to 31 January.
/// A season is a palette and two quiet ornaments; the layout and the controls stay put.
/// </summary>
public static class Season
{
    public static SeasonKind Current { get; private set; }

    /// <summary>Raised when the season on screen changes, so the ornaments repaint.</summary>
    public static event Action? Changed;

    public static SeasonKind For(DateTime day) => day.Month switch
    {
        10 => SeasonKind.Halloween,
        12 or 1 => SeasonKind.NewYear,
        _ => SeasonKind.None
    };

    /// <summary><paramref name="forced"/> (DUSTORE_SEASON) shows a season on any date and wins over the setting.</summary>
    public static SeasonKind Decide(DateTime day, bool enabled, string? forced) =>
        TryParse(forced, out var asked) ? asked : enabled ? For(day) : SeasonKind.None;

    public static bool TryParse(string? text, out SeasonKind kind)
    {
        kind = SeasonKind.None;
        switch (text?.Trim().ToLowerInvariant())
        {
            case "halloween": kind = SeasonKind.Halloween; return true;
            case "newyear" or "new-year": kind = SeasonKind.NewYear; return true;
            case "none" or "off": return true;
            default: return false;
        }
    }

    public static string Describe(SeasonKind kind) => kind switch
    {
        SeasonKind.Halloween => "Сейчас: Хеллоуин — до конца октября.",
        SeasonKind.NewYear => "Сейчас: Новый год — по 31 января.",
        _ => "Сейчас: обычное оформление."
    };

    internal static void Set(SeasonKind kind)
    {
        if (Current == kind) return;
        Current = kind;
        Changed?.Invoke();
    }

    /// <summary>The brushes a season replaces; everything not listed keeps the dark defaults.</summary>
    internal static IReadOnlyDictionary<string, string>? Colors(SeasonKind kind) => kind switch
    {
        // A night with a plum cast, ghost-violet selection, pumpkin for the action.
        SeasonKind.Halloween => new Dictionary<string, string>
        {
            ["Background"] = "#0E0A10", ["BackgroundSoft"] = "#16101A", ["NavBackground"] = "#0B080D", ["NavTranslucent"] = "#E30B080D",
            ["Surface"] = "#1D1522", ["SurfaceRaised"] = "#271C2E", ["SurfaceHover"] = "#33263B", ["SurfacePressed"] = "#18111C",
            ["Fill"] = "#14FFFFFF", ["FillHover"] = "#21FFFFFF", ["Hairline"] = "#13FFFFFF", ["BorderSoft"] = "#1FFFFFFF",
            ["BorderMid"] = "#33B78CFF", ["BorderStrong"] = "#7A5C86",
            ["TextPrimary"] = "#F7F4FB", ["TextSecondary"] = "#D9CBD6", ["TextDim"] = "#A38FA6",
            ["Orchid"] = "#B78CFF", ["AccentSubtle"] = "#33B78CFF",
            ["Accent"] = "#FF9A3D", ["AccentHover"] = "#FFB56B", ["AccentPressed"] = "#DB7A22", ["AccentInk"] = "#1B1206"
        },
        // A frosty blue night, ice for the selection, the gold of lights for the action.
        SeasonKind.NewYear => new Dictionary<string, string>
        {
            ["Background"] = "#0A0F1A", ["BackgroundSoft"] = "#0F1626", ["NavBackground"] = "#080C15", ["NavTranslucent"] = "#E3080C15",
            ["Surface"] = "#131C2E", ["SurfaceRaised"] = "#1A2540", ["SurfaceHover"] = "#22304F", ["SurfacePressed"] = "#101828",
            ["Fill"] = "#14FFFFFF", ["FillHover"] = "#21FFFFFF", ["Hairline"] = "#13FFFFFF", ["BorderSoft"] = "#1FFFFFFF",
            ["BorderMid"] = "#339ED8FF", ["BorderStrong"] = "#4F648C",
            ["TextPrimary"] = "#F7F4FB", ["TextSecondary"] = "#C9D6EA", ["TextDim"] = "#8898B4",
            ["Orchid"] = "#9ED8FF", ["AccentSubtle"] = "#2E9ED8FF",
            ["Accent"] = "#FFD36B", ["AccentHover"] = "#FFE29A", ["AccentPressed"] = "#E0B047", ["AccentInk"] = "#1A1606"
        },
        _ => null
    };
}
