package io.papermc.paper.util;

import java.lang.StackWalker.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paper adds {@code getClassLogger()} to {@code com.mojang.logging.LogUtils} with a
 * source patch. Here {@code com.mojang.logging} is an external library rather than
 * patchable decompiled source, so Paper's helper lives in this class instead. The
 * behaviour is identical: a logger named after the calling class's simple name.
 */
public final class PaperLogUtils {
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE);

    private PaperLogUtils() {
    }

    public static Logger getClassLogger() {
        return LoggerFactory.getLogger(STACK_WALKER.getCallerClass().getSimpleName());
    }
}
