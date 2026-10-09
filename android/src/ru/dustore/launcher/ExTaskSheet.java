package ru.dustore.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** A dismissible view of the durable operation; closing this sheet never loses or cancels work. */
final class ExTaskSheet {
    static void show(Activity activity) { show(activity, null); }
    static void show(Activity activity, Library.Entry entry) {
        ExTask task = ExTask.get(activity);
        final Library.Entry target = entry != null ? entry : activity instanceof MainActivity
                ? task.entry(((MainActivity) activity).library()) : null;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        ExTaskBinding.Root surface = new ExTaskBinding.Root(activity) {
            @Override void hostDestroyed() { dialog.dismiss(); }
            @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
                super.onConfigurationChanged(configuration);
                size(dialog, activity);
            }
        };
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackground(Ui.panel(activity));
        Ui.lift(surface, Ui.R_CARD);
        LinearLayout root = Ui.column(activity);
        android.content.res.Configuration configuration = activity.getResources().getConfiguration();
        boolean compact = configuration.screenHeightDp < 450;
        int spacing = Ui.dp(activity, compact ? 14 : 20);
        root.setPadding(spacing, spacing, spacing, spacing);
        TextView kind = Ui.chip(activity, "КОНВЕРТАЦИЯ", Ui.ACCENT);
        kind.setVisibility(compact ? View.GONE : View.VISIBLE);
        root.addView(kind);
        if (!compact) root.addView(Ui.gap(activity, 10));
        TextView title = Ui.display(activity, "Операция eX", compact && configuration.fontScale >= 1.5f ? Ui.T_HEAD : Ui.T_TITLE);
        title.setMaxLines(compact && configuration.fontScale >= 1.5f ? 1 : 2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END); root.addView(title);
        TextView subtitle = Ui.paragraph(activity, "", Ui.T_LABEL, Ui.MUTED);
        subtitle.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 14));
        TextView stage = Ui.title(activity, "", compact && configuration.fontScale >= 1.5f ? Ui.T_BODY : Ui.T_HEAD, Ui.TEXT);
        stage.setPadding(0, Ui.dp(activity, compact ? 8 : 12), 0, Ui.dp(activity, compact ? 8 : 12));
        stage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(stage);
        ExTaskVisual visual = new ExTaskVisual(activity, compact || configuration.fontScale > 1.4f);
        root.addView(visual, new LinearLayout.LayoutParams(-1, -2)); root.addView(Ui.gap(activity, 14));
        TextView detail = Ui.paragraph(activity, "", Ui.T_LABEL, Ui.DIM_FIXED);
        detail.setMaxLines(5); detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.setTextIsSelectable(true); root.addView(detail);
        TextView transfer = Ui.paragraph(activity, "", Ui.T_LABEL, Ui.ACCENT_HOVER);
        transfer.setPadding(0, Ui.dp(activity, 8), 0, 0); transfer.setVisibility(View.GONE); root.addView(transfer);
        TextView elapsed = Ui.text(activity, "", Ui.T_MICRO, Ui.DIM);
        elapsed.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 14)); root.addView(elapsed);
        root.addView(subtitle);

        TextView journalToggle = Ui.button(activity, "Показать журнал", false); root.addView(journalToggle);
        ScrollView journalScroll = new ScrollView(activity);
        TextView journal = Ui.text(activity, "", Ui.T_MICRO, Ui.MUTED);
        journal.setTypeface(android.graphics.Typeface.MONOSPACE); journal.setTextIsSelectable(true);
        journal.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 12));
        journal.setBackground(Ui.rounded(activity, Ui.CARD_PRESSED, 12));
        android.widget.HorizontalScrollView horizontalJournal = new android.widget.HorizontalScrollView(activity);
        journal.setHorizontallyScrolling(true); horizontalJournal.addView(journal); journalScroll.addView(horizontalJournal);
        journalScroll.setVisibility(View.GONE);
        root.addView(journalScroll, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 166)));
        journalToggle.setOnClickListener(v -> {
            boolean visible = journalScroll.getVisibility() != View.VISIBLE;
            journalScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
            journalToggle.setText(visible ? "Скрыть журнал" : "Показать журнал");
            if (visible) journal.setText(android.text.TextUtils.join("\n", task.snapshot().log));
        });
        TextView copy = Ui.button(activity, "Скопировать диагностику", false);
        root.addView(copy);
        copy.setOnClickListener(v -> UiWork.execute(() -> {
            String text = fullLog(activity.getApplicationContext());
            if (text.isEmpty()) text = android.text.TextUtils.join("\n", task.snapshot().log);
            final String content = text;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                try {
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("eX diagnostics", content));
                    Toast.makeText(activity, "Последние строки диагностики скопированы", Toast.LENGTH_SHORT).show();
                } catch (RuntimeException unavailable) { Toast.makeText(activity, "Не удалось скопировать журнал", Toast.LENGTH_SHORT).show(); }
            });
        }));
        TextView full = Ui.button(activity, "Открыть полный файл журнала", false); root.addView(full);
        full.setOnClickListener(v -> {
            android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            view.setDataAndType(Files.diagnosticLog(), "text/plain");
            view.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { activity.startActivity(view); }
            catch (Exception unavailable) { Toast.makeText(activity, "Для полного журнала нужен просмотрщик текстовых файлов", Toast.LENGTH_LONG).show(); }
        });
        LinearLayout footer = Ui.column(activity);
        footer.setPadding(spacing, Ui.dp(activity, 12), spacing, Ui.dp(activity, 16));
        footer.setBackgroundColor(Ui.alpha(Ui.BACK_SOFT, 235));
        TextView action = Ui.button(activity, "Остановить", false);
        TextView play = Ui.button(activity, "Играть в eX", true);
        TextView install = Ui.button(activity, "Установить APK игры", false);
        TextView close = Ui.button(activity, "Свернуть", false);
        LinearLayout actions = new TaskActions(activity, action, play, close);
        play.setVisibility(View.GONE); install.setVisibility(View.GONE);
        LinearLayout.LayoutParams installPosition = new LinearLayout.LayoutParams(-1, -2);
        installPosition.bottomMargin = Ui.dp(activity, 8);
        footer.addView(install, installPosition);
        LinearLayout.LayoutParams actionPosition = new LinearLayout.LayoutParams(0, -2, 1f);
        actionPosition.rightMargin = Ui.dp(activity, 8);
        actions.addView(action, actionPosition);
        LinearLayout.LayoutParams playPosition = new LinearLayout.LayoutParams(0, -2, 1f);
        playPosition.rightMargin = Ui.dp(activity, 8);
        actions.addView(play, playPosition);
        if (activity instanceof MainActivity && target != null) {
            MainActivity host = (MainActivity) activity;
            play.setOnClickListener(v -> { dialog.dismiss(); host.playEx(target); });
            install.setOnClickListener(v -> { dialog.dismiss(); host.installEx(target); });
        }
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1f)); footer.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        close.setOnClickListener(v -> dialog.dismiss());
        ScrollView content = new ScrollView(activity);
        content.setFillViewport(false); content.setVerticalScrollBarEnabled(true); content.addView(root);
        surface.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        surface.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(surface);
        dialog.setCancelable(true);
        action.setOnClickListener(v -> {
            ExTask.Snapshot current = task.snapshot();
            if (current.busy()) { task.cancel(); return; }
            if ("convert".equals(current.kind) && activity instanceof MainActivity && target != null) {
                dialog.dismiss(); ((MainActivity) activity).emulate(target); return;
            }
            String problem = "proof".equals(current.kind) ? task.proof() : task.setup();
            if (problem != null) Toast.makeText(activity, problem, Toast.LENGTH_LONG).show();
            else { dialog.dismiss(); ExTaskSheet.show(activity); }
        });

        ExTaskBinding.Changed listener = (snapshot, transition) -> {
            if (activity.isDestroyed()) return;
            text(kind, "setup".equals(snapshot.kind) ? "СИСТЕМА eX" : "proof".equals(snapshot.kind) ? "ПРОВЕРКА eX" : "КОНВЕРТАЦИЯ");
            text(title, snapshot.title.isEmpty() ? "Операция eX" : snapshot.title);
            text(subtitle, snapshot.busy() ? "Можно свернуть окно: подготовка продолжается, её состояние остаётся в разделе eX."
                    : ExTask.INTERRUPTED.equals(snapshot.state) ? "Завершённые этапы сохранены. Повторите подготовку; eX проверит имеющиеся файлы."
                    : "Результат сохранён. Журнал помогает найти причину, если подготовка прервалась.");
            String status = (snapshot.busy() && snapshot.step > 0 ? "Этап " + snapshot.step + " из " + snapshot.total + " · " : "") + snapshot.stateText();
            boolean changedStage = !android.text.TextUtils.equals(stage.getText(), status);
            text(stage, status);
            stage.setTextColor(ExTask.DONE.equals(snapshot.state) ? Ui.SUCCESS
                    : ExTask.FAILED.equals(snapshot.state) || ExTask.INTERRUPTED.equals(snapshot.state) ? Ui.DANGER
                    : ExTask.CANCELLED.equals(snapshot.state) || ExTask.CANCELLING.equals(snapshot.state) ? Ui.WARNING : Ui.TEXT);
            if (transition && changedStage) Motion.reveal(stage, 0, 1);
            text(detail, snapshot.error.isEmpty() ? snapshot.detail : snapshot.error);
            transfer.setVisibility(snapshot.hasTransfer() ? View.VISIBLE : View.GONE);
            if (snapshot.hasTransfer()) text(transfer, snapshot.transferLabel + " · " + transferText(snapshot));
            String time = duration(snapshot.elapsedMillis);
            text(elapsed, (snapshot.busy() ? "В работе " : "Длительность ") + time);
            if (!snapshot.busy() && journalScroll.getVisibility() == View.VISIBLE)
                journal.setText(android.text.TextUtils.join("\n", snapshot.log));
            text(close, snapshot.busy() ? "Свернуть" : "Закрыть");
            close.setContentDescription(snapshot.busy() ? "Вернуться к приложению. Подготовка продолжится" : "Закрыть результат eX");
            boolean retry = !snapshot.busy() && !ExTask.DONE.equals(snapshot.state);
            boolean canRetry = !"convert".equals(snapshot.kind) || target != null && activity instanceof MainActivity;
            action.setVisibility(snapshot.busy() || retry && canRetry ? View.VISIBLE : View.GONE);
            text(action, snapshot.busy() ? ExTask.CANCELLING.equals(snapshot.state) ? "Останавливаем…" : !snapshot.cancellable ? "Сохраняем…" : "Остановить" : "Повторить");
            action.setEnabled(!snapshot.busy() || snapshot.cancellable);
            action.setContentDescription(!snapshot.busy() ? "Повторить подготовку"
                    : ExTask.CANCELLING.equals(snapshot.state) ? "Останавливаем текущую операцию eX"
                    : !snapshot.cancellable ? "Записываем результат. Отмена временно недоступна"
                    : "Остановить текущую операцию eX");
            boolean finishedGame = ExTask.DONE.equals(snapshot.state) && "convert".equals(snapshot.kind)
                    && target != null && activity instanceof MainActivity;
            play.setVisibility(finishedGame ? View.VISIBLE : View.GONE);
            install.setVisibility(finishedGame ? View.VISIBLE : View.GONE);
        };
        ExTaskBinding binding = new ExTaskBinding(activity, surface, visual, listener, (snapshot, milliseconds) -> {
            text(elapsed, (snapshot.busy() ? "В работе " : "Длительность ") + duration(milliseconds));
            if (journalScroll.getVisibility() == View.VISIBLE) text(journal, android.text.TextUtils.join("\n", snapshot.log));
        });
        dialog.setOnDismissListener(d -> binding.close());
        dialog.show();
        size(dialog, activity);
    }

    /** Measure real text, including future busy labels, before choosing two columns. */
    private static final class TaskActions extends LinearLayout {
        private static final String[] BUSY = { "Остановить", "Останавливаем…", "Сохраняем…", "Повторить" };
        private static final String[] CLOSE = { "Свернуть", "Закрыть" };
        private final TextView action, play, close;
        private final int gap;
        TaskActions(android.content.Context context, TextView action, TextView play, TextView close) {
            super(context); this.action = action; this.play = play; this.close = close;
            gap = Ui.dp(context, 8); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
        }
        private static float needed(TextView button, String[] labels) {
            float width = 0;
            for (String label : labels) width = Math.max(width, button.getPaint().measureText(label));
            return width + button.getCompoundPaddingLeft() + button.getCompoundPaddingRight();
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int visible = 0;
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) visible++;
            float widest = needed(close, CLOSE);
            if (action.getVisibility() != GONE) widest = Math.max(widest, needed(action, BUSY));
            if (play.getVisibility() != GONE) widest = Math.max(widest, play.getPaint().measureText("Играть в eX") + play.getCompoundPaddingLeft() + play.getCompoundPaddingRight());
            int column = Math.max(Ui.dp(getContext(), 48), (int) Math.ceil(widest));
            boolean stack = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && visible > 1
                    && available < visible * column + (visible - 1) * gap;
            int orientation = stack ? VERTICAL : HORIZONTAL;
            if (getOrientation() != orientation) setOrientation(orientation);
            int position = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                boolean shown = child.getVisibility() != GONE;
                if (shown) position++;
                LayoutParams params = (LayoutParams) child.getLayoutParams();
                int width = stack ? LayoutParams.MATCH_PARENT : 0;
                float weight = stack ? 0f : 1f;
                int after = shown && position < visible ? gap : 0;
                int right = stack ? 0 : after, bottom = stack ? after : 0;
                if (params.width != width || params.weight != weight || params.rightMargin != right || params.bottomMargin != bottom) {
                    params.width = width; params.height = LayoutParams.WRAP_CONTENT; params.weight = weight;
                    params.rightMargin = right; params.bottomMargin = bottom; child.setLayoutParams(params);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private static void size(Dialog dialog, Activity activity) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 20), Ui.dp(activity, 600));
            int height = Math.min(activity.getResources().getDisplayMetrics().heightPixels - Ui.dp(activity, 50), Ui.dp(activity, 680));
            dialog.getWindow().setLayout(width, height);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }
    }

    static void text(TextView view, String value) {
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    static String transferText(ExTask.Snapshot snapshot) {
        return bytes(snapshot.completedBytes) + (snapshot.totalBytes > 0 ? " из " + bytes(snapshot.totalBytes) : " · объём неизвестен");
    }

    private static String bytes(long bytes) {
        if (bytes < 1024) return Math.max(0, bytes) + " Б";
        if (bytes < 1048576) return String.format(java.util.Locale.getDefault(), "%.1f КБ", bytes / 1024d);
        if (bytes < 1073741824) return String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / 1048576d);
        return String.format(java.util.Locale.getDefault(), "%.2f ГБ", bytes / 1073741824d);
    }

    static String duration(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds < 60 ? seconds + " с" : seconds / 60 + " мин " + seconds % 60 + " с";
    }

    private static String fullLog(android.content.Context context) {
        File file = new File(context.getExternalFilesDir(null), "ex.log");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, input.length() - 128 * 1024);
            input.seek(start); byte[] text = new byte[(int) (input.length() - start)]; input.readFully(text);
            return new String(text, StandardCharsets.UTF_8);
        } catch (Exception missing) { return ""; }
    }
}
