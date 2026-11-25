package com.wimoor.ai.lili;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 移植自 src/lib/lili/ui.ts：zustand + persist 的 UI 状态。
 * flash 保留 3200ms 自动清空语义；persist 的 partialize 仅持久化 sandboxExtras。
 * navigator.clipboard 用 Clipboard 端口抽象，保留失败回退策略。
 */
public final class UIState {

    public interface Clipboard {
        void write(String text) throws Exception;
    }

    private boolean navOpen = false;
    private String toast = "";
    private boolean sandboxExtras = true;
    private long toastExpiresAt = 0L;

    public boolean isNavOpen() { return navOpen; }
    public String getToast() { return toast; }
    public boolean isSandboxExtras() { return sandboxExtras; }

    public void setNav(boolean open) { this.navOpen = open; }

    /** window.setTimeout(() => set({ toast: "" }), 3200) 的时间无关实现。 */
    public void flash(String toast, long nowMillis) {
        this.toast = toast == null ? "" : toast;
        this.toastExpiresAt = nowMillis + 3200L;
    }

    public void flash(String toast) { flash(toast, System.currentTimeMillis()); }

    public void expireToast(long nowMillis) {
        if (toastExpiresAt > 0 && nowMillis >= toastExpiresAt) {
            this.toast = "";
            this.toastExpiresAt = 0;
        }
    }

    public void setSandboxExtras(boolean on) { this.sandboxExtras = on; }

    /** partialize: (state) => ({ sandboxExtras }) */
    public Map<String, Object> persistedState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sandboxExtras", sandboxExtras);
        return m;
    }

    /** 从持久化存储恢复（对应 persist 的 rehydrate）。 */
    public void restore(Map<String, Object> persisted) {
        if (persisted != null && Boolean.FALSE.equals(persisted.get("sandboxExtras"))) this.sandboxExtras = false;
    }

    /** navigator.clipboard.writeText 失败时返回 "textarea-fallback" 策略供调用方执行 DOM 兜底。 */
    public String copyText(String text, Clipboard clipboard) {
        try {
            if (clipboard != null) clipboard.write(text);
            return "clipboard";
        } catch (Exception e) {
            return "textarea-fallback";
        }
    }
}
