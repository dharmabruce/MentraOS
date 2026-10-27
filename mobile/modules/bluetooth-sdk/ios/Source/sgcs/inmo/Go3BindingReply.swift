import Foundation

/// Typed answer to a binding query (operation 0). Never log the identifier.
enum Go3BindingReply: Equatable {
    case accessFound([UInt8])
    case unbound
    case identifierMissing
    case vendorError
    case malformed
    case limitExceeded
}

/// Minimal bounded protobuf parser for the binding query reply: type 24, field 27, operation 3,
/// status field 4 = {1: bound, 2: identifier, 3: error}. Unknown or ambiguous shapes fail closed.
/// Returns nil for unrelated messages. Swift port of the Android `sgcs/inmo/BindingReplyParser.java`.
enum Go3BindingReplyParser {
    static let maxMessage = 4096

    private struct Invalid: Error {}

    static func parse(_ message: [UInt8]) -> Go3BindingReply? {
        guard message.count <= maxMessage else { return .limitExceeded }
        do {
            var root = Reader(bytes: message, position: 0, end: message.count)
            var type: UInt64?
            var bind: Reader?
            var foreignRoot = false
            while root.more {
                let tag = try root.tag(), field = tag >> 3, wire = tag & 7
                if field == 2 {
                    guard type == nil, wire == 0 else { throw Invalid() }
                    type = try root.scalar()
                } else if field == 27 {
                    guard bind == nil, wire == 2 else { throw Invalid() }
                    bind = try root.nested(max: maxMessage)
                } else if field == 1 {
                    guard wire == 0 else { throw Invalid() }
                    _ = try root.scalar()
                } else {
                    foreignRoot = true
                    try root.skip(wire)
                }
            }
            guard type == 24, var bind else { return nil }
            if foreignRoot { throw Invalid() }

            var operation: UInt64?
            var status: Reader?
            var foreignBind = false
            while bind.more {
                let tag = try bind.tag(), field = tag >> 3, wire = tag & 7
                if field == 1 {
                    guard operation == nil, wire == 0 else { throw Invalid() }
                    operation = try bind.scalar()
                } else if field == 4 {
                    guard status == nil, wire == 2 else { throw Invalid() }
                    status = try bind.nested(max: maxMessage)
                } else {
                    foreignBind = true
                    try bind.skip(wire)
                }
            }
            guard operation == 3 else { return nil }
            if foreignBind { throw Invalid() }
            guard var status else { return .identifierMissing }
            // Vendor implicit-presence default: a present, exactly empty status means unbound.
            if !status.more { return .unbound }

            var bound: UInt64?
            var error: UInt64?
            var id: Reader?
            while status.more {
                let tag = try status.tag(), field = tag >> 3, wire = tag & 7
                if field == 1 {
                    guard bound == nil, wire == 0 else { throw Invalid() }
                    let value = try status.scalar()
                    guard value <= 1 else { throw Invalid() }
                    bound = value
                } else if field == 2 {
                    guard id == nil, wire == 2 else { throw Invalid() }
                    id = try status.nested(max: 256)
                } else if field == 3 {
                    guard error == nil, wire == 0 else { throw Invalid() }
                    error = try status.scalar()
                } else {
                    try status.skip(wire)
                }
            }
            if let error, error != 0 { return .vendorError }
            guard let bound else { return .malformed }
            if bound == 0 { return .unbound }
            guard let id, id.end > id.position else { return .identifierMissing }
            let value = Array(message[id.position ..< id.end])
            return Go3Protocol.validIdentifier(value) ? .accessFound(value) : .malformed
        } catch {
            return .malformed
        }
    }

    /// Bounded protobuf reader; rejects non-canonical and oversized varints.
    struct Reader {
        let bytes: [UInt8]
        var position: Int
        let end: Int

        var more: Bool { position < end }

        mutating func tag() throws -> Int {
            let value = try scalar()
            guard value != 0, value <= 0xFFFF_FFFF, value >> 3 != 0 else { throw Invalid() }
            return Int(value)
        }

