package ca.stellardrift.mcannouncer.util;

public final class Signals {

    private Signals() {
    }

    @SuppressWarnings("all") // Signal is internal api, suppress all warnings and don't import to avoid an unsuppressable warning
    public static void register(final String name, final Runnable signalHandler) {
        sun.misc.Signal.handle(new sun.misc.Signal(name), sig -> signalHandler.run());
    }

}
