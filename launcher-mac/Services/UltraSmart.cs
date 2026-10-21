using System.Diagnostics;
using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace DustoreLauncherV.Mac.Services;

/// <summary>
/// Smart ULTRA on the Mac: the share of the display a full-screen Unity game renders at is
/// chosen for each game from how busy it kept the graphics processor the last time.
///
/// macOS tells any program how busy the GPU is — the "Device Utilization %" every graphics
/// accelerator reports to the I/O Registry, the number Activity Monitor shows — without any
/// special rights. While a game ULTRA launched is running, it is read every few seconds, and
/// the median is written down together with the share the game was played at. The next
/// launch takes one step down when the GPU was flat out, one step up when it had room even
/// counting the extra pixels, and otherwise stays: 100 → 85 → 75 %, never lower on its own.
///
/// It is the same rule as the Windows launcher's, on the whole GPU rather than per process:
/// with a game in front, the game is what keeps the GPU busy.
/// </summary>
public static class UltraSmart
{
    public const int Smart = 0;
    public static readonly int[] Steps = { 100, 85, 75 };
    public const double FlatOut = 80, Room = 65;
    private const int SampleEverySeconds = 3, SkipFirstSeconds = 15, FewestSamples = 8;

    public sealed class Note
    {
        public Guid Game { get; set; }
        public string Name { get; set; } = "";
        public double GpuLoad { get; set; }
        public int Samples { get; set; }
        public int Scale { get; set; } = 100;
        public DateTime MeasuredUtc { get; set; }
    }

    public static int StepOf(int scale) => scale <= 0 || scale > 92 ? 100 : scale > 80 ? 85 : 75;

    public static double Predict(double load, int from, int to) => from <= 0 ? load : load * (to * (double)to) / (from * (double)from);

    /// <summary>The share to play this launch at, and the sentence that says why.</summary>
    public static int Choose(Note? known, out string why)
    {
        if (known is null || known.Samples <= 0)
        {
            why = "Умный ULTRA: первый запуск этой игры — 85 %. За игру ULTRA измерит загрузку видеокарты и подберёт разрешение для следующего раза.";
            return 85;
        }
        int step = StepOf(known.Scale), index = Array.IndexOf(Steps, step);
        if (known.GpuLoad >= FlatOut && index < Steps.Length - 1)
        {
            why = $"Умный ULTRA: {Steps[index + 1]} %. В прошлый раз при {step} % видеокарта была занята на {known.GpuLoad:0} % — ей не хватало.";
            return Steps[index + 1];
        }
        if (index > 0 && Predict(known.GpuLoad, step, Steps[index - 1]) <= Room)
        {
            why = $"Умный ULTRA: {Steps[index - 1]} %. В прошлый раз при {step} % видеокарта была занята на {known.GpuLoad:0} % — запас есть, картинка чётче.";
            return Steps[index - 1];
        }
        why = $"Умный ULTRA: {step} %. В прошлый раз видеокарта была занята на {known.GpuLoad:0} %.";
        return step;
    }

    /// <summary>The busiest graphics accelerator in <c>ioreg -c IOAccelerator</c> output, per cent; null when none reports.</summary>
    public static double? ParseUtilization(string ioreg)
    {
        double? busiest = null;
        foreach (Match match in Regex.Matches(ioreg ?? "", "\"Device Utilization %\"\\s*=\\s*(\\d+(?:\\.\\d+)?)"))
            if (double.TryParse(match.Groups[1].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out double value))
                busiest = Math.Max(busiest ?? 0, Math.Min(100, value));
        return busiest;
    }

    private static async Task<double?> SampleAsync(CancellationToken cancellation)
    {
        var start = new ProcessStartInfo("/usr/sbin/ioreg") { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (string argument in new[] { "-r", "-d", "1", "-w", "0", "-c", "IOAccelerator" }) start.ArgumentList.Add(argument);
        using var process = Process.Start(start);
        if (process is null) return null;
        string output = await process.StandardOutput.ReadToEndAsync(cancellation).ConfigureAwait(false);
        await process.WaitForExitAsync(cancellation).ConfigureAwait(false);
        return ParseUtilization(output);
    }

    // ------------------------------------------------------------- what is known ----

    private static readonly object Gate = new();
    private static string NotesPath(string profile) => Path.Combine(profile, "ultra-games.json");

    public static List<Note> AllKnown(string profile)
    {
        try
        {
            lock (Gate)
                if (File.Exists(NotesPath(profile)))
                    return (JsonSerializer.Deserialize<List<Note>>(File.ReadAllText(NotesPath(profile))) ?? new()).OrderByDescending(note => note.MeasuredUtc).ToList();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { }
        return new();
    }

    public static Note? Known(string profile, Guid game) => AllKnown(profile).FirstOrDefault(note => note.Game == game);

    public static void Remember(string profile, Note note)
    {
        try
        {
            lock (Gate)
            {
                var notes = AllKnown(profile).Where(other => other.Game != note.Game).ToList();
                notes.Insert(0, note);
                if (notes.Count > 400) notes.RemoveRange(400, notes.Count - 400);
                string temporary = NotesPath(profile) + ".tmp";
                File.WriteAllText(temporary, JsonSerializer.Serialize(notes, new JsonSerializerOptions { WriteIndented = true }));
                File.Move(temporary, NotesPath(profile), true);
            }
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    public static void Forget(string profile)
    {
        try { lock (Gate) if (File.Exists(NotesPath(profile))) File.Delete(NotesPath(profile)); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    /// <summary>A short line about one game for the settings page.</summary>
    public static string Describe(Note note)
    {
        int step = StepOf(note.Scale), next = Choose(note, out _);
        return $"{(string.IsNullOrWhiteSpace(note.Name) ? "Игра" : note.Name)} — {step} %, видеокарта {note.GpuLoad:0} %" + (next != step ? $" → в следующий раз {next} %" : "");
    }

    // ----------------------------------------------------------------- watching ----

    /// <summary>
    /// Reads the GPU's load while <paramref name="running"/> says the game is on, and writes
    /// the median down when it ends. Never throws: knowledge is a convenience.
    /// </summary>
    public static void Watch(string profile, GameEntry entry, int scale, Func<bool> running)
    {
        if (!OperatingSystem.IsMacOS()) return;
        _ = Task.Run(async () =>
        {
            try
            {
                // The session is registered a moment after the launch.
                var appear = Stopwatch.StartNew();
                while (!running() && appear.Elapsed < TimeSpan.FromSeconds(30)) await Task.Delay(500).ConfigureAwait(false);
                if (!running()) return;
                var loads = new List<double>();
                var clock = Stopwatch.StartNew();
                while (running() && clock.Elapsed < TimeSpan.FromHours(6))
                {
                    await Task.Delay(TimeSpan.FromSeconds(SampleEverySeconds)).ConfigureAwait(false);
                    if (clock.Elapsed < TimeSpan.FromSeconds(SkipFirstSeconds)) continue;
                    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                    double? load = await SampleAsync(timeout.Token).ConfigureAwait(false);
                    if (load is double value) loads.Add(value);
                }
                if (loads.Count < FewestSamples) return;
                loads.Sort();
                Remember(profile, new Note { Game = entry.Id, Name = entry.Name, GpuLoad = Math.Round(loads[loads.Count / 2], 1), Samples = loads.Count, Scale = scale, MeasuredUtc = DateTime.UtcNow });
            }
            catch (Exception) { }
        });
    }
}
