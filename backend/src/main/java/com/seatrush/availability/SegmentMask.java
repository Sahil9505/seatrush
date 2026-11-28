package com.seatrush.availability;

/**
 * Core availability bitmask logic for train seat booking.
 *
 * Worked example (SPEC section 4: 6 stops S0 to S5, 5 segments 0 to 4):
 * - Existing booking S0->S2 uses segments 0 and 1, so mask = 00011 (3).
 * - Request S2->S4 uses segments 2 and 3, so mask = 01100 (12).
 *   No overlap (3 & 12 == 0), so the same seat can be sold.
 * - Request S1->S3 uses segments 1 and 2, so mask = 00110 (6).
 *   Overlaps on segment 1 (3 & 6 != 0), so this seat is rejected.
 *
 * Supports journeys with up to 63 segments (up to 64 stops).
 */
public final class SegmentMask {

    private SegmentMask() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Builds a request bitmask for booking segments from stop a to stop b.
     *
     * @param a         From stop index (0-based)
     * @param b         To stop index (0-based, must be > a)
     * @param stopCount Total number of stops in the journey
     * @return 64-bit mask representing segments [a, b - 1]
     * @throws IllegalArgumentException if bounds or stopCount are invalid
     */
    public static long requestMask(int a, int b, int stopCount) {
        if (stopCount < 2) {
            throw new IllegalArgumentException("stopCount must be at least 2, got: " + stopCount);
        }
        if (stopCount - 1 > 63) {
            throw new IllegalArgumentException("stopCount cannot exceed 64 (max 63 segments), got: " + stopCount);
        }
        if (a < 0) {
            throw new IllegalArgumentException("from stop 'a' cannot be negative, got: " + a);
        }
        if (a >= b) {
            throw new IllegalArgumentException("from stop 'a' (" + a + ") must be strictly less than to stop 'b' (" + b + ")");
        }
        if (b > stopCount - 1) {
            throw new IllegalArgumentException("to stop 'b' (" + b + ") cannot exceed stopCount - 1 (" + (stopCount - 1) + ")");
        }

        return ((1L << (b - a)) - 1) << a;
    }

    /**
     * Checks if a seat is free for the requested segments.
     *
     * @param seatMask Current occupied segments mask of the seat
     * @param reqMask  Requested segments mask
     * @return true if none of the requested segments are occupied
     */
    public static boolean isFree(long seatMask, long reqMask) {
        return (seatMask & reqMask) == 0L;
    }

    /**
     * Books requested segments on a seat mask.
     *
     * @param seatMask Current occupied segments mask
     * @param reqMask  Requested segments mask
     * @return Updated seat mask with requested segments marked occupied
     * @throws IllegalStateException if the seat is not free for the requested segments
     */
    public static long book(long seatMask, long reqMask) {
        if (!isFree(seatMask, reqMask)) {
            throw new IllegalStateException("Seat is not free for requested mask: " + reqMask + ", current: " + seatMask);
        }
        return seatMask | reqMask;
    }

    /**
     * Cancels segments on a seat mask. Idempotent: cancelling twice is a no-op.
     *
     * @param seatMask Current occupied segments mask
     * @param reqMask  Segments mask to release
     * @return Updated seat mask with requested segments cleared
     */
    public static long cancel(long seatMask, long reqMask) {
        return seatMask & ~reqMask;
    }

    /**
     * Finds the index of the lowest-numbered free seat that can accommodate reqMask.
     * Java reference for the Redis Lua scan in B05.
     *
     * @param seatMasks Array of current seat masks
     * @param reqMask   Requested segments mask
     * @return 0-based index of the first free seat, or -1 if none fit
     */
    public static int findFirstFreeSeat(long[] seatMasks, long reqMask) {
        if (seatMasks == null || seatMasks.length == 0) {
            return -1;
        }
        for (int i = 0; i < seatMasks.length; i++) {
            if (isFree(seatMasks[i], reqMask)) {
                return i;
            }
        }
        return -1;
    }
}
