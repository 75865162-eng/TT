package com.wimoor.ai.lili;

/** 移植自 src/lib/lili/tx.ts：中英文本选择，Locale 的 "zh" / "en" 线格式。 */
public final class Tx {

    private Tx() {}

    public enum Locale {
        ZH("zh"), EN("en");
        private final String wire;
        Locale(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static Locale fromWire(String value) {
            return "en".equals(value) ? EN : ZH;
        }
    }

    /** tx(locale, zh, en) === locale === "en" ? en : zh */
    public static String tx(Locale locale, String zh, String en) {
        return locale == Locale.EN ? en : zh;
    }

    public static String tx(String locale, String zh, String en) {
        return tx(Locale.fromWire(locale), zh, en);
    }
}
