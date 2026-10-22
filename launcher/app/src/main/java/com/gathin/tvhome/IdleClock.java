package com.gathin.tvhome;

/** What FireHomeWatcher does when the idle time is up. Plain Java, tested on the JVM. */
final class IdleClock {

    enum Action { NOTHING, OPEN_CLOCK, CHECK_AGAIN }

    /** How long to wait before looking again while something is playing. */
    static final long RECHECK_MS = 60_000;

    private IdleClock() {
    }

    /** Screens that count as playing even when silent: Smart Home's camera view. */
    static boolean isLiveView(String activityClass) {
        return "com.homecontrol.tv.CameraActivity".equals(activityClass);
    }

    /**
     * @param minutes   the delay from Settings; 0 is off
     * @param screenOn  the TV is awake
     * @param playing   sound is playing (a show, music), or a live view is open; paused apps are fair game
     * Once the clock is open no key comes until someone uses the remote, so it isn't opened twice.
     */
    static Action decide(int minutes, boolean screenOn, boolean playing) {
        if (minutes <= 0 || !screenOn) return Action.NOTHING;
        return playing ? Action.CHECK_AGAIN : Action.OPEN_CLOCK;
    }
}
