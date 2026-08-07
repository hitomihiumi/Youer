package gg.pufferfish.pufferfish;

// Youer: temporary local stub for M3 (vendored-package rebase milestone) - com/mohistmc/youer is
// deliberately not restored until M6, so this package can't depend on the real
// com.mohistmc.youer.util.I18n (which pulls in Youer.java and its whole dependency graph).
// Replace call sites with the real com.mohistmc.youer.util.I18n in M6.
public class I18n {

    public static String as(String key) {
        return key;
    }

    public static String as(String key, Object... objects) {
        return key + " " + java.util.Arrays.toString(objects);
    }
}