        mutating func scalar() throws -> UInt64 {
            var value: UInt64 = 0
            for i in 0 ..< 5 {
                guard position < end else { throw Invalid() }
                let b = bytes[position]
                position += 1
                if i == 4, b & 0xF0 != 0 { throw Invalid() }
                value |= UInt64(b & 0x7F) << (7 * UInt64(i))
                if b & 0x80 == 0 {
                    if i > 0, b == 0 { throw Invalid() }
                    return value
                }
            }
            throw Invalid()
        }

        mutating func nested(max: Int) throws -> Reader {
            let count = try scalar()
            guard count <= UInt64(max), count <= UInt64(end - position) else { throw Invalid() }
            let result = Reader(bytes: bytes, position: position, end: position + Int(count))
            position += Int(count)
            return result
        }

        mutating func skip(_ wire: Int) throws {
            switch wire {
            case 0:
                for i in 0 ..< 10 {
                    guard position < end else { throw Invalid() }
                    let b = bytes[position]
                    position += 1
                    if i == 9, b & 0xFE != 0 { throw Invalid() }
                    if b & 0x80 == 0 { return }
                }
                throw Invalid()
            case 1: try advance(8)
            case 2: _ = try nested(max: maxMessage)
            case 5: try advance(4)
            default: throw Invalid()
            }
        }

        mutating func advance(_ count: Int) throws {
            guard count <= end - position else { throw Invalid() }
            position += count
        }
    }
}

/// Reassembles inbound AA55 frames during one binding query and yields the first typed binding reply.
/// Both glasses endpoints (source 1 and 2, destination 3) are CRC-checked and reassembled independently.
/// Unrelated messages are skipped; framing errors fail closed as `.malformed`.
/// Swift port of the Android `sgcs/inmo/BindingResponseDecoder.java`.
struct Go3BindingResponseDecoder {
    static let maxWireBytes = 32768
    static let maxFragments = 32

    private struct Assembly {
        var bytes: [UInt8] = []
        var sequence = -1
        var fragments = 0
        var nextIndex = 0
    }

    private var pending: [UInt8] = []
    private var assemblies = [Assembly(), Assembly()]
    private var total = 0
    private(set) var reply: Go3BindingReply?

    mutating func accept(_ data: Data) {
        for byte in data {
            guard reply == nil else { return }
            total += 1
            if total > Self.maxWireBytes {
                fail(.limitExceeded)
                return
            }
            pending.append(byte)
            parse()
        }
    }

    private mutating func parse() {
        while pending.count >= 2, reply == nil {
            if pending[0] != 0xAA || pending[1] != 0x55 {
                pending.removeFirst()
                continue
            }
            if pending.count < 12 { return }
            let length = u16(10), count = u16(4), index = u16(6), seq = u16(2)
            let source = Int(pending[8]), destination = Int(pending[9])
            if length > Go3BindingReplyParser.maxMessage || count == 0 || count > Self.maxFragments || index >= count {
                fail(.malformed)
                return
            }
            if pending.count < length + 14 { return }
            if Int(Go3Protocol.crc16(pending[0 ..< length + 12])) != u16(length + 12) {
                fail(.malformed)
                return
            }
            if (source != 1 && source != 2) || destination != 3 {
                fail(.malformed)
                return
            }
            var current = assemblies[source - 1]
            if current.nextIndex == 0 {
                if index != 0 {
                    fail(.malformed)
                    return
                }
                current.sequence = seq
                current.fragments = count
            }
            if seq != current.sequence || count != current.fragments || index != current.nextIndex
                || current.bytes.count + length > Go3BindingReplyParser.maxMessage
            {
                fail(.malformed)
                return
            }
            current.bytes += pending[12 ..< 12 + length]
            current.nextIndex += 1
            pending.removeFirst(length + 14)
            if current.nextIndex == current.fragments {
                let parsed = Go3BindingReplyParser.parse(current.bytes)
                assemblies[source - 1] = Assembly()
                if let parsed { reply = parsed }
            } else {
                assemblies[source - 1] = current
            }
        }
    }

    private mutating func fail(_ kind: Go3BindingReply) {
        reply = kind
        pending.removeAll()
        assemblies = [Assembly(), Assembly()]
    }

    private func u16(_ offset: Int) -> Int {
        Int(pending[offset]) << 8 | Int(pending[offset + 1])
    }
}
