package com.seatrush.availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentMaskTest {

    @Test
    @DisplayName("1. Worked example: S0->S2 = 3, S2->S4 = 12, S1->S3 = 6; coexist and reject")
    void testWorkedExample() {
        int stopCount = 6; // S0 to S5 -> 5 segments (0, 1, 2, 3, 4)

        long s0ToS2 = SegmentMask.requestMask(0, 2, stopCount);
        long s2ToS4 = SegmentMask.requestMask(2, 4, stopCount);
        long s1ToS3 = SegmentMask.requestMask(1, 3, stopCount);

        assertEquals(3L, s0ToS2, "S0->S2 occupies segments 0 and 1 (bits 0,1 -> 00011_2 = 3)");
        assertEquals(12L, s2ToS4, "S2->S4 occupies segments 2 and 3 (bits 2,3 -> 01100_2 = 12)");
        assertEquals(6L, s1ToS3, "S1->S3 occupies segments 1 and 2 (bits 1,2 -> 00110_2 = 6)");

        long seatMask = 0L;

        // First booking S0->S2
        assertTrue(SegmentMask.isFree(seatMask, s0ToS2));
        seatMask = SegmentMask.book(seatMask, s0ToS2);
        assertEquals(3L, seatMask);

        // Second booking S2->S4 can coexist on the same seat
        assertTrue(SegmentMask.isFree(seatMask, s2ToS4));
        seatMask = SegmentMask.book(seatMask, s2ToS4);
        assertEquals(15L, seatMask, "Segments 0, 1, 2, 3 occupied (01111_2 = 15)");

        // Third booking S1->S3 overlaps on segment 1, so it is rejected
        assertFalse(SegmentMask.isFree(seatMask, s1ToS3));
        final long bookedSeatMask = seatMask;
        assertThrows(IllegalStateException.class, () -> SegmentMask.book(bookedSeatMask, s1ToS3));
    }

    @Test
    @DisplayName("2. Edge cases: single segment, whole journey, adjacent bookings, invalid ranges")
    void testEdgeCases() {
        // Single segment
        assertEquals(1L, SegmentMask.requestMask(0, 1, 6));
        assertEquals(2L, SegmentMask.requestMask(1, 2, 6));
        assertEquals(16L, SegmentMask.requestMask(4, 5, 6));

        // Whole journey (5 segments -> 31)
        assertEquals(31L, SegmentMask.requestMask(0, 5, 6));

        // Adjacent bookings do not overlap
        long seg0To2 = SegmentMask.requestMask(0, 2, 6);
        long seg2To4 = SegmentMask.requestMask(2, 4, 6);
        long seg4To5 = SegmentMask.requestMask(4, 5, 6);
        assertEquals(0L, seg0To2 & seg2To4);
        assertEquals(0L, seg2To4 & seg4To5);

        // Max segments: 64 stops (63 segments)
        long full63Segments = SegmentMask.requestMask(0, 63, 64);
        assertEquals(0x7FFFFFFFFFFFFFFFL, full63Segments);
        assertTrue(SegmentMask.isFree(0L, full63Segments));

        // Invalid: a == b
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(2, 2, 6));

        // Invalid: a > b
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(3, 2, 6));

        // Invalid: negative a
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(-1, 2, 6));

        // Invalid: b beyond last stop (stopCount - 1)
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(0, 6, 6));
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(0, 10, 6));

        // Invalid: stopCount below 2
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(0, 1, -2));

        // Invalid: more than 63 segments (stopCount > 64)
        assertThrows(IllegalArgumentException.class, () -> SegmentMask.requestMask(0, 1, 65));
    }

    @Test
    @DisplayName("3. Book then cancel restores original mask; cancel twice harmless; booking occupied throws")
    void testBookAndCancelLifecycle() {
        long originalMask = 0b10100L;
        long reqMask = SegmentMask.requestMask(0, 2, 6); // 0b00011L

        long bookedMask = SegmentMask.book(originalMask, reqMask);
        assertEquals(0b10111L, bookedMask);

        // Cancel restores the original mask
        long restoredMask = SegmentMask.cancel(bookedMask, reqMask);
        assertEquals(originalMask, restoredMask);

        // Cancel twice is idempotent and harmless
        long cancelledAgain = SegmentMask.cancel(restoredMask, reqMask);
        assertEquals(originalMask, cancelledAgain);

        // Booking an occupied range throws IllegalStateException
        assertThrows(IllegalStateException.class, () -> SegmentMask.book(bookedMask, reqMask));

        // Booking a partially overlapping range also throws
        long partialReq = SegmentMask.requestMask(1, 3, 6); // 0b00110L
        assertThrows(IllegalStateException.class, () -> SegmentMask.book(bookedMask, partialReq));
    }

    @Test
    @DisplayName("4. findFirstFreeSeat: lowest free index, -1 when none fits, empty array")
    void testFindFirstFreeSeat() {
        long req = SegmentMask.requestMask(1, 3, 6); // bits 1 and 2 (0b00110L)

        long[] seats = new long[] {
                0b00010L, // Seat 0: segment 1 occupied -> not free
                0b00100L, // Seat 1: segment 2 occupied -> not free
                0b11000L, // Seat 2: segments 3 and 4 occupied -> free for segments 1..2!
                0b00000L  // Seat 3: completely free -> also fits, but index 2 is lower
        };

        assertEquals(2, SegmentMask.findFirstFreeSeat(seats, req));

        // When all seats conflict with req
        long[] allOccupied = new long[] {
                0b00010L,
                0b00100L,
                0b00110L
        };
        assertEquals(-1, SegmentMask.findFirstFreeSeat(allOccupied, req));

        // Empty array
        assertEquals(-1, SegmentMask.findFirstFreeSeat(new long[0], req));

        // Null array
        assertEquals(-1, SegmentMask.findFirstFreeSeat(null, req));
    }

    @Test
    @DisplayName("5. Model-based randomized test (fixed seed, 10,000 iterations)")
    void testModelBasedRandomized() {
        Random random = new Random(42L);
        int stopCount = 10; // 9 segments
        int numSeats = 5;

        long[] seatMasks = new long[numSeats];
        boolean[][] model = new boolean[numSeats][stopCount - 1];

        for (int i = 0; i < 10_000; i++) {
            int seat = random.nextInt(numSeats);
            int a = random.nextInt(stopCount - 1);
            int b = a + 1 + random.nextInt(stopCount - 1 - a);

            long reqMask = SegmentMask.requestMask(a, b, stopCount);

            // Independent model check
            boolean modelFree = true;
            for (int seg = a; seg < b; seg++) {
                if (model[seat][seg]) {
                    modelFree = false;
                    break;
                }
            }

            boolean maskFree = SegmentMask.isFree(seatMasks[seat], reqMask);
            assertEquals(modelFree, maskFree, "Iteration " + i + ": isFree mismatch for seat " + seat + " [" + a + ".." + b + ")");

            if (random.nextBoolean()) {
                // Book operation
                if (modelFree) {
                    seatMasks[seat] = SegmentMask.book(seatMasks[seat], reqMask);
                    for (int seg = a; seg < b; seg++) {
                        model[seat][seg] = true;
                    }
                } else {
                    assertThrows(IllegalStateException.class, () -> SegmentMask.book(seatMasks[seat], reqMask));
                }
            } else {
                // Cancel operation
                seatMasks[seat] = SegmentMask.cancel(seatMasks[seat], reqMask);
                for (int seg = a; seg < b; seg++) {
                    model[seat][seg] = false;
                }
            }

            // Verify full model consistency with bitmask representation
            for (int seg = 0; seg < stopCount - 1; seg++) {
                boolean bitSet = (seatMasks[seat] & (1L << seg)) != 0L;
                assertEquals(model[seat][seg], bitSet, "Iteration " + i + ": state mismatch at segment " + seg);
            }
        }
    }

    @Test
    @DisplayName("6. No-overlap simulation (fixed seed, 1,000 requests, 10 seats, 6 stops)")
    void testNoOverlapSimulation() {
        Random random = new Random(1337L);
        int numSeats = 10;
        int stopCount = 6; // 5 segments
        int numSegments = stopCount - 1;

        long[] seatMasks = new long[numSeats];
        int[][] coverage = new int[numSeats][numSegments];

        int allocatedCount = 0;

        for (int i = 0; i < 1_000; i++) {
            int a = random.nextInt(numSegments);
            int b = a + 1 + random.nextInt(numSegments - a);

            long reqMask = SegmentMask.requestMask(a, b, stopCount);
            int seat = SegmentMask.findFirstFreeSeat(seatMasks, reqMask);

            if (seat != -1) {
                seatMasks[seat] = SegmentMask.book(seatMasks[seat], reqMask);
                allocatedCount++;

                for (int seg = a; seg < b; seg++) {
                    coverage[seat][seg]++;
                    assertTrue(coverage[seat][seg] <= 1,
                            "Double allocation detected on seat " + seat + ", segment " + seg + " at request " + i);
                }
            }
        }

        assertTrue(allocatedCount > 0, "Simulation should have allocated seats");

        // Verify that no seat segment was ever allocated more than once
        for (int s = 0; s < numSeats; s++) {
            for (int seg = 0; seg < numSegments; seg++) {
                assertTrue(coverage[s][seg] <= 1, "Seat " + s + " segment " + seg + " was double allocated");
                boolean bitOccupied = (seatMasks[s] & (1L << seg)) != 0L;
                assertEquals(coverage[s][seg] == 1, bitOccupied, "Mask bit does not match coverage counter");
            }
        }
    }
}
