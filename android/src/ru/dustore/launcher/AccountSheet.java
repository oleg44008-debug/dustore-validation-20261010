package ru.dustore.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Вход в аккаунт eXepc: одно окно, три состояния.
 *
 * Почта → код из письма → пароль. Так же, как в настольном лаунчере, и по той же
 * причине: аккаунт нужен, чтобы отзыв нельзя было оставить от чужого имени, а
 * единственная доступная проверка «это правда ты» — письмо на почту.
 *
 * Отдельной кнопки «зарегистрироваться» нет намеренно. Человек вводит почту, а дальше
 * сервер сам говорит, знает он её или нет: новому предложат придумать пароль, знакомому —
 * ввести свой. Выбирать между «войти» и «создать аккаунт» до того, как ты вообще назвал
 * себя, — лишний вопрос, на который половина отвечает неправильно.
 */
final class AccountSheet {

    interface Changed {
        void done();
    }

    private final Activity activity;
    private final Account account;

    AccountSheet(Activity activity, Account account) {
        this.activity = activity;
        this.account = account;
    }

    void show(Changed changed) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(activity, Ui.S3), Ui.dp(activity, Ui.S5),
                Ui.dp(activity, Ui.S3), Ui.dp(activity, Ui.S5));

        LinearLayout box = Ui.column(activity);
        int pad = Ui.dp(activity, Ui.S4);
        box.setPadding(pad, pad, pad, pad);

        TextView title = Ui.title(activity, "Аккаунт eXepc", Ui.T_HEAD, Ui.TEXT);
        box.addView(title);

        TextView hint = Ui.paragraph(activity,
                "Синхронизирует библиотеку игр между телефоном и компьютером.",
                Ui.T_LABEL, Ui.DIM_FIXED);
        hint.setPadding(0, Ui.dp(activity, Ui.S2), 0, Ui.dp(activity, Ui.S5));
        box.addView(hint);

        EditText email = field("Почта", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        box.addView(email);

        // Поля кода, пароля и ника появляются только когда до них дошло дело: показывать
        // сразу пять пустых строк — значит отпугнуть на первом же шаге.
        EditText code = field("Код из письма", InputType.TYPE_CLASS_NUMBER);
        EditText password = field("Пароль", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText nickname = field("Ник (по желанию)", InputType.TYPE_CLASS_TEXT);

        code.setVisibility(TextView.GONE);
        password.setVisibility(TextView.GONE);
        nickname.setVisibility(TextView.GONE);

        box.addView(code);
        box.addView(password);
        box.addView(nickname);

        // Строка состояния — спокойная: сюда попадает и «Отправляем письмо…», и текст
        // ошибки, а красить обычный ход дела в тревожный цвет незачем.
        TextView status = Ui.paragraph(activity, "", Ui.T_LABEL, Ui.INFO);
        status.setPadding(0, Ui.dp(activity, Ui.S1), 0, Ui.dp(activity, Ui.S3));
        box.addView(status);

        TextView action = Ui.button(activity, "Выслать код", true);
        box.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, Ui.TOUCH + Ui.S1)));

        TextView known = Ui.button(activity, "Я уже регистрировался — ввести пароль", false);
        LinearLayout.LayoutParams knownParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, Ui.TOUCH));
        knownParams.topMargin = Ui.dp(activity, Ui.S2);
        box.addView(known, knownParams);

        // 0 — ввод почты, 1 — регистрация по коду, 2 — вход паролем,
        // 3 — смена пароля по коду для уже существующего аккаунта.
        final int[] state = {0};

        known.setOnClickListener(v -> {
            state[0] = 2;
            code.setVisibility(TextView.GONE);
            nickname.setVisibility(TextView.GONE);
            password.setVisibility(TextView.VISIBLE);
            action.setText("Войти");
            known.setVisibility(TextView.GONE);
            status.setText("");
        });

        action.setOnClickListener(v -> {
            String mail = email.getText().toString().trim();

            if (mail.isEmpty()) {
                status.setText("Введи почту.");
                return;
            }

            action.setEnabled(false);

            if (state[0] == 0) {
                status.setText("Отправляем письмо…");

                account.requestCode(mail, (error, existing) -> {
                    action.setEnabled(true);

                    if (error != null) {
                        status.setText(error);
                        return;
                    }

                    code.setVisibility(TextView.VISIBLE);
                    password.setVisibility(TextView.VISIBLE);
                    known.setVisibility(TextView.GONE);

                    if (existing) {
                        // На эту почту аккаунт уже есть. Заводить второй нельзя — одна
                        // почта, один человек, одни отзывы. Значит это не регистрация,
                        // а смена пароля, и ник спрашивать незачем: он уже выбран.
                        state[0] = 3;
                        nickname.setVisibility(TextView.GONE);
                        password.setHint("Новый пароль");
                        action.setText("Задать новый пароль");
                        status.setText("На " + mail + " уже есть аккаунт. Код для смены "
                                + "пароля отправлен, он действует 15 минут. Помнишь старый "
                                + "пароль — просто войди им.");
                        return;
                    }

                    state[0] = 1;
                    nickname.setVisibility(TextView.VISIBLE);
                    action.setText("Готово");
                    status.setText("Код отправлен на " + mail + ". Он действует 15 минут.");
                });

                return;
            }

            if (state[0] == 1 || state[0] == 3) {
                boolean reset = state[0] == 3;
                status.setText(reset ? "Меняем пароль…" : "Проверяем код…");

                account.register(mail, code.getText().toString().trim(),
                        password.getText().toString(), nickname.getText().toString(), reset,
                        error -> {
                            action.setEnabled(true);

                            if (error != null) {
                                status.setText(error);
                                return;
                            }

                            Toast.makeText(activity,
                                    reset ? "Пароль изменён, ты вошёл как " + account.name()
                                          : "Готово, ты вошёл как " + account.name(),
                                    Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            changed.done();
                        });

                return;
            }

            status.setText("Входим…");

            account.signIn(mail, password.getText().toString(), error -> {
                action.setEnabled(true);

                if (error != null) {
                    status.setText(error);
                    return;
                }

                Toast.makeText(activity, "Привет, " + account.name(), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                changed.done();
            });
        });

        scroll.addView(box);
        dialog.setContentView(scroll);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawable(Ui.sheet(activity));
        }

        dialog.show();
    }

    private EditText field(String hint, int inputType) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setHintTextColor(Ui.DIM);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.T_BODY);
        input.setInputType(InputType.TYPE_CLASS_TEXT | inputType);
        input.setSingleLine(true);
        input.setMinHeight(Ui.dp(activity, Ui.TOUCH));

        // Поле подсвечивается рамкой, когда в него пишут: без этого на тёмном фоне не
        // видно, куда сейчас идут буквы, — а тут их четыре штуки подряд.
        input.setBackground(Ui.field(activity));
        input.setPadding(Ui.dp(activity, Ui.S3), Ui.dp(activity, Ui.S3),
                Ui.dp(activity, Ui.S3), Ui.dp(activity, Ui.S3));
        input.setLayoutParams(Ui.wide(activity, Ui.S2));

        return input;
    }
}
