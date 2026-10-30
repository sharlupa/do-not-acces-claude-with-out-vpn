package io.github.sharlupa.claudeguard;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

final class Ui {
    private Ui() {}

    /** Отступы под системные панели (Android 15+ рисует приложения от края до края). */
    static void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }
}
