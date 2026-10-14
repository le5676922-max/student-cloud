package im.tlgxy.cloud.core.util;

/**
 * Server-only identity scope for an already-authorized persisted job. Never populated from a
 * request header, task payload or model argument; the caller must capture the verified owner.
 * It does not impersonate a Sa-Token session and deliberately does not propagate to child threads.
 */
public final class BackgroundActorContext {
    private static final ThreadLocal<Integer> ACTOR = new ThreadLocal<>();

    private BackgroundActorContext() { }

    public static Integer currentActor() { return ACTOR.get(); }

    public static void runAs(int verifiedOwner, Runnable work) {
        if (verifiedOwner <= 0 || verifiedOwner == 2) throw new IllegalArgumentException("A login user is required");
        if (ACTOR.get() != null) throw new IllegalStateException("Nested background identity scopes are forbidden");
        ACTOR.set(verifiedOwner);
        try { work.run(); }
        finally { ACTOR.remove(); }
    }
}
