package ru.dustore.launcher;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Экран библиотеки: что скачано, что установлено, во что играли.
 *
 * Это не список файлов: карточка ведёт игру от загрузки к установке и запуску.
 *
 * Разметка сделана под палец, а не под мышь: одно главное действие на карточку, крупной
 * кнопкой во всю ширину, и никаких мелких надписей рядом, в которые надо целиться.
 *
 * И это карточка игры, а не строка списка. Слева обложка — у установленной игры её
 * собственный значок, у остальных плитка, посчитанная из названия; цвет в этот экран
 * приносят игры, интерфейс остаётся тёмным и тихим. Состояние вынесено в метку: цвет
 * говорит быстрее слова, «Установлена» зелёным читается, не читая.
 */
final class LibraryScreen extends ScrollView {

    interface Host {
        void install(Library.Entry entry);

        void play(Library.Entry entry);

        void openPage(Library.Entry entry);

        /** Windows-сборка: эмулировать через eX — распаковать, разобрать, собрать APK. */
        void emulate(Library.Entry entry);

        /** Эмулированная игра: открыть в eX прямо сейчас. */
        void playEx(Library.Entry entry);

        /** Эмулированная игра: поставить её APK-стаб как приложение. */
        void installEx(Library.Entry entry);

        void forget(Library.Entry entry);
        void cancelDownload(Library.Entry entry);

        /** Пустая библиотека ведёт в магазин. */
        void openStore();

    }

    /** Сторона обложки. Крупнее — и карточка перестаёт помещаться в экран. */
    private static final int COVER = 88;

    private final Activity activity;
    private final Library library;
    private final Host host;
    private final LinearLayout box;
    private LinearLayout collection;
    private TextView libraryCount;
    private EditText search;
    private LinearLayout filterButtons;
    private LinearLayout collectionTools;
    private int filter;
    private boolean newest = true;
    private String query = "";
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.text.Collator titleOrder = java.text.Collator.getInstance();
    private List<LibraryState> snapshot = new ArrayList<>();
    private final java.util.Map<Library.Entry, LibraryState> states = new java.util.IdentityHashMap<>();
    private final java.util.Map<Library.Entry, CachedCard> cards = new java.util.IdentityHashMap<>();
    private int snapshotGeneration;
    private boolean loadingSnapshot, refreshAgain;
    private int cardLayoutStyle = -1;
    private final Runnable searchRefresh = () -> renderGames(false);
    private final Runnable downloadRefresh = this::refresh;
    private static final class CachedCard {
        final String signature; final LinearLayout view;
        final TextView progressText; LibraryState metadata;
        CachedCard(String signature, LinearLayout view, LibraryState metadata) {
            this.signature = signature; this.view = view; this.metadata = metadata;
            progressText = view.findViewWithTag("library-meta");
        }
    }

    LibraryScreen(Activity activity, Library library, Host host) {
        super(activity);

        this.activity = activity;
        this.library = library;
        this.host = host;

        SeasonScene.apply(this);

        // Врезки — на самом списке, а не на его содержимом: заголовок иначе уезжает под
        // часы, а последняя карточка прячется за навигационной полосой.
        Ui.fit(this, true, false, true);

        box = Ui.column(activity);
        box.setPadding(Ui.dp(activity, Ui.S4), Ui.dp(activity, Ui.S4),
                Ui.dp(activity, Ui.S4), Ui.dp(activity, Ui.S6));
        setClipToPadding(false);
        addView(box);
        buildHeader();
        refresh();
    }

