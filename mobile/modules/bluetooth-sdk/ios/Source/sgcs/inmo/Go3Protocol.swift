import Foundation

/// Measured INMO GO3 AA55 framing and the messages MentraOS sends. Images display through the
/// Navigation app (openNavigation → destinationArrived(png) → openNavigation).
/// Swift port of the Android `sgcs/inmo/Go3Protocol.java`; protocol notes: lens-spark@3e2158f docs/PROTOCOL.md.
struct Go3Protocol {
    static let maxFragment = 230

    private var sequence: UInt16 = 0x7000

    // MARK: - Messages

    /// Enables the protocol bridge on the GATT channel. Not a registration write.
    static let enableBridge: [UInt8] = [0x10, 0x14, 0xA2, 0x01, 0x02, 0x50, 0x00]
    /// Operation 0: read-only binding query.
    static let queryBindState: [UInt8] = [0x10, 0x18, 0xDA, 0x01, 0x02, 0x08, 0x00]
    static let advertiseNavigation: [UInt8] = [0x10, 0x23, 0xB2, 0x02, 0x03, 0x0A, 0x01, 0x02]
    static let openNavigation: [UInt8] = [0x10, 0x0F, 0x92, 0x01, 0x02, 0x08, 0x0D]

    /// Operation 1: authenticate with an existing binding identifier. Nil if the identifier is invalid.
    static func login(_ identifier: [UInt8]) -> [UInt8]? {
        guard validIdentifier(identifier) else { return nil }
        let inner = [0x08, 0x01, 0x12] + varint(identifier.count) + identifier
        return [0x10, 0x18, 0xDA, 0x01] + varint(inner.count) + inner
    }

    /// Wraps a PNG in the Navigation "destination arrived" message, which is how the GO3 shows an image.
    static func destinationArrived(_ png: [UInt8]) -> [UInt8] {
        let arrived = [0x08] + varint(1864) + [0x10] + varint(418)
            + [0x1A, 0x03, 0x55, 0x53, 0x41, 0x22] + varint(png.count) + png
        let navigation = [0x08, 0x0A, 0x62] + varint(arrived.count) + arrived
        return [0x10, 0x21, 0xA2, 0x02] + varint(navigation.count) + navigation
    }

    /// Opaque binding identifier bytes: bounded, non-empty, not all zero.
    static func validIdentifier(_ value: [UInt8]) -> Bool {
        !value.isEmpty && value.count <= 256 && value.contains { $0 != 0 }
    }

    // MARK: - Framing

    /// Splits one message into AA55 frames of at most 230 payload bytes; each frame is one GATT write.
    mutating func frames(_ payload: [UInt8]) -> [Data] {
        let count = max(1, (payload.count + Self.maxFragment - 1) / Self.maxFragment)
        let current = sequence
        sequence = sequence &+ 1
        var result: [Data] = []
        result.reserveCapacity(count)
        for index in 0 ..< count {
            let offset = index * Self.maxFragment
            let length = min(Self.maxFragment, payload.count - offset)
            var frame: [UInt8] = [0xAA, 0x55]
            frame += u16(Int(current)) + u16(count) + u16(index) + [0x03, 0x01] + u16(length)
            frame += payload[offset ..< offset + length]
            frame += u16(Int(Self.crc16(frame)))
            result.append(Data(frame))
        }
        return result
    }

    /// Whether this frame (one GATT write) is sent confirmed: the last fragment of a short message, so the
    /// Open after each image (one confirmation per frame) and each session setup message; never an image fragment.
    static func confirms(_ frame: Data) -> Bool {
        let bytes = [UInt8](frame.prefix(8))
        guard bytes.count == 8 else { return false }
        let count = Int(bytes[4]) << 8 | Int(bytes[5])
        let index = Int(bytes[6]) << 8 | Int(bytes[7])
        return index + 1 == count && count < Go3SendRate.minFrameWrites
    }

    /// CRC-16/CCITT-FALSE.
    static func crc16<C: Collection>(_ data: C) -> UInt16 where C.Element == UInt8 {
        var crc: UInt16 = 0xFFFF
        for byte in data {
            crc ^= UInt16(byte) << 8
            for _ in 0 ..< 8 {
                crc = crc & 0x8000 != 0 ? (crc << 1) ^ 0x1021 : crc << 1
            }
        }
        return crc
    }

    static func varint(_ value: Int) -> [UInt8] {
        var remaining = UInt32(value)
        var out: [UInt8] = []
        while remaining & ~0x7F != 0 {
            out.append(UInt8(remaining & 0x7F) | 0x80)
            remaining >>= 7
        }
        out.append(UInt8(remaining))
        return out
    }

    private func u16(_ value: Int) -> [UInt8] {
        [UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)]
    }
}
