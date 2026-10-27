import Foundation

/// The adaptive GO3 sender: a target rate in KB/s that paces every GATT write, steered by the round trip
/// of the one confirmed write per frame. Times are monotonic nanoseconds passed in. One instance lives as
/// long as the SGC, so the rate and marks survive reconnects.
/// Swift port of the Android `sgcs/inmo/SendRate.java` (lens-spark@3e2158f docs/SPEC.md "Sending").
struct Go3SendRate {
    static let startKbps = 30.0, minKbps = 4.0, maxKbps = 200.0, climbKbps = 0.3
    /// Climb only while the target is within 15% of the rate actually delivered.
    static let headroom = 1.15
    /// A round trip over this many times usual is the early sign of a backlog: hold.
    static let rising = 1.5
    /// A round trip over 2x usual plus this is a backlog: cut.
    static let backlogExtraMs: UInt64 = 50
    static let backlogCut = 0.85
    static let maxDrainMs: UInt64 = 10000
    /// A write blocked this long (the glasses went silent and came back) halves the rate.
    static let blockedMs: UInt64 = 1000
    static let softLoosenMs: UInt64 = 20000, hardLoosenMs: UInt64 = 30000
    static let loosen = 1.05
    /// A confirmation covering fewer writes than this ends a session setup message, not a frame.
    static let minFrameWrites = 4
    /// Idle time beyond one usual round trip (at most this) earns no credit toward the next write.
    private static let maxCreditMs = 100.0

    enum State: String { case climb, hold, drain }

    private(set) var kbps = startKbps
    private(set) var softKbps = maxKbps
    private(set) var hardKbps = maxKbps
    // Per connection (reset by connectionStarted).
    private(set) var usualRttMs = 0.0
    private(set) var deliveredKbps = 0.0
    private var nextSendAt: UInt64?
    private var softAt: UInt64 = 0, hardAt: UInt64 = 0, cutAt: UInt64 = 0
    private var draining = false
    private(set) var lastRttMs: UInt64 = 0
    private(set) var state = State.climb

    /// A new connection: forget its round trips and delivery, keep the rate and the marks.
    mutating func connectionStarted(now: UInt64) {
        usualRttMs = 0
        deliveredKbps = 0
        nextSendAt = nil
        softAt = now
        hardAt = now
        draining = false
        lastRttMs = 0
        state = .climb
    }

    /// How long to wait (ns) before writing `bytes`, and books them. The whole frame, writes and the wait for
    /// its confirmation, runs at the target: time spent waiting counts, but idle time beyond one usual round
    /// trip (at most 100 ms) earns no credit.
    mutating func waitNanos(bytes: Int, now: UInt64) -> UInt64 {
        let credit = UInt64(min(usualRttMs, Self.maxCreditMs) * 1e6)
        let earliest = now > credit ? now - credit : 0
        let start = max(earliest, nextSendAt ?? 0)
        nextSendAt = start + UInt64(Double(bytes) * 1e9 / (kbps * 1024))
        return start > now ? start - now : 0
    }

    /// A write was blocked over `blockedMs`: halve, and the rate before becomes the hard mark.
    mutating func blocked(now: UInt64) {
        hardKbps = kbps
        hardAt = now
        cut(now: now, to: kbps * 0.5)
    }

    /// The link dropped: the reconnect resumes at half, and the rate it dropped at becomes the hard mark.
    mutating func dropped() {
        hardKbps = kbps
        kbps = max(Self.minKbps, kbps * 0.5)
    }

    /// A confirmed write's round trip. `frameWrites` and `frameBytes` are everything that confirmation
    /// covers, written over `frameNanos` (first write to confirmation).
    mutating func confirmed(rttMs: UInt64, frameWrites: Int, frameBytes: Int, frameNanos: UInt64, now: UInt64) {
        lastRttMs = rttMs
        guard frameWrites >= Self.minFrameWrites else { return }
        let rtt = Double(rttMs)
        if frameBytes > 0, frameNanos > 0 {
            let delivered = Double(frameBytes) / 1024.0 / (Double(frameNanos) / 1e9)
            deliveredKbps = deliveredKbps == 0 ? delivered : deliveredKbps * 0.8 + delivered * 0.2
        }
        if usualRttMs == 0 { usualRttMs = rtt }
        if draining {
            // After a cut, wait for the backlog to drain (round trips back near usual) before judging again.
            if rtt > usualRttMs * Self.rising, now - cutAt <= Self.maxDrainMs * 1_000_000 { return }
            draining = false
        }
        if rtt > 2 * usualRttMs + Double(Self.backlogExtraMs) {
            // A backlog: cut once to just under what the link was carrying, mark that level softly, then drain.
            let carried = min(kbps, deliveredKbps)
            softKbps = carried
            softAt = now
            cut(now: now, to: carried * Self.backlogCut)
            return
        }
        if rtt > usualRttMs * Self.rising {
            // Round trips creeping up: hold. "Usual" follows only slowly, so a lasting change is eventually
            // accepted as the new normal.
            usualRttMs = usualRttMs * 0.98 + rtt * 0.02
            state = .hold
            return
        }
        usualRttMs = usualRttMs * 0.9 + rtt * 0.1
        state = .climb
        if kbps < deliveredKbps * Self.headroom {
            var step = Self.climbKbps
            if kbps > hardKbps * 0.85 { step = min(step, Self.climbKbps / 5) }
            if kbps > softKbps * 0.9 { step = min(step, Self.climbKbps / 3) }
            kbps = min(Self.maxKbps, kbps + step)
        } else {
            // A target far above what's delivered holds nothing back.
            kbps = max(Self.minKbps, deliveredKbps * Self.headroom)
        }
        // Marks loosen over time, so one left by a passing problem stops slowing the climb.
        if now - hardAt > Self.hardLoosenMs * 1_000_000 {
            hardKbps = min(Self.maxKbps, hardKbps * Self.loosen)
            hardAt = now
        }
        if now - softAt > Self.softLoosenMs * 1_000_000 {
            softKbps = min(Self.maxKbps, softKbps * Self.loosen)
            softAt = now
        }
    }

    private mutating func cut(now: UInt64, to target: Double) {
        kbps = max(Self.minKbps, target)
        cutAt = now
        draining = true
        state = .drain
    }

    /// Numbers and state names only, for logging.
    var description: String {
        "targetKbps=\(Int(kbps.rounded())) deliveredKbps=\(Int(deliveredKbps.rounded())) rttMs=\(lastRttMs)"
            + " usualRttMs=\(Int(usualRttMs.rounded())) state=\(state.rawValue)"
    }
}
