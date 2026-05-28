package com.termux.terminal;

final class TerminalEngineFactory {

    private TerminalEngineFactory() {}

    static TerminalEngine create(TerminalOutput session, int columns, int rows, int cellWidthPixels,
                                 int cellHeightPixels, Integer transcriptRows, TerminalSessionClient client) {
        if (!GhosttyTerminalEngine.isAvailable()) {
            String message = "libghostty-vt is required but unavailable";
            Logger.logError(client, "TerminalEngineFactory", message);
            throw new IllegalStateException(message);
        }

        Logger.logInfo(client, "TerminalEngineFactory", "Using libghostty-vt terminal engine");
        return new GhosttyTerminalEngine(session, columns, rows, cellWidthPixels, cellHeightPixels, transcriptRows, client);
    }
}
