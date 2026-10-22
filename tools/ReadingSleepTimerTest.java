package io.github.shandianchengzi.txtreader;

/** Plain-Java regression checks with a synthetic monotonic clock. */
public final class ReadingSleepTimerTest {
    private static final long MIN = 60_000L;
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        ReadingSleepTimer timer = new ReadingSleepTimer();
        timer.reset(0, 0, true);
        check(timer.remaining(10_000_000L) == -1, "unlimited");
        check(!timer.expired(10_000_000L), "unlimited never expires");

        timer.reset(15, 1000, true);
        check(timer.remaining(1000 + 5 * MIN) == 10 * MIN, "5 minutes used");
        timer.pause(1000 + 5 * MIN);
        check(timer.remaining(1000 + 500 * MIN) == 10 * MIN, "pause freezes countdown");
        timer.resume(1000 + 500 * MIN);
        check(!timer.expired(1000 + 509 * MIN), "one minute remaining");
        check(timer.expired(1000 + 510 * MIN), "expires after total 15 playing minutes");

        timer.reset(30, 0, true);
        timer.reset(45, 12 * MIN, true);
        check(timer.remaining(12 * MIN) == 45 * MIN, "change selection resets timer");
        timer.pause(20 * MIN);
        timer.reset(120, 20 * MIN, false);
        check(timer.remaining(1000 * MIN) == 120 * MIN, "change while paused");
        timer.resume(1000 * MIN);
        check(timer.expired(1120 * MIN), "2 hours of resumed playback");
        timer.reset(15, 1120 * MIN, true);
        check(timer.remaining(1120 * MIN) == 15 * MIN, "new playback resets timer");
        timer.reset(0, 1121 * MIN, true);
        check(!timer.expired(9999 * MIN), "switching to unlimited cancels deadline");
        check(ReadingSleepTimer.normalized(999) == 0, "invalid preference safe");
        System.out.println("ReadingSleepTimer regression tests passed");
    }
}
