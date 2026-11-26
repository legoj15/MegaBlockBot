package com.legoj15.megablockbot.game.resolver;

import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The current acquisition index. A rebuild on the server thread swaps in a new immutable index, so any thread that
 * holds the previous one keeps a consistent view.
 */
public final class AcquisitionIndexes {
    private static final AtomicReference<AcquisitionIndex> CURRENT = new AtomicReference<>(AcquisitionIndex.EMPTY);

    private AcquisitionIndexes() {}

    /** The latest index; {@link AcquisitionIndex#EMPTY} before the first build and after the server stops. */
    public static AcquisitionIndex current() {
        return CURRENT.get();
    }

    static void publish(AcquisitionIndex index) {
        CURRENT.set(index);
    }

    static void clear() {
        CURRENT.set(AcquisitionIndex.EMPTY);
    }
}
