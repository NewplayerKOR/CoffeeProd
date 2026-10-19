import { readFileSync, writeFileSync } from 'node:fs';

export function tcpHeader(packet, linkType) {
  let ipOffset;
  if (linkType === 276) {
    if (packet.length < 20 || packet.readUInt16BE(0) !== 0x0800) throw new Error('Expected SLL2 IPv4 frame.');
    ipOffset = 20;
  } else if (linkType === 1) {
    if (packet.length < 14) throw new Error('Truncated Ethernet header.');
    ipOffset = 14;
    let protocol = packet.readUInt16BE(12);
    while (protocol === 0x8100 || protocol === 0x88a8) {
      if (packet.length < ipOffset + 4) throw new Error('Truncated VLAN header.');
      protocol = packet.readUInt16BE(ipOffset + 2); ipOffset += 4;
    }
    if (protocol !== 0x0800) throw new Error('Expected Ethernet IPv4 frame.');
  } else throw new Error('Unsupported link type; do not assume it is payload-free.');
  if (packet.length < ipOffset + 20 || packet[ipOffset] >>> 4 !== 4 || packet[ipOffset + 9] !== 6) {
    throw new Error('Expected IPv4 TCP header.');
  }
  const ipLength = (packet[ipOffset] & 15) * 4, tcp = ipOffset + ipLength;
  if (ipLength < 20 || packet.length < tcp + 14 || (packet.readUInt16BE(ipOffset + 6) & 0x3fff) !== 0) {
    throw new Error('Fragmented or truncated TCP base header.');
  }
  const tcpLength = (packet[tcp + 12] >>> 4) * 4;
  if (tcpLength < 20 || packet.length > tcp + tcpLength) throw new Error('Payload bytes present or invalid TCP data offset.');
  const ipTotal = packet.readUInt16BE(ipOffset + 2);
  if (ipTotal < ipLength + tcpLength) throw new Error('Invalid original IP length.');
  const ip = (offset) => [...packet.subarray(offset, offset + 4)].join('.');
  return { source_ip: ip(ipOffset + 12), destination_ip: ip(ipOffset + 16),
    source_port: packet.readUInt16BE(tcp), destination_port: packet.readUInt16BE(tcp + 2),
    sequence: packet.readUInt32BE(tcp + 4), acknowledgment: packet.readUInt32BE(tcp + 8),
    flags: packet[tcp + 13], original_tcp_payload_bytes: ipTotal - ipLength - tcpLength,
    captured_frame_bytes: packet.length, captured_tcp_payload_bytes: 0,
    capture_interface_index: linkType === 276 ? packet.readUInt32BE(4) : null };
}

export function readCapture(buffer) {
  const magic = buffer.subarray(0, 4).toString('hex'), packets = [];
  const add = (bytes, timestamp, linkType, interfaceId) => {
    if (!Number.isFinite(timestamp)) throw new Error('Invalid packet timestamp.');
    packets.push({ timestamp_unix_seconds: timestamp, interface_id: interfaceId, ...tcpHeader(bytes, linkType) });
  };
  if (['d4c3b2a1', 'a1b2c3d4', '4d3cb2a1', 'a1b23c4d'].includes(magic)) {
    if (buffer.length < 24) throw new Error('Truncated pcap header.');
    const little = magic.startsWith('d4') || magic.startsWith('4d'), nano = magic === '4d3cb2a1' || magic === 'a1b23c4d';
    const u32 = (offset) => little ? buffer.readUInt32LE(offset) : buffer.readUInt32BE(offset);
    const linkType = u32(20) & 0xffff;
    for (let offset = 24; offset < buffer.length;) {
      if (offset + 16 > buffer.length) throw new Error('Truncated pcap record.');
      const length = u32(offset + 8);
      if (offset + 16 + length > buffer.length) throw new Error('Truncated captured packet.');
      add(buffer.subarray(offset + 16, offset + 16 + length), u32(offset) + u32(offset + 4) / (nano ? 1e9 : 1e6), linkType, 0);
      offset += 16 + length;
    }
  } else if (magic === '0a0d0d0a') {
    let little = true, interfaces = [];
    for (let offset = 0; offset < buffer.length;) {
      if (offset + 12 > buffer.length) throw new Error('Truncated pcapng block.');
      if (buffer.subarray(offset, offset + 4).toString('hex') === '0a0d0d0a') {
        const bom = buffer.subarray(offset + 8, offset + 12).toString('hex');
        if (!['4d3c2b1a', '1a2b3c4d'].includes(bom)) throw new Error('Invalid pcapng byte order.');
        little = bom === '4d3c2b1a'; interfaces = [];
      }
      const u32 = (at) => little ? buffer.readUInt32LE(at) : buffer.readUInt32BE(at);
      const u16 = (at) => little ? buffer.readUInt16LE(at) : buffer.readUInt16BE(at);
      const type = u32(offset), size = u32(offset + 4), end = offset + size;
      if (size < 12 || size % 4 || end > buffer.length || u32(end - 4) !== size) throw new Error('Invalid pcapng block size.');
      if (type === 1) {
        if (size < 20) throw new Error('Invalid interface block.');
        let resolution = 1e-6;
        for (let option = offset + 16; option + 4 <= end - 4;) {
          const code = u16(option), length = u16(option + 2);
          if (!code) break;
          if (option + 4 + length > end - 4) throw new Error('Invalid interface option.');
          if (code === 9) {
            if (length !== 1) throw new Error('Invalid timestamp resolution.');
            const exponent = buffer[option + 4];
            resolution = exponent & 128 ? 2 ** -(exponent & 127) : 10 ** -exponent;
          }
          if (code === 14) throw new Error('Timestamp offset option is not supported.');
          option += 4 + Math.ceil(length / 4) * 4;
        }
        interfaces.push({ linkType: u16(offset + 8), resolution });
      } else if (type === 6) {
        if (size < 32) throw new Error('Invalid enhanced packet block.');
        const interfaceId = u32(offset + 8), info = interfaces[interfaceId], cap = u32(offset + 20);
        if (!info || offset + 28 + Math.ceil(cap / 4) * 4 > end - 4) throw new Error('Invalid packet/interface.');
        const timestamp = Number((BigInt(u32(offset + 12)) << 32n) + BigInt(u32(offset + 16))) * info.resolution;
        add(buffer.subarray(offset + 28, offset + 28 + cap), timestamp, info.linkType, interfaceId);
      } else if (type === 2 || type === 3) throw new Error('Unsupported legacy packet block.');
      offset = end;
    }
  } else throw new Error('Unsupported capture file.');
  if (!packets.length) throw new Error('No captured packets; not a successful trace.');
  return packets;
}

if (process.argv[1]?.replaceAll('\\', '/').endsWith('/tcp-header-capture.mjs') && process.argv[2]) {
  const packets = readCapture(readFileSync(process.argv[2]));
  if (!process.argv[3]) throw new Error('Supply metadata output path.');
  writeFileSync(process.argv[3], JSON.stringify({ payload_validation: 'PASS: captured TCP payload bytes are zero in every packet',
    packets, clock_alignment_verified: false, limits: ['NIC/offload duplicates and encrypted SSH multiplexing are not HTTP request traces.',
      'Do not subtract timestamps across machines without clock-offset bounds.'] }, null, 2) + '\n');
  console.log(`PASS: ${packets.length} header-only packets; metadata exported.`);
}
