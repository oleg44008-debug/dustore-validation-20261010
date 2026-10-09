package ru.dustore.launcher;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Real environment status and a durable, cancellable task shared by both editions. */
final class ExScreen extends ExTaskBinding.Root {
    private final Activity activity;
    private final ExTask task;
    private final TextView state, gpu, install, check, taskStatus, taskOpen, quota;
    private final TextView taskBytes;
    private final ExTaskVisual taskVisual;
    private final ExTaskBinding taskBinding;
    private final LinearLayout parts;
    private int refreshGeneration;
    private int environmentStage;
    private boolean environmentReady;
    private boolean refreshing;
    private String observedState = "";
    private boolean stylesApplied, styledReady;

    ExScreen(Activity activity) {
        super(activity);
        this.activity = activity;
        task = ExTask.get(activity);
        setOrientation(VERTICAL);
        setBackground(Ui.panel(activity)); Ui.lift(this, Ui.R_CARD);
        int inset = Ui.dp(activity, 20); setPadding(inset, inset, inset, inset);
        LinearLayout.LayoutParams location = new LinearLayout.LayoutParams(-1, -2);
        location.bottomMargin = Ui.dp(activity, 18); setLayoutParams(location);

        LinearLayout heading = Ui.row(activity); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.display(activity, "eX", Ui.T_TITLE), new LayoutParams(0, -2, 1));
        TextView mark = Ui.chip(activity, "ПК → телефон", Ui.ACCENT); heading.addView(mark); addView(heading);
        TextView intro = Ui.paragraph(activity, "Windows-игры запускаются на этом телефоне. Выберите скачанную игру в библиотеке и нажмите «Эмулировать».", Ui.T_LABEL, Ui.MUTED);
        intro.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 18)); addView(intro);
        state = Ui.title(activity, "Проверяем среду…", Ui.T_BODY, Ui.TEXT);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); addView(state);
        gpu = Ui.paragraph(activity, "", Ui.T_MICRO, Ui.DIM);
        gpu.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 16)); addView(gpu);
        quota = Ui.paragraph(activity, "", Ui.T_MICRO, Ui.DIM); addView(quota);
        addView(Ui.gap(activity, 12));

        taskVisual = new ExTaskVisual(activity, true); taskVisual.setVisibility(GONE);
        addView(taskVisual, new LayoutParams(-1, -2));
        taskStatus = Ui.paragraph(activity, "", Ui.T_LABEL, Ui.TEXT);
        taskStatus.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14));
        taskStatus.setBackground(Ui.rounded(activity, Ui.CARD_PRESSED, 14));
        taskStatus.setVisibility(GONE); addView(taskStatus);
        taskStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        taskBytes = Ui.paragraph(activity, "", Ui.T_MICRO, Ui.ACCENT_HOVER);
        taskBytes.setPadding(0, Ui.dp(activity, 8), 0, 0); taskBytes.setVisibility(GONE); addView(taskBytes);
        taskOpen = Ui.button(activity, "Открыть состояние eX", false);
        taskOpen.setOnClickListener(v -> ExTaskSheet.show(activity));
        taskOpen.setVisibility(GONE); addView(taskOpen, wide());
        install = Ui.button(activity, "Подготовить систему eX", true);
        install.setOnClickListener(v -> begin(environmentReady)); addView(install, wide());
        check = Ui.button(activity, "Проверить систему", false);
        check.setOnClickListener(v -> begin(true)); addView(check, wide());

        TextView more = Ui.button(activity, "Компоненты и совместимость  ↓", false);
        addView(more);
        LinearLayout technical = Ui.column(activity); technical.setVisibility(GONE);
        TextView about = Ui.paragraph(activity, "Wine и Box64 переводят Windows-код, Mesa Turnip и DXVK отвечают за графику. Поддержка зависит от игры и устройства. Для первого запуска eX нужны сеть и свободное место; подготовка проверяет их сама.", Ui.T_MICRO, Ui.DIM);
        about.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 14)); technical.addView(about);
        parts = Ui.column(activity); technical.addView(parts); addView(technical);
        more.setOnClickListener(v -> {
            boolean open = technical.getVisibility() != VISIBLE;
            technical.setVisibility(open ? VISIBLE : GONE);
            more.setText(open ? "Скрыть компоненты  ↑" : "Компоненты и совместимость  ↓");
            if (open) Motion.reveal(technical, 0, 1); else Motion.reset(technical);
        });
        taskBinding = new ExTaskBinding(activity, this, taskVisual, (snapshot, transition) -> taskChanged(snapshot), null);
        taskChanged(task.snapshot()); refresh();
    }

    private LayoutParams wide() {
        LayoutParams value = new LayoutParams(-1, -2); value.topMargin = Ui.dp(activity, 8); return value;
    }

    void refresh() {
        if (refreshing) return;
        refreshing = true;
        int version = ++refreshGeneration;
        android.content.Context app = activity.getApplicationContext();
        UiWork.executeHeavy(() -> {
            int stage = Ex.stage(app);
            boolean ready = ExNative.ready(app);
            boolean gpuReady = Ex.gpuReady(app);
            String gpuName = Ex.gpuName(app);
            ExParts.Part[] components = ExParts.all();
            boolean[] have = new boolean[components.length];
            for (int i = 0; i < have.length; i++) have[i] = ExParts.have(app, components[i]) || components[i] == ExParts.HANGOVER && ready;
            String remaining = Prime.ON ? "Prime · переносы eX без очереди и дневного лимита" : Prime.remaining(app);
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || version != refreshGeneration) return;
                refreshing = false; environmentStage = stage; environmentReady = ready;
                state.setText(ready ? "Система готова" : stage > 0 ? "Есть готовые этапы · можно продолжить" : "Система ещё не подготовлена");
                state.setTextColor(ready ? Ui.SUCCESS : stage > 0 ? Ui.WARNING : Ui.MUTED);
                gpu.setText(stage >= Ex.STAGE_GPU ? gpuReady ? gpuName + " · Turnip" : "Программная графика · возможности 3D ограничены" : "Компоненты загрузятся при подготовке");
                quota.setText(remaining);
                parts.removeAllViews();
                for (int i = 0; i < components.length; i++) {
                    TextView line = Ui.paragraph(activity, (have[i] ? "✓  " : "○  ") + components[i].what + (have[i] ? " · проверено" : " · загрузится при необходимости"), Ui.T_MICRO, have[i] ? Ui.TEXT : Ui.DIM);
                    line.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 6)); parts.addView(line);
                }
                taskChanged(task.snapshot());
            });
        });
    }

    private void taskChanged(ExTask.Snapshot snapshot) {
        if (activity.isDestroyed()) return;
        boolean busy = snapshot.busy();
        taskStatus.setVisibility(snapshot.present() ? VISIBLE : GONE);
        taskVisual.setVisibility(snapshot.present() ? VISIBLE : GONE);
        taskOpen.setVisibility(snapshot.present() ? VISIBLE : GONE);
        ExTaskSheet.text(taskStatus, snapshot.title + "\n" + snapshot.stateText()
                + (snapshot.error.isEmpty() ? snapshot.busy() && !snapshot.detail.equals(snapshot.stage) ? "\n" + snapshot.detail : "" : "\n" + snapshot.error));
        taskStatus.setTextColor(ExTask.DONE.equals(snapshot.state) ? Ui.SUCCESS
                : ExTask.FAILED.equals(snapshot.state) || ExTask.INTERRUPTED.equals(snapshot.state) ? Ui.DANGER : Ui.TEXT);
        taskBytes.setVisibility(snapshot.hasTransfer() ? VISIBLE : GONE);
        if (snapshot.hasTransfer()) ExTaskSheet.text(taskBytes, snapshot.transferLabel + " · " + ExTaskSheet.transferText(snapshot));
        ExTaskSheet.text(taskOpen, busy ? "Открыть прогресс и отмену" : "Результат и журнал");
        ExTaskSheet.text(install, busy ? "Подготовка уже выполняется" : environmentReady ? "Проверить готовность eX" : environmentStage > 0 ? "Продолжить подготовку eX" : "Подготовить систему eX");
        install.setEnabled(!busy); check.setEnabled(!busy && environmentStage > 0);
        if (!stylesApplied || styledReady != environmentReady) {
            stylesApplied = true; styledReady = environmentReady;
            Ui.asFilled(install, !environmentReady); Ui.asFilled(check, environmentReady);
        }
        if (!observedState.equals(snapshot.state)) {
            observedState = snapshot.state;
            if (!busy && snapshot.present()) refresh();
        }
    }

    private void begin(boolean verify) {
        String problem = verify ? task.proof() : task.setup();
        if (problem != null) android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show();
        else ExTaskSheet.show(activity);
    }

}
