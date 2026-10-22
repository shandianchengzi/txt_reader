package io.github.shandianchengzi.txtreader;

/**
 * Monotonic countdown of actual playback time. Pauses do not consume the
 * configured duration; changing the preference starts a fresh timer.
 *
 * Package-private and Android-independent so the transitions can be tested
 * with a fake elapsedRealtime clock.
 */
final class ReadingSleepTimer {
    static final String PREF_KEY = "speechTimerMinutes";
    static final int[] OPTIONS = {15, 30, 45, 120, 0};
    private static final long MINUTE_MS = 60_000L;
    private int limitMinutes;
    private long remainingMs;
    private long sinceMs;
    private boolean running;

    static int normalized(int minutes) {
        for (int valid : OPTIONS) if (valid == minutes) return minutes;
        return 0; // Unknown or old values are safely treated as unlimited.
    }

    void reset(int minutes, long now, boolean isPlaying) {
        limitMinutes = normalized(minutes);
        remainingMs = (long) limitMinutes * MINUTE_MS;
        sinceMs = now;
        running = limitMinutes > 0 && isPlaying;
    }

    void pause(long now) {
        if (!running) return;
        remainingMs = remaining(now);
        sinceMs = now;
        running = false;
    }

    void resume(long now) {
        if (limitMinutes == 0 || running) return;
        sinceMs = now;
        running = true;
    }

    long remaining(long now) {
        if (limitMinutes == 0) return -1L;
        if (!running) return remainingMs;
        return Math.max(0L, remainingMs - Math.max(0L, now - sinceMs));
    }

    boolean expired(long now) {
        return limitMinutes > 0 && remaining(now) == 0L;
    }
}