    private void buildHeader() {
        LinearLayout heading = Ui.row(activity);
        boolean stackedHeading = activity.getResources().getConfiguration().fontScale >= 1.5f
                && activity.getResources().getConfiguration().screenWidthDp < 600;
        if (stackedHeading) heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = Ui.column(activity);
        words.addView(Ui.display(activity, "Библиотека", Ui.T_DISPLAY));
        libraryCount = Ui.text(activity, "", Ui.T_LABEL, Ui.DIM);
        libraryCount.setPadding(0, Ui.dp(activity, 6), 0, 0);
        words.addView(libraryCount);
        heading.addView(words, stackedHeading ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView edition = Prime.mark(activity);
        LinearLayout.LayoutParams editionParams = new LinearLayout.LayoutParams(-2, -2);
        if (stackedHeading) { editionParams.topMargin = Ui.dp(activity, 8); editionParams.gravity = Gravity.START; }
        heading.addView(edition, editionParams);
        box.addView(heading);
        box.addView(Ui.gap(activity, 22));

        collectionTools = Ui.column(activity);
        box.addView(collectionTools);

        search = new EditText(activity);
        search.setTextSize(Ui.T_BODY);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setHint("Найти вашу игру");
        search.setContentDescription("Поиск по библиотеке");
        search.setSingleLine(true);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(Ui.field(activity));
        search.setMinHeight(Ui.dp(activity, 52));
        search.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 10), Ui.dp(activity, 16), Ui.dp(activity, 10));
        Ui.setIcon(search, "search", 18, Ui.DIM);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                ui.removeCallbacks(searchRefresh);
                if (collection != null) ui.postDelayed(searchRefresh, 100);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(search.getWindowToken(), 0);
                search.clearFocus();
                return true;
            }
            return false;
        });
        collectionTools.addView(search, new LinearLayout.LayoutParams(-1, -2));
        box.setFocusableInTouchMode(true);
        box.requestFocus();
        collectionTools.addView(Ui.gap(activity, 14));

        HorizontalScrollView filters = new HorizontalScrollView(activity);
        filters.setHorizontalScrollBarEnabled(false);
        filterButtons = Ui.row(activity);
        String[] names = { "Все игры", "Готовые", "eX / Windows", "Android", "Веб" };
        for (int i = 0; i < names.length; i++) {
            final int selected = i;
            TextView option = Ui.button(activity, names[i], false);
            option.setTextSize(Ui.T_LABEL);
            option.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 10), Ui.dp(activity, 14), Ui.dp(activity, 10));
            option.setOnClickListener(v -> { filter = selected; updateFilters(); renderGames(true); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = Ui.dp(activity, 8);
            filterButtons.addView(option, params);
        }
        filters.addView(filterButtons);
        collectionTools.addView(filters);
        collectionTools.addView(Ui.gap(activity, 16));
        LinearLayout shelf = Ui.row(activity);
        shelf.setGravity(Gravity.CENTER_VERTICAL);
        TextView collectionTitle = Ui.title(activity, "ВАША КОЛЛЕКЦИЯ", Ui.T_MICRO, Ui.DIM);
        collectionTitle.setLetterSpacing(.1f);
        shelf.addView(collectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sort = Ui.button(activity, "Новые ↓", false);
        sort.setTextSize(Ui.T_MICRO);
        sort.setMinHeight(Ui.dp(activity, 48));
        sort.setBackground(null);
        sort.setOnClickListener(v -> {
            newest = !newest;
            sort.setText(newest ? "Новые ↓" : "По названию ↓");
            renderGames(true);
        });
        shelf.addView(sort);
        collectionTools.addView(shelf);
        collection = Ui.column(activity);
        box.addView(collection);
        updateFilters();
    }

    private void updateFilters() {
        for (int i = 0; i < filterButtons.getChildCount(); i++) {
            TextView option = (TextView) filterButtons.getChildAt(i);
            option.setSelected(i == filter);
            option.setTextColor(i == filter ? Ui.TEXT : Ui.MUTED);
            option.setBackground(Ui.glass(activity, 18, i == filter));
        }
    }

    void refresh() {
        if (loadingSnapshot) { refreshAgain = true; return; }
        loadingSnapshot = true;
        int generation = ++snapshotGeneration;
        ui.removeCallbacks(downloadRefresh);
        android.content.Context app = activity.getApplicationContext();
        UiWork.execute(() -> {
            List<LibraryState> loaded = LibraryState.load(app, library);
            ui.post(() -> {
                loadingSnapshot = false;
                if (activity.isDestroyed()) return;
                if (refreshAgain) { refreshAgain = false; refresh(); return; }
                if (generation != snapshotGeneration) return;
                snapshot = loaded; states.clear();
                for (LibraryState state : loaded) states.put(state.entry, state);
                cards.keySet().removeIf(entry -> !states.containsKey(entry));
                if (isShown()) renderGames(false);
                boolean downloading = false;
                for (LibraryState state : loaded) downloading |= state.activeDownload();
                if (downloading && isShown() && getWindowVisibility() == VISIBLE)
                    ui.postDelayed(downloadRefresh, 1200);
            });
        });
    }

    private void renderGames(boolean animate) {
        int style = layoutStyle();
        if (style != cardLayoutStyle) { cards.clear(); cardLayoutStyle = style; }
        int count = snapshot.size();
        collectionTools.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        libraryCount.setText(count == 0 ? "Ваши игры появятся здесь" : count + " " + games(count) + " · на этом устройстве");

        if (snapshot.isEmpty()) {
            clearCollection();
            collection.addView(empty());
            return;
        }

        List<Library.Entry> found = new ArrayList<>();
        for (LibraryState metadata : snapshot) {
            Library.Entry entry = metadata.entry;
            boolean matches = metadata.normalizedTitle.contains(query);
            if (!matches) continue;
            if (filter == 1 && !metadata.playable()) continue;
            if (filter == 2 && !entry.isPc()) continue;
            if (filter == 3 && !entry.isApp()) continue;
            if (filter == 4 && !entry.isWeb()) continue;
            found.add(entry);
        }
        if (newest) found.sort((a, b) -> Long.compare(b.addedUtc, a.addedUtc));
        else found.sort((a, b) -> titleOrder.compare(a.title == null ? "" : a.title, b.title == null ? "" : b.title));
        java.util.ArrayList<View> wanted = new java.util.ArrayList<>(found.size());
        for (Library.Entry entry : found) {
            LibraryState metadata = states.get(entry);
            CachedCard cached = cards.get(entry);
            if (cached == null || !cached.signature.equals(metadata.signature)) {
                cached = new CachedCard(metadata.signature, card(entry), metadata); cards.put(entry, cached);
            }
            TextView progressText = cached.progressText;
            if (progressText != null && cached.metadata != metadata) {
                String text = meta(entry);
                progressText.setText(text == null ? "" : text);
                progressText.setVisibility(text == null ? GONE : VISIBLE);
            }
            cached.metadata = metadata;
            wanted.add(cached.view);
        }
        java.util.Set<View> wantedSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        wantedSet.addAll(wanted);
        for (int i = collection.getChildCount() - 1; i >= 0; i--) {
            View child = collection.getChildAt(i);
            if (!wantedSet.contains(child)) { Motion.reset(child); collection.removeViewAt(i); }
        }
        for (int i = 0; i < wanted.size(); i++) {
            View child = wanted.get(i);
            if (collection.getChildAt(i) != child) {
                if (child.getParent() == collection) collection.removeView(child);
                collection.addView(child, i);
            }
        }
        if (!query.isEmpty() || filter != 0) libraryCount.setText(found.size() + " из " + count + " · на этом устройстве");
        if (found.isEmpty()) {
            LinearLayout empty = panel();
            empty.addView(Ui.title(activity, "Ничего не найдено", Ui.T_HEAD, Ui.TEXT));
            empty.addView(Ui.gap(activity, 8));
            empty.addView(Ui.paragraph(activity, "Измените поиск или выберите другой фильтр.", Ui.T_LABEL, Ui.DIM_FIXED));
            TextView clear = Ui.button(activity, "Сбросить поиск", false);
            clear.setOnClickListener(v -> { filter = 0; updateFilters(); search.setText(""); ui.removeCallbacks(searchRefresh); renderGames(true); });
            empty.addView(Ui.gap(activity, 14));
            empty.addView(clear);
            collection.addView(empty);
        }
        if (animate) Motion.revealChildren(collection);
    }

    private void clearCollection() {
        for (int i = 0; i < collection.getChildCount(); i++) Motion.reset(collection.getChildAt(i));
        collection.removeAllViews();
    }

    private static String games(int count) {
        int tail = count % 100;
        if (tail >= 11 && tail <= 14) return "игр";
        switch (count % 10) {
            case 1: return "игра";
            case 2: case 3: case 4: return "игры";
            default: return "игр";
        }
    }

    /** Пусто — это тоже экран: крупно, что делать, и одна кнопка, которая это делает. */
    private LinearLayout empty() {
        LinearLayout card = panel();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean shortWindow = activity.getResources().getConfiguration().screenHeightDp < 500;
        int pad = Ui.dp(activity, shortWindow ? Ui.S5 : Ui.S6);
        card.setPadding(pad, pad, pad, pad);

        ImageView artwork = new ImageView(activity);
        artwork.setImageResource(R.drawable.brand_empty);
        artwork.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(artwork, new LinearLayout.LayoutParams(Ui.dp(activity, shortWindow ? 80 : 164), Ui.dp(activity, shortWindow ? 64 : 132)));
        card.addView(Ui.gap(activity, shortWindow ? 12 : 16));

        TextView title = Ui.display(activity, "Пока здесь пусто", Ui.T_TITLE);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView what = Ui.paragraph(activity,
                "Скачайте игру в магазине Dustore — она появится здесь. Windows-игры "
                        + "запускаются на телефоне через eX.",
                Ui.T_BODY, Ui.DIM_FIXED);
        what.setGravity(Gravity.CENTER);
        what.setPadding(0, Ui.dp(activity, Ui.S2), 0, Ui.dp(activity, Ui.S4));
        card.addView(what);

        TextView store = Ui.button(activity, "Открыть магазин", true);
        store.setPadding(Ui.dp(activity, Ui.S6), Ui.dp(activity, Ui.S3), Ui.dp(activity, Ui.S6), Ui.dp(activity, Ui.S3));
        store.setOnClickListener(v -> host.openStore());
        card.addView(store, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return card;
    }

    // ------------------------------------------------------------- карточка игры ----

    private LinearLayout card(final Library.Entry entry) {
        LinearLayout card = panel();

        LibraryState metadata = states.get(entry);
        boolean installed = metadata.installed;
        boolean ready = metadata.ready;

        // На широком экране (планшет, телефон лёжа) главная кнопка стоит справа от названия:
        // карточка в одну строку, и на экран помещается несколько игр, а не одна.
        boolean wide = wideCards();
        LinearLayout top = Ui.row(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout head = head(entry, installed, ready);
        top.addView(head, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        LinearLayout actions = Ui.column(activity);
        actions.setPadding(0, wide ? 0 : Ui.dp(activity, Ui.S4), 0, 0);

        // Одно главное действие на карточку, во всю ширину: на телефоне выбор из трёх
        // одинаковых кнопок в ряд — это три способа промахнуться.
        if (entry.isWeb() && ready) {
            actions.addView(big("Играть", true, v -> host.play(entry)));
        } else if (installed) {
            actions.addView(big("Играть", true, v -> host.play(entry)));
        } else if (entry.isPc() && ready) {
            // Сборка для Windows: сначала эмуляция, потом APK, потом игра. Главная кнопка —
            // следующий шаг; предыдущие остаются мелкими, чтобы не путать, где что.
            ExGame game = metadata.game;
            boolean apkReady = metadata.apkReady;
            boolean stubInstalled = metadata.stubInstalled;

            if (game == null || game.recipe == null) {
                actions.addView(big("Эмулировать  ·  eX", true, v -> host.emulate(entry)));
            } else if (stubInstalled) {
                actions.addView(big("Играть  ·  eX", true, v -> host.playEx(entry)));
            } else if (apkReady) {
                actions.addView(big("Установить APK игры", true, v -> host.installEx(entry)));
                actions.addView(big("Играть без установки", false, v -> host.playEx(entry)));
            } else {
                actions.addView(big("Играть  ·  eX", true, v -> host.playEx(entry)));
                actions.addView(big("Собрать APK заново", false, v -> host.emulate(entry)));
            }
        } else if (ready) {
            actions.addView(big("Установить", true, v -> host.install(entry)));
        } else if (metadata.failed || "unknown".equals(entry.kind)) {
            actions.addView(big("Скачать заново", false, v -> host.openPage(entry)));
        } else if (metadata.activeDownload()) {
            actions.addView(big("Отменить загрузку", false, v -> host.cancelDownload(entry)));
        }

        if (actions.getChildCount() > 0 && wide) {
            LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(Ui.dp(activity, 240), ViewGroup.LayoutParams.WRAP_CONTENT);
            side.leftMargin = Ui.dp(activity, Ui.S4);
            top.addView(actions, side);
        } else if (actions.getChildCount() > 0) {
            card.addView(actions);
        }
        for (int i = 0; i < actions.getChildCount(); i++) {
            View action = actions.getChildAt(i);
            if (action instanceof TextView) action.setContentDescription(((TextView) action).getText() + " · " + entry.title);
        }

        card.addView(secondaryRow(entry));

        return card;
    }

    /** Обложка, название и метка состояния — три вещи, по которым игру узнают. */
    private LinearLayout head(Library.Entry entry, boolean installed, boolean ready) {
        LinearLayout row = Ui.row(activity);

        ImageView cover = new ImageView(activity);
        cover.setImageDrawable(cover(entry, installed));
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        Ui.roundCorners(cover, Ui.R_CARD);

        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, COVER), Ui.dp(activity, COVER));
        coverParams.rightMargin = Ui.dp(activity, Ui.S3);
        row.addView(cover, coverParams);

        LinearLayout words = Ui.column(activity);
        words.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = Ui.display(activity, entry.title, Ui.T_HEAD);
        title.setLineSpacing(Ui.dp(activity, 2), 1f);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(title);

        LinearLayout mark = Ui.column(activity);
        mark.setPadding(0, Ui.dp(activity, Ui.S2), 0, 0);
        mark.setGravity(Gravity.CENTER_VERTICAL);
        mark.addView(Ui.chip(activity, state(entry, installed, ready), stateColor(entry,
                installed, ready)), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String meta = meta(entry);
        TextView line = Ui.text(activity, meta == null ? "" : meta, Ui.T_MICRO, Ui.DIM);
        line.setTag("library-meta");
        line.setVisibility(meta == null ? GONE : VISIBLE);
        line.setPadding(0, Ui.dp(activity, 6), 0, 0);
        mark.addView(line);
        row.addView(words);
        if (!wideCards() && activity.getResources().getConfiguration().fontScale >= 1.3f) {
            LinearLayout head = Ui.column(activity); head.addView(row); head.addView(mark);
            return head;
        }
        words.addView(mark);
        return row;
    }

    private boolean wideCards() {
        float density = activity.getResources().getDisplayMetrics().density;
        float widthDp = getWidth() > 0 ? getWidth() / density : activity.getResources().getConfiguration().screenWidthDp;
        return widthDp >= 600 * Math.max(1f, activity.getResources().getConfiguration().fontScale);
    }

    private int layoutStyle() { return (wideCards() ? 1 : 0) | (activity.getResources().getConfiguration().fontScale >= 1.3f ? 2 : 0); }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (collection != null && !snapshot.isEmpty() && layoutStyle() != cardLayoutStyle) {
            ui.removeCallbacks(searchRefresh); ui.post(searchRefresh);
        }
    }

    /**
     * Обложка игры.
     *
     * У установленной игры она настоящая: её собственный значок, тот же, что на домашнем
     * экране, — узнаётся мгновенно и рисуется без единой скачанной картинки. У всех
     * остальных плитка из названия: своя у каждой игры и всегда одна и та же.
     */
    private Drawable cover(Library.Entry entry, boolean installed) {
        LibraryState metadata = states.get(entry);
        if (metadata != null && metadata.icon != null) return metadata.icon;
        return Ui.cover(activity, entry.title);
    }

    /** Одно слово о том, где игра. Всё остальное — ниже и мельче. */
    private String state(Library.Entry entry, boolean installed, boolean ready) {
        LibraryState metadata = states.get(entry);
        if (metadata.failed || metadata.activeDownload()) {
            return metadata.downloadText();
        }

        if (entry.isWeb()) {
            return ready ? "Запускается прямо здесь" : "Готовим…";
        }

        if (entry.isPc() && !ready) {
            return "Скачивается…";
        }

        if (entry.isPc()) {
            if (metadata.game != null && metadata.game.recipe != null) {
                if (metadata.game.probe != null && metadata.game.probe.startsWith("первый кадр")) return "eX · проба дала изображение";
                return "eX · совместимость ещё не подтверждена";
            }
            return entry.folder != null
                    ? "Windows — подключена к eX"
                    : "Windows — пойдёт через eX";
        }

        if (installed) {
            return "Установлена";
        }

        if (ready) {
            return "Скачана — осталось установить";
        }

        if ("unknown".equals(entry.kind)) {
            return "Файл не удалось разобрать";
        }

        return "Скачивается…";
    }

    /** Цвет метки. Спокойные оттенки: зелёное «готово», синее «в работе», красное «беда». */
    private int stateColor(Library.Entry entry, boolean installed, boolean ready) {
        if (states.get(entry).failed || "unknown".equals(entry.kind)) {
            return Ui.DANGER;
        }

        if (entry.isPc()) {
            return Ui.WARNING;
        }

        if (installed || (entry.isWeb() && ready)) {
            return Ui.SUCCESS;
        }

        if (ready) {
            return Ui.ACCENT;
        }

        return Ui.INFO;
    }

    /** Время игры, запущенной через лаунчер. */
    private String meta(Library.Entry entry) {
        StringBuilder text = new StringBuilder();

        if (entry.playedMinutes > 0) {
            text.append("сыграно ").append(Ui.playtime(entry.playedMinutes));
        }
        LibraryState metadata = states.get(entry);
        String progress = metadata == null ? "" : metadata.byteProgress();
        if (!progress.isEmpty()) append(text, progress);
        if (metadata != null && metadata.game != null && metadata.game.recipe != null
                && metadata.game.probe != null && !metadata.game.probe.isEmpty()) append(text, "Проба: " + metadata.game.probe);

        return text.length() == 0 ? null : text.toString();
    }

    private static void append(StringBuilder text, String value) {
        if (text.length() > 0) {
            text.append("   ·   ");
        }

        text.append(value);
    }

    /** Стоит ли на телефоне такое приложение — стаб игры виден через queries в манифесте. */
    private boolean installedPackage(String packageName) {
        try {
            activity.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception missing) {
            return false;
        }
    }

    /** Второстепенное — мелким и в стороне от главной кнопки. */
    private LinearLayout secondaryRow(final Library.Entry entry) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, Ui.S1), 0, 0);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView page = small("Страница игры", Ui.ACCENT, v -> host.openPage(entry));
        TextView remove = small("Удалить…", Ui.DIM, v -> host.forget(entry));
        page.setContentDescription("Страница игры · " + entry.title);
        remove.setContentDescription("Удалить с устройства · " + entry.title);
        if (!wideCards() && activity.getResources().getConfiguration().fontScale >= 1.5f) {
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(page, new LinearLayout.LayoutParams(-1, -2)); row.addView(remove, new LinearLayout.LayoutParams(-1, -2));
            return row;
        }
        row.addView(page);

        View spacer = new View(activity);
        row.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(remove);

        return row;
    }

    /**
     * Мелкое действие — но нажимаемое пальцем.
     *
     * Слово в 13sp занимает шесть миллиметров по высоте, палец накрывает десять. Поэтому
     * у надписи есть невидимый запас сверху и снизу: целиться в буквы не нужно.
     */
    private TextView small(String label, int color, OnClickListener click) {
        TextView view = Ui.text(activity, label, Ui.T_LABEL, color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(Ui.dp(activity, Ui.TOUCH));
        view.setPadding(Ui.dp(activity, Ui.S2), 0, Ui.dp(activity, Ui.S2), 0);
        view.setForeground(Ui.tap(activity, Ui.R_BUTTON));
        view.setClickable(true);
        view.setOnClickListener(click);
        view.setFocusable(true);
        Motion.touch(view);

        return view;
    }

    /** Кнопка во всю ширину и в палец высотой. */
    private TextView big(String label, boolean filled, OnClickListener click) {
        TextView button = Ui.button(activity, label, filled);
        button.setPadding(Ui.dp(activity, Ui.S3), Ui.dp(activity, Ui.S3), Ui.dp(activity, Ui.S3), Ui.dp(activity, Ui.S3));
        button.setOnClickListener(click);
        button.setMinHeight(Ui.dp(activity, Ui.TOUCH + Ui.S1));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(activity, Ui.S2);
        button.setLayoutParams(params);

        return button;
    }

    @Override protected void onDetachedFromWindow() {
        snapshotGeneration++; ui.removeCallbacks(downloadRefresh); ui.removeCallbacks(searchRefresh);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (ui != null && visibility != VISIBLE) ui.removeCallbacks(downloadRefresh);
        else if (collection != null && isShown()) { renderGames(false); refresh(); }
    }

    /** Общая заготовка карточки: слои, кромка, тень и воздух внутри. */
    private LinearLayout panel() {
        LinearLayout card = Ui.column(activity);
        card.setBackground(Ui.panel(activity));
        Ui.lift(card, Ui.R_CARD);

        int pad = Ui.dp(activity, Ui.S4);
        card.setPadding(pad, pad, pad, pad);
        card.setLayoutParams(Ui.wide(activity, Ui.S3));

        return card;
    }
}
