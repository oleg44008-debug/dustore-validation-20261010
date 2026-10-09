package ru.dustore.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Рецепт запуска: что игра просит у Windows и чем ей это дать на телефоне.
 *
 * Считается на самом телефоне — там, где лежат файлы, — и без компьютера. Ничего похожего
 * на декомпиляцию здесь нет: читаются заголовок .exe, имена библиотек из его таблицы
 * импорта и, если графику там не объявили, имена библиотек строкой в разделах данных.
 * Та же информация, по которой сама Windows решает, что подгружать.
 *
 * Слово в слово повторяет разбор с компьютера (`ExRecipe.cs`, `PeLook`, `GameProfile`):
 * одна сборка обязана получать один и тот же вердикт с обеих сторон.
 */
final class ExRecipe {
    static final int CURRENT_VERSION = 2;
    int version = CURRENT_VERSION;

    static final int TIER_LIGHT = 1;
    static final int TIER_MEDIUM = 2;
    static final int TIER_HEAVY = 3;
    static final int TIER_BLOCKED = 4;

    /** Движок, прочитанный с диска, или null. */
    String engine;

    /** Путь к .exe относительно папки игры, с прямыми косыми чертами. */
    String exe;

    /** 64-битная сборка. */
    boolean wide = true;

    String api = "неизвестно";
    String renderer = "dxvk";
    int tier = TIER_MEDIUM;
    String antiCheat;
    String profile = ExPads.DESKTOP;
    String expect = "";
    String source = "не найдено";

    final List<String> saw = new ArrayList<>();
    final Map<String, String> env = new LinkedHashMap<>();

    /**
     * Ключи запуска, которые игра понимает сама.
     *
     * Godot 4 умеет переключить рисовалку ключом командной строки: «mobile» вместо
     * «forward_plus» снимает с процессора половину работы, а именно процессор здесь
     * узкое место — код Godot идёт через переводчик. Плюс полный экран рабочего стола.
     */
    final List<String> args = new ArrayList<>();

    /** Unity accepts these documented player flags; keep stored game args intact. */
    List<String> launchArgs(int width, int height) {
        List<String> result = new ArrayList<>(args);
        if ("Unity".equals(engine)) {
            if (!result.contains("-screen-width")) {
                result.add("-screen-width");
                result.add(Integer.toString(width));
            }
            if (!result.contains("-screen-height")) {
                result.add("-screen-height");
                result.add(Integer.toString(height));
            }
            if (!result.contains("-screen-fullscreen")) {
                result.add("-screen-fullscreen");
                result.add("1");
            }
            if (!result.contains("-screen-quality")) {
                result.add("-screen-quality");
                result.add("Fastest");
            }
        }
        return result;
    }

    boolean canRun() {
        return tier != TIER_BLOCKED;
    }

    String tierName() {
        switch (tier) {
            case TIER_LIGHT: return "лёгкая";
            case TIER_MEDIUM: return "средняя";
            case TIER_HEAVY: return "тяжёлая";
            default: return "нельзя";
        }
    }

    // ------------------------------------------------------------------ разбор ----

    /** Разбирает папку игры, сам находя файл запуска. */
    static ExRecipe read(File folder) {
        return read(folder, findExe(folder));
    }

