package ch.confluencehelper.convert;

/**
 * POI logs via log4j-api and PDFBox via commons-logging (which picks log4j-api when present).
 * Without a log4j provider, log4j prints a "could not find a logging implementation" StatusLogger
 * warning on stderr. This routes log4j to the API's built-in SimpleLogger, switched off, unless the
 * user configured logging. Must run before the first class that touches log4j is initialised.
 */
public final class LibraryLogging {

    private LibraryLogging() {
    }

    public static void silence() {
        if (System.getProperty("log4j2.loggerContextFactory") == null) {
            System.setProperty("log4j2.loggerContextFactory",
                    "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
            if (System.getProperty("org.apache.logging.log4j.simplelog.level") == null) {
                System.setProperty("org.apache.logging.log4j.simplelog.level", "OFF");
            }
        }
    }
}
