@testable import MentraBluetoothSDK
import XCTest

/// Mirrors the Android `sgcs/inmo` tests: AA55 framing, messages, binding replies and the adaptive sender.
final class InmoGo3ProtocolTests: XCTestCase {
    // MARK: - Framing and messages

    func testCrcMatchesCcittFalseCheckValue() {
        XCTAssertEqual(Go3Protocol.crc16(Array("123456789".utf8)), 0x29B1)
    }

    func testVarintCrossesOneByteBoundary() {
        XCTAssertEqual(Go3Protocol.varint(127), [0x7F])
        XCTAssertEqual(Go3Protocol.varint(128), [0x80, 0x01])
    }

    func testFrameHasMeasuredHeaderLengthAndCrc() {
        var protocolState = Go3Protocol()
        let frames = protocolState.frames([1, 2, 3])
        XCTAssertEqual(frames.count, 1)
        let frame = [UInt8](frames[0])
        XCTAssertEqual(frame.count, 17)
        XCTAssertEqual(Array(frame[0 ..< 2]), [0xAA, 0x55])
        XCTAssertEqual(frame[5], 1)
        XCTAssertEqual(frame[7], 0)
        XCTAssertEqual(Array(frame[8 ..< 10]), [0x03, 0x01])
        XCTAssertEqual(frame[11], 3)
        let crc = Int(frame[15]) << 8 | Int(frame[16])
        XCTAssertEqual(crc, Int(Go3Protocol.crc16(frame[0 ..< 15])))
    }

    func testPayloadIsFragmentedAt230Bytes() {
        var protocolState = Go3Protocol()
        let frames = protocolState.frames([UInt8](repeating: 0, count: 231))
        XCTAssertEqual(frames.map(\.count), [244, 15])
    }

    func testSequenceAdvancesPerMessage() {
        var protocolState = Go3Protocol()
        let first = [UInt8](protocolState.frames([1])[0])
        let second = [UInt8](protocolState.frames([1])[0])
        XCTAssertEqual(Array(first[2 ..< 4]), [0x70, 0x00])
        XCTAssertEqual(Array(second[2 ..< 4]), [0x70, 0x01])
    }

    func testLoginWrapsTheIdentifier() {
        XCTAssertEqual(
            Go3Protocol.login([0x41, 0x42, 0x43]),
            [0x10, 0x18, 0xDA, 0x01, 0x07, 0x08, 0x01, 0x12, 0x03, 0x41, 0x42, 0x43]
        )
        XCTAssertNil(Go3Protocol.login([]))
        XCTAssertNil(Go3Protocol.login([0]))
        XCTAssertNil(Go3Protocol.login([UInt8](repeating: 1, count: 257)))
    }

    func testDestinationArrivedWrapsPngInNavigationMessage() {
        let png = [UInt8](repeating: 0x5A, count: 500)
        let arrived = [0x08] + Go3Protocol.varint(1864) + [0x10] + Go3Protocol.varint(418)
            + [0x1A, 0x03, 0x55, 0x53, 0x41, 0x22] + Go3Protocol.varint(png.count) + png
        let navigation = [0x08, 0x0A, 0x62] + Go3Protocol.varint(arrived.count) + arrived
        let expected = [0x10, 0x21, 0xA2, 0x02] + Go3Protocol.varint(navigation.count) + navigation
        XCTAssertEqual(Go3Protocol.destinationArrived(png), expected)
    }

    func testImageFragmentsGoUnconfirmedAndShortMessagesAreConfirmed() {
        var protocolState = Go3Protocol()
        let image = protocolState.frames(Go3Protocol.destinationArrived([UInt8](repeating: 0, count: 5000)))
        let open = protocolState.frames(Go3Protocol.openNavigation)
        let advertise = protocolState.frames(Go3Protocol.advertiseNavigation)
        XCTAssertFalse(image.contains(where: Go3Protocol.confirms))
        XCTAssertTrue(Go3Protocol.confirms(open[0]))
        XCTAssertTrue(Go3Protocol.confirms(advertise[0]))
    }

    // MARK: - Binding replies

    private func field(_ tag: Int, _ value: [UInt8]) -> [UInt8] {
        Go3Protocol.varint(tag) + Go3Protocol.varint(value.count) + value
    }

    private func response(_ status: [UInt8]) -> [UInt8] {
        [16, 24] + field(218, [8, 3] + field(34, status))
    }

    /// One reply frame from glasses endpoint 1 to the phone (destination 3).
    private func reply(_ body: [UInt8], seq: Int = 9, count: Int = 1, index: Int = 0, source: UInt8 = 1) -> Data {
        var frame: [UInt8] = [0xAA, 0x55, UInt8(seq >> 8), UInt8(seq & 255), UInt8(count >> 8), UInt8(count & 255),
                              UInt8(index >> 8), UInt8(index & 255), source, 3, UInt8(body.count >> 8), UInt8(body.count & 255)]
        frame += body
        let crc = Go3Protocol.crc16(frame)
        frame += [UInt8(crc >> 8), UInt8(crc & 255)]
        return Data(frame)
    }

    private func decode(_ data: Data...) -> Go3BindingReply? {
        var decoder = Go3BindingResponseDecoder()
        data.forEach { decoder.accept($0) }
        return decoder.reply
    }

    func testFoundIdentifierIsReturned() {
        XCTAssertEqual(decode(reply(response([8, 1] + field(18, [71, 72, 73])))), .accessFound([71, 72, 73]))
    }