    static ExRecipe read(File folder, String exe) {
        ExRecipe recipe = new ExRecipe();
        recipe.exe = exe;
        recipe.engine = detectEngine(folder);
        recipe.profile = ExPads.suggest(recipe.engine);
        recipe.antiCheat = findAntiCheat(folder);

        if (recipe.antiCheat != null) {
            recipe.tier = TIER_BLOCKED;
            recipe.renderer = "нет";
            recipe.expect = "Играть не получится: " + recipe.antiCheat + " проверяет, в "
                    + "какой системе идёт игра, и чужое окружение не пропускает.";
            return recipe;
        }

        if (exe != null) {
            look(recipe, new File(folder, exe));
            // Godot умеет упаковывать PCK внутрь одного EXE: соседнего .pck тогда нет.
            if (recipe.engine == null && mentions(new File(folder, exe), "Godot Engine")) {
                recipe.engine = "Godot";
                recipe.profile = ExPads.suggest(recipe.engine);
            }

            if ("Godot".equals(recipe.engine) && mentions(new File(folder, exe), "--rendering-method")) {
                recipe.args.add("--rendering-method");
                recipe.args.add("mobile");
                recipe.args.add("--fullscreen");
            }
        }

        boolean dx12 = has(recipe.saw, "d3d12");
        boolean dx11 = has(recipe.saw, "d3d11");
        boolean dx10 = has(recipe.saw, "d3d10");
        boolean dx9 = has(recipe.saw, "d3d9") || has(recipe.saw, "d3dx9");
        boolean gl = has(recipe.saw, "opengl32");
        boolean vk = has(recipe.saw, "vulkan-1");

        // Движок знает о себе больше, чем список библиотек. Godot 4 несёт и DirectX 12,
        // и Vulkan, но по умолчанию на Windows рисует Vulkan-ом.
        boolean godotVulkan = vk && "Godot".equals(recipe.engine);

        String alsoVulkan = vk && (dx12 || dx11 || dx10)
                ? " В сборке нашлась и поддержка Vulkan: если игра умеет ключ Vulkan, ей "
                  + "это сильно поможет."
                : "";

        if (dx12 && !godotVulkan) {
            recipe.api = "DirectX 12";
            recipe.renderer = "vkd3d";
            recipe.tier = TIER_HEAVY;
            recipe.expect = "DirectX 12 переводится в Vulkan хуже одиннадцатого: кадров "
                    + "будет мало, телефон будет греться." + alsoVulkan;
        } else if (vk) {
            recipe.api = godotVulkan ? "Vulkan (Godot по умолчанию)" : "Vulkan";
            recipe.renderer = "native";
            recipe.tier = TIER_MEDIUM;
            recipe.expect = "Игра рисует через Vulkan — переводить графику не нужно, "
                    + "только инструкции процессора. Из всех случаев этот самый удачный.";
        } else if (dx11 || dx10) {
            recipe.api = dx11 ? "DirectX 11" : "DirectX 10";
            recipe.renderer = "dxvk";
            recipe.tier = TIER_MEDIUM;
            recipe.expect = "DirectX 11 переводится в Vulkan через DXVK. Для среднего 3D "
                    + "это рабочий путь, но не «как на компьютере»." + alsoVulkan;
        } else if (dx9) {
            recipe.api = "DirectX 9";
            recipe.renderer = "dxvk";
            recipe.tier = TIER_LIGHT;
            recipe.expect = "DirectX 9 — самый обжитой случай: старые игры и 2D идут "
                    + "лучше всего остального.";
        } else if (gl) {
            recipe.api = "OpenGL";
            recipe.renderer = "native";
            recipe.tier = TIER_LIGHT;
            recipe.expect = "OpenGL телефон умеет сам — переводить графику почти не "
                    + "придётся.";
        } else if (has(recipe.saw, "dxgi")) {
            recipe.api = "DirectX 11 (по dxgi)";
            recipe.renderer = "dxvk";
            recipe.tier = TIER_MEDIUM;
            recipe.expect = "Точную версию DirectX сборка не называет — видно только общий "
                    + "слой dxgi. Настройки взяты как для одиннадцатого.";
        } else {
            recipe.api = "не объявлена в заголовках";
            recipe.renderer = "dxvk";
            recipe.tier = TIER_MEDIUM;
            recipe.expect = "Игра не объявляет графику заранее — подгружает её на ходу. "
                    + "Настройки взяты средние; что получится, будет видно на запуске.";
        }

        buildEnv(recipe);
        return recipe;
    }

