package net.minecraft.launchwrapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LogWrapper {
    /** Singleton instance */
    public static LogWrapper log = new LogWrapper();
    /** The actual log4j log */
    private Logger myLog;
    /** Guard for log configuration */
    private static boolean configured;

    /**
     * <ul>
     * <li> Initializes myLog with the LaunchWrapper log4j logger. </li>
     * <li> Sets the configured guard to true </li>
     * </ul>
     */
    private static void configureLogging() {
        // snip
    }

    /** Switches the output to a different Logger object */
    public static void retarget(Logger to) {
        log.myLog = to;
    }

    /**
     * <ul>
     * <li>Sets up a "logChannel" logger in log4j</li>
     * <li>Logs the given data using String.format as the formatter (for compat it's useful to detect curly brace specifiers and use message formats instead in that case)</li>
     * </ul>
     */
    public static void log(String logChannel, Level level, String format, Object... data) {
        // snip
    }

    /** Like above, but logs to myLog (and configures logging first if guard is false). */
    public static void log(Level level, String format, Object... data) {
        // snip
    }

    /** Like above, with a Throwable */
    public static void log(String logChannel, Level level, Throwable ex, String format, Object... data) {
        // snip
    }

    /** Like above, with a Throwable */
    public static void log(Level level, Throwable ex, String format, Object... data) {
        // snip
    }

    /** Trivial wrapper */
    public static void severe(String format, Object... data) {
        log(Level.ERROR, format, data);
    }

    /** Trivial wrapper */
    public static void warning(String format, Object... data) {
        log(Level.WARN, format, data);
    }

    /** Trivial wrapper */
    public static void info(String format, Object... data) {
        log(Level.INFO, format, data);
    }

    /** Trivial wrapper */
    public static void fine(String format, Object... data) {
        log(Level.DEBUG, format, data);
    }

    /** Trivial wrapper */
    public static void finer(String format, Object... data) {
        log(Level.TRACE, format, data);
    }

    /** Trivial wrapper */
    public static void finest(String format, Object... data) {
        log(Level.TRACE, format, data);
    }

    /** Ensures a given logger name is initialized (pointless?!) */
    public static void makeLog(String logChannel) {
        LogManager.getLogger(logChannel);
    }
}
