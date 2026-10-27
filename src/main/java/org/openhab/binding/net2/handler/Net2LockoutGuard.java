/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.net2.handler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Tells when an ACU has locked everyone out, and when to re-instate it.
 * <p>
 * Net2 can suddenly refuse every token and PIN at an ACU, or run its time schedules wrongly, until
 * the ACU is re-instated in the Net2 software (seen for years across Net2 versions; on 2026-10-27
 * every attempt at one door came back as eventType 112 for 20 minutes). The signature is several
 * refusals of <em>known</em> users in a short time with no entry between them. One guard per door.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class Net2LockoutGuard {

    /** Event types that refuse a known user: 24, 25, 27 (access denied) and 112 (the lockout). */
    static final Set<Integer> REFUSAL_EVENT_TYPES = Set.of(24, 25, 27, 112);

    /** What one event means for the door. */
    public enum Result {
        /** nothing changes */
        NONE,
        /** the door has just been judged locked out */
        LOCKOUT,
        /** still locked out, and it is time to re-instate (again) */
        REINSTATE,
        /** an entry after a lockout: the door lets people in again */
        CLEARED
    }

    private final Deque<Instant> refusals = new ArrayDeque<>();
    private boolean lockedOut = false;
    private @Nullable Instant lastReinstate;

    public static boolean isRefusal(@Nullable Integer eventType) {
        return eventType != null && REFUSAL_EVENT_TYPES.contains(eventType);
    }

    public boolean isLockedOut() {
        return lockedOut;
    }

    /**
     * An entry: the door works, whatever came before.
     */
    public synchronized Result onEntry() {
        refusals.clear();
        if (lockedOut) {
            lockedOut = false;
            return Result.CLEARED;
        }
        return Result.NONE;
    }

    /**
     * A refusal of a known user at {@code now}.
     *
     * @param threshold refusals within the window that make a lockout
     * @param window how far back refusals count
     * @param autoReinstate whether a lockout may be re-instated without a person
     * @param interval the least time between two re-instates of the same ACU
     * @return LOCKOUT the first time (REINSTATE instead when it may re-instate), REINSTATE again
     *         when the lockout outlasts the interval, otherwise NONE
     */
    public synchronized Result onRefusal(Instant now, int threshold, Duration window, boolean autoReinstate,
            Duration interval) {
        refusals.addLast(now);
        while (!refusals.isEmpty() && refusals.peekFirst().isBefore(now.minus(window))) {
            refusals.removeFirst();
        }
        boolean wasLockedOut = lockedOut;
        if (!lockedOut && refusals.size() >= Math.max(1, threshold)) {
            lockedOut = true;
        }
        if (!lockedOut) {
            return Result.NONE;
        }
        Instant last = lastReinstate;
        if (autoReinstate && (last == null || !now.isBefore(last.plus(interval)))) {
            lastReinstate = now;
            return Result.REINSTATE;
        }
        return wasLockedOut ? Result.NONE : Result.LOCKOUT;
    }
}