    /**
     * Переменные под этот рецепт — поверх базовых из {@link Ex#environment}.
     *
     * DXVK стоит у всех: библиотеки лежат на диске C: и подхватываются только теми, кто их
     * зовёт. Для DirectX 12 родного перевода пока нет — оставляем встроенный в Wine.
     */
    private static void buildEnv(ExRecipe recipe) {
        String always = "mscoree,mshtml,winemenubuilder.exe=d";

        switch (recipe.renderer) {
            case "vkd3d":
                recipe.env.put("WINEDLLOVERRIDES", always + ";d3d9,d3d10core,d3d11,dxgi=n");
                break;
            case "native":
                recipe.env.put("WINEDLLOVERRIDES", always + ";d3d8,d3d9,d3d10core,d3d11,dxgi=n");
                break;
            default:
                recipe.env.put("WINEDLLOVERRIDES", always + ";d3d8,d3d9,d3d10core,d3d11,dxgi=n");
                break;
        }

        if (!recipe.wide) {
            recipe.env.put("BOX64_DYNAREC_STRONGMEM", "2");
        }
    }

    // --------------------------------------------------------------- движок ----

    static String detectEngine(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return null;
        }

        String found = sniff(folder);

        if (found != null) {
            return found;
        }

        // Архивы часто раскрываются в «Игра/Игра/game.exe»: один уровень вглубь стоит
        // посмотреть, глубже — уже не догадка, а гадание.
        File[] children = folder.listFiles();

        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    found = sniff(child);