    func testEmptyStatusMeansUnbound() {
        XCTAssertEqual(decode(reply(response([]))), .unbound)
    }

    func testVendorErrorIsReported() {
        XCTAssertEqual(decode(reply(response([0x18, 0x05]))), .vendorError)
    }

    func testFragmentedReplyIsReassembledAcrossReads() {
        let body = response([8, 1] + field(18, [1, 2]))
        let first = reply(Array(body[0 ..< 5]), seq: 19, count: 2, index: 0)
        let second = reply(Array(body[5...]), seq: 19, count: 2, index: 1)
        let wire = first + second
        XCTAssertEqual(decode(wire.prefix(7), wire.dropFirst(7)), .accessFound([1, 2]))
    }

    func testUnrelatedMessagesAreSkipped() {
        XCTAssertEqual(decode(reply([16, 2], seq: 3), reply(response([]), seq: 4)), .unbound)
    }

    func testBadCrcFailsClosed() {
        var bad = [UInt8](reply(response([])))
        bad[bad.count - 1] ^= 1
        XCTAssertEqual(decode(Data(bad)), .malformed)
    }

    func testWrongDestinationFailsClosed() {
        var frame = [UInt8](reply(response([])))
        frame[9] = 2
        let crc = Go3Protocol.crc16(frame[0 ..< frame.count - 2])
        frame[frame.count - 2] = UInt8(crc >> 8)
        frame[frame.count - 1] = UInt8(crc & 255)
        XCTAssertEqual(decode(Data(frame)), .malformed)
    }

    // MARK: - Adaptive sender

    private let ms: UInt64 = 1_000_000

    /// One 5 KB frame of 22 writes, carried at `deliveredKbps`, its confirmation back after `rttMs`.
    private func frame(_ rate: inout Go3SendRate, now: inout UInt64, delivered: Double, rttMs: UInt64) {
        now += 150 * ms
        let bytes = 5 * 1024
        rate.confirmed(rttMs: rttMs, frameWrites: 22, frameBytes: bytes,
                       frameNanos: UInt64(Double(bytes) / 1024.0 / delivered * 1e9), now: now)
    }

    func testRateClimbsWhileDeliveryKeepsUp() {
        var rate = Go3SendRate()
        var now = 5000 * ms
        rate.connectionStarted(now: now)
        XCTAssertEqual(rate.kbps, 30, accuracy: 1e-9)
        for _ in 0 ..< 10 { frame(&rate, now: &now, delivered: 30, rttMs: 20) }
        XCTAssertEqual(rate.kbps, 33, accuracy: 1e-6)
        XCTAssertEqual(rate.state, .climb)
    }

    func testRateHoldsOnRisingRoundTrips() {
        var rate = Go3SendRate()
        var now = 5000 * ms
        rate.connectionStarted(now: now)
        frame(&rate, now: &now, delivered: 30, rttMs: 20)
        let before = rate.kbps
        frame(&rate, now: &now, delivered: 30, rttMs: 31)
        XCTAssertEqual(rate.kbps, before, accuracy: 1e-9)
        XCTAssertEqual(rate.state, .hold)
    }

    func testBacklogCutsOnceThenDrains() {
        var rate = Go3SendRate()
        var now = 5000 * ms
        rate.connectionStarted(now: now)
        frame(&rate, now: &now, delivered: 30, rttMs: 20)
        frame(&rate, now: &now, delivered: 30, rttMs: 100)
        XCTAssertEqual(rate.kbps, 30 * 0.85, accuracy: 1e-6)
        XCTAssertEqual(rate.softKbps, 30, accuracy: 1e-6)
        XCTAssertEqual(rate.state, .drain)
        frame(&rate, now: &now, delivered: 30, rttMs: 100)
        XCTAssertEqual(rate.kbps, 30 * 0.85, accuracy: 1e-6)
        frame(&rate, now: &now, delivered: 30, rttMs: 20)
        XCTAssertEqual(rate.kbps, 30 * 0.85 + 0.3, accuracy: 1e-6)
    }

    func testBlockedAndDroppedHalveAndSetTheHardMark() {
        var rate = Go3SendRate()
        var now = 5000 * ms
        rate.connectionStarted(now: now)
        frame(&rate, now: &now, delivered: 30, rttMs: 20)
        let before = rate.kbps
        rate.blocked(now: now)
        XCTAssertEqual(rate.kbps, before / 2, accuracy: 1e-9)
        XCTAssertEqual(rate.hardKbps, before, accuracy: 1e-9)
        rate.dropped()
        rate.connectionStarted(now: now)
        XCTAssertEqual(rate.kbps, before / 4, accuracy: 1e-9)
    }

    func testSetupMessagesDoNotSteer() {
        var rate = Go3SendRate()
        rate.connectionStarted(now: 5000 * ms)
        rate.confirmed(rttMs: 500, frameWrites: 1, frameBytes: 20, frameNanos: ms, now: 5000 * ms)
        XCTAssertEqual(rate.kbps, 30, accuracy: 1e-9)
        XCTAssertEqual(rate.state, .climb)
    }

    func testWritesArePacedToTheTarget() {
        var rate = Go3SendRate()
        let now = 5000 * ms
        rate.connectionStarted(now: now)
        XCTAssertEqual(rate.waitNanos(bytes: 3 * 1024, now: now), 0)
        // 3 KB at 30 KB/s is 100 ms.
        XCTAssertEqual(Double(rate.waitNanos(bytes: 230, now: now)), Double(100 * ms), accuracy: Double(ms) / 100)
    }
}