                    if (found != null) {
                        return found;
                    }
                }
            }
        }

        return null;
    }

    private static String sniff(File folder) {
        if (new File(folder, "UnityPlayer.dll").exists() || hasDir(folder, "_Data")) {
            return "Unity";
        }

        if (new File(folder, "Engine").isDirectory() && hasUnrealBinaries(folder)) {
            return "Unreal Engine";
        }

        if (new File(folder, "data.win").exists()) {
            return "GameMaker";
        }

        if (new File(folder, "renpy").isDirectory()) {
            return "Ren'Py";
        }

        if (new File(folder, "www").isDirectory() || new File(folder, "package.json").exists()
                || hasFile(folder, ".rgss3a") || hasFile(folder, ".rgss2a")
                || hasFile(folder, ".rgssad")) {
            return "RPG Maker";
        }

        if (hasFile(folder, ".pck")) {
            return "Godot";
        }

        if (new File(folder, "index.html").exists()) {
            return "Веб-сборка";
        }

        if (hasFile(folder, ".love")) {
            return "LÖVE";
        }

        if (hasFile(folder, ".jar")) {
            return "Java";
        }

        return null;
    }

    private static boolean hasDir(File folder, String suffix) {
        File[] children = folder.listFiles();

        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && child.getName().endsWith(suffix)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean hasFile(File folder, String suffix) {
        File[] children = folder.listFiles();

        if (children != null) {
            for (File child : children) {
                if (child.isFile() && child.getName().toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean hasUnrealBinaries(File folder) {
        File[] children = folder.listFiles();

        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && new File(child, "Binaries/Win64").isDirectory()) {
                    return true;
                }
            }
        }

        return false;
    }

    // ------------------------------------------------------------- файл запуска ----

    /**
     * Файл запуска: .exe ближе к корню сильнее, крупнее — сильнее, служебные — мимо.
     *
     * Godot кладёт рядом `игра.console.exe` — то же самое, но с консолью; Unity —
     * `UnityCrashHandler64.exe`; установщики — `unins000.exe`. Ничто из этого игрой не
     * является.
     */
    static String findExe(File root) {
        List<File> found = new ArrayList<>();
        collectExes(root, found, 0);

        File best = null;
        long bestScore = Long.MIN_VALUE;

        for (File file : found) {
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);

            if (name.contains("crashhandler") || name.startsWith("unins")
                    || name.contains("vcredist") || name.contains("dxsetup")
                    || name.contains("dxwebsetup") || name.contains("setup")
                    || name.contains(".console.")) {
                continue;
            }

            int depth = depthOf(root, file);
            long score = -depth * 1_000_000_000L + Math.min(file.length(), 900_000_000L);

            if (score > bestScore) {
                bestScore = score;
                best = file;
            }
        }

        if (best == null) {
            return null;
        }

        return best.getAbsolutePath().substring(root.getAbsolutePath().length() + 1)
                .replace('\\', '/');
    }

    private static void collectExes(File where, List<File> into, int depth) {
        if (depth > 3) {
            return;
        }

        File[] children = where.listFiles();

        if (children == null) {
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                collectExes(child, into, depth + 1);
            } else if (child.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                into.add(child);
            }
        }
    }

    private static int depthOf(File root, File file) {
        int depth = 0;
        File at = file.getParentFile();

        while (at != null && !at.equals(root)) {
            depth++;
            at = at.getParentFile();
        }

        return depth;
    }

    // ----------------------------------------------------------------- защита ----

    private static final String[][] GUARDS = {
            { "easyanticheat", "Easy Anti-Cheat" },
            { "beclient", "BattlEye" },
            { "beservice", "BattlEye" },
            { "battleye", "BattlEye" },
            { "vgk.sys", "Vanguard" },
            { "xigncode", "XignCode" },
            { "gameguard", "GameGuard" },
            { "denuvo", "Denuvo" }
    };

    static String findAntiCheat(File folder) {
        return guardIn(folder, 0);
    }

    private static String guardIn(File where, int depth) {
        File[] children = where.listFiles();

        if (children == null || depth > 6) {
            return null;
        }

        for (File child : children) {
            String name = child.getName().toLowerCase(java.util.Locale.ROOT);

            for (String[] guard : GUARDS) {
                if (name.contains(guard[0])) {
                    return guard[1];
                }
            }

            if (child.isDirectory()) {
                String found = guardIn(child, depth + 1);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    // ---------------------------------------------------------------- заголовки ----

    private static final String[] INTERESTING = {
            "d3d12.dll", "d3d11.dll", "d3d10.dll", "d3d9.dll", "dxgi.dll",
            "opengl32.dll", "vulkan-1.dll", "xinput1_3.dll", "dinput8.dll"
    };

    private static final class Slice {
        String name;
        long rva;
        long virtualSize;
        long fileOffset;
        long fileSize;
    }

    /**
     * Читает .exe в два приёма: таблица импорта с произвольным доступом, потом строки в
     * разделах данных. Первые четыре мегабайта читать бесполезно: у сборок на 100 МБ
     * таблица лежит далеко за началом.
     */
    private static void look(ExRecipe recipe, File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] head = at(in, 0, 8192);

            if (head.length < 0x40 || head[0] != 'M' || head[1] != 'Z') {
                return;
            }

            int pe = le32(head, 0x3C);

            if (pe <= 0 || pe + 24 > head.length || head[pe] != 'P' || head[pe + 1] != 'E') {
                return;
            }

            int machine = le16(head, pe + 4);
            recipe.wide = machine == 0x8664 || machine == 0xAA64;

            int sectionCount = le16(head, pe + 6);
            int optionalSize = le16(head, pe + 20);
            int optional = pe + 24;
            boolean plus = le16(head, optional) == 0x20B;
            int directories = optional + (plus ? 112 : 96);
            long importRva = le32(head, directories + 8) & 0xFFFFFFFFL;

            List<Slice> sections = new ArrayList<>();
            int table = optional + optionalSize;

            for (int i = 0; i < sectionCount && i < 96 && table + 40 <= head.length;
                 i++, table += 40) {
                Slice slice = new Slice();
                slice.name = ascii(head, table, 8);
                slice.rva = le32(head, table + 12) & 0xFFFFFFFFL;
                slice.virtualSize = le32(head, table + 8) & 0xFFFFFFFFL;
                slice.fileOffset = le32(head, table + 20) & 0xFFFFFFFFL;
                slice.fileSize = le32(head, table + 16) & 0xFFFFFFFFL;
                sections.add(slice);
            }

            readImports(recipe, in, sections, importRva);

            if (!recipe.saw.isEmpty()) {
                recipe.source = "заголовки";
            }

            if (!hasGraphics(recipe.saw)) {
                int found = scanForNames(recipe, in, sections);

                if (found > 0) {
                    recipe.source = recipe.saw.size() > found
                            ? "заголовки + строки в файле" : "строки в файле";
                }
            }
        } catch (Exception broken) {
            // Нечитаемый или необычный файл — рецепт просто будет осторожнее.
        }
    }

    /** Есть ли в файле такая строка — так узнаётся Godot 4 по его же разбору ключей. */
    static boolean mentions(File file, String text) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] needle = text.getBytes("US-ASCII");
            byte[] chunk = new byte[1 << 20];
            long at = 0;

            while (at < in.length() && at < 200L * 1024 * 1024) {
                in.seek(at);
                int got = in.read(chunk);

                if (got <= 0) {
                    break;
                }

                if (contains(java.util.Arrays.copyOf(chunk, got), needle)) {
                    return true;
                }

                // Перекрытие на длину иглы: строка на стыке кусков не должна теряться.
                if (got < chunk.length) break;
                at += got - needle.length + 1;
            }
        } catch (Exception broken) {
            // Не прочитали — значит, не нашли.
        }

        return false;
    }

    private static boolean hasGraphics(List<String> dlls) {
        for (String dll : dlls) {
            String low = dll.toLowerCase(java.util.Locale.ROOT);

            // dxgi не считается нарочно: она бывает у всего новее девятого DirectX и
            // версии не называет — искать надо дальше.
            if (low.startsWith("d3d") || low.startsWith("opengl") || low.startsWith("vulkan")) {
                return true;
            }
        }

        return false;
    }

    private static void readImports(ExRecipe recipe, RandomAccessFile in, List<Slice> sections,
                                    long importRva) throws Exception {
        if (importRva == 0) {
            return;
        }

        long descriptors = toOffset(sections, importRva);

        if (descriptors < 0) {
            return;
        }

        byte[] block = at(in, descriptors, 96 * 20);

        for (int i = 0; i + 20 <= block.length; i += 20) {
            long nameRva = le32(block, i + 12) & 0xFFFFFFFFL;

            if (nameRva == 0) {
                break;
            }

            long where = toOffset(sections, nameRva);

            if (where < 0) {
                continue;
            }

            String dll = ascii(at(in, where, 64), 0, 64);

            if (!dll.isEmpty() && !recipe.saw.contains(dll)) {
                recipe.saw.add(dll);
            }
        }
    }

    private static int scanForNames(ExRecipe recipe, RandomAccessFile in, List<Slice> sections)
            throws Exception {
        int added = 0;
        long budget = 48L * 1024 * 1024;

        for (Slice section : sections) {
            if (section.fileSize == 0 || budget <= 0) {
                continue;
            }

            if (section.name.startsWith(".text") || section.name.startsWith("UPX")) {
                continue;
            }

            int size = (int) Math.min(section.fileSize, budget);
            byte[] data = at(in, section.fileOffset, size);
            budget -= size;

            for (String needle : INTERESTING) {
                if (recipe.saw.contains(needle)) {
                    continue;
                }

                if (contains(data, needle.getBytes("US-ASCII"))
                        || contains(data, needle.getBytes("UTF-16LE"))) {
                    recipe.saw.add(needle);
                    added++;
                }
            }
        }

        return added;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }

        byte first = needle[0];
        byte firstUpper = (byte) Character.toUpperCase((char) first);

        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (haystack[i] != first && haystack[i] != firstUpper) {
                continue;
            }

            for (int j = 1; j < needle.length; j++) {
                byte a = haystack[i + j];
                byte b = needle[j];

                if (a != b && Character.toLowerCase((char) a) != Character.toLowerCase((char) b)) {
                    continue outer;
                }
            }

            return true;
        }

        return false;
    }

    private static long toOffset(List<Slice> sections, long rva) {
        for (Slice section : sections) {
            long size = Math.max(section.virtualSize, section.fileSize);

            if (rva >= section.rva && rva < section.rva + size) {
                return section.fileOffset + (rva - section.rva);
            }
        }

        return -1;
    }

    private static byte[] at(RandomAccessFile in, long offset, int count) throws Exception {
        if (offset < 0 || offset >= in.length()) {
            return new byte[0];
        }

        int size = (int) Math.min(count, in.length() - offset);
        byte[] data = new byte[size];
        in.seek(offset);
        in.readFully(data);
        return data;
    }

    private static boolean has(List<String> dlls, String name) {
        for (String dll : dlls) {
            if (dll.toLowerCase(java.util.Locale.ROOT).startsWith(name)) {
                return true;
            }
        }

        return false;
    }

    private static int le32(byte[] data, int at) {
        if (at < 0 || at + 4 > data.length) {
            return 0;
        }

        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
    }

    private static int le16(byte[] data, int at) {
        if (at < 0 || at + 2 > data.length) {
            return 0;
        }

        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private static String ascii(byte[] data, int offset, int limit) {
        StringBuilder text = new StringBuilder();

        for (int i = offset; i < data.length && i < offset + limit; i++) {
            if (data[i] == 0) {
                break;
            }

            text.append((char) (data[i] & 0xFF));
        }

        return text.toString().trim();
    }

    // ------------------------------------------------------------------- json ----

    JSONObject toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("version", version);
            json.put("engine", engine == null ? JSONObject.NULL : engine);
            json.put("exe", exe == null ? JSONObject.NULL : exe);
            json.put("wide", wide);
            json.put("api", api);
            json.put("renderer", renderer);
            json.put("tier", tier);
            json.put("antiCheat", antiCheat == null ? JSONObject.NULL : antiCheat);
            json.put("profile", profile);
            json.put("expect", expect);
            json.put("source", source);
            json.put("saw", new JSONArray(saw));

            JSONObject vars = new JSONObject();

            for (Map.Entry<String, String> entry : env.entrySet()) {
                vars.put(entry.getKey(), entry.getValue());
            }

            json.put("env", vars);
            json.put("args", new JSONArray(args));
            return json;
        } catch (Exception broken) {
            return new JSONObject();
        }
    }

    static ExRecipe fromJson(JSONObject json) {
        ExRecipe recipe = new ExRecipe();

        if (json == null) {
            return recipe;
        }

        recipe.engine = json.isNull("engine") ? null : json.optString("engine", null);
        recipe.version = json.optInt("version", 0);
        recipe.exe = json.isNull("exe") ? null : json.optString("exe", null);
        recipe.wide = json.optBoolean("wide", true);
        recipe.api = json.optString("api", recipe.api);
        recipe.renderer = json.optString("renderer", recipe.renderer);
        recipe.tier = json.optInt("tier", recipe.tier);
        recipe.antiCheat = json.isNull("antiCheat") ? null : json.optString("antiCheat", null);
        recipe.profile = json.optString("profile", recipe.profile);
        recipe.expect = json.optString("expect", "");
        recipe.source = json.optString("source", recipe.source);

        JSONArray saw = json.optJSONArray("saw");

        if (saw != null) {
            for (int i = 0; i < saw.length(); i++) {
                recipe.saw.add(saw.optString(i));
            }
        }

        JSONArray args = json.optJSONArray("args");

        if (args != null) {
            for (int i = 0; i < args.length(); i++) {
                recipe.args.add(args.optString(i));
            }
        }

        JSONObject vars = json.optJSONObject("env");

        if (vars != null) {
            java.util.Iterator<String> keys = vars.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                recipe.env.put(key, vars.optString(key, ""));
            }
        }

        return recipe;
    }
}
