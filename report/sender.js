// Who a report is counted against: the sender, for how many one may send a minute, and the sender's network,
// for how many it may send a day. Both Workers count by them.

/**
 * The sender's [address] as the minute's limit counts it. An IPv4 address counts whole. An IPv6 address counts by
 * its first 64 bits: a provider gives each home, and each phone on a mobile network, a /64 of its own, so the
 * addresses inside one are one sender. An IPv4 address written as IPv6 (::ffff:a.b.c.d) counts as that IPv4
 * address. What isn't an address counts as itself, so it's still limited.
 */
export function senderKey(address) {
  const read = addressOf(address);
  if (read.ipv4) return read.ipv4.join(".");
  if (read.ipv6) return `${read.ipv6.slice(0, 4).map(hex).join(":")}::/64`;
  return read.text;
}

/**
 * The network of the sender at [address], as the day's limit counts it: an IPv4 address by its /24, an IPv6
 * address by its /48, the most a provider gives one customer, so a sender can't count as many by moving within
 * what it was given. A 6to4 address (2002::/16) carries an IPv4 address, and counts by that one's /24. What
 * isn't an address is its own network.
 */
export function networkOf(address) {
  const read = addressOf(address);
  const ipv4 = read.ipv4 ?? (read.ipv6?.[0] === 0x2002 ? [read.ipv6[1] >> 8, read.ipv6[1] & 0xff, read.ipv6[2] >> 8, read.ipv6[2] & 0xff] : null);
  if (ipv4) return `${ipv4.slice(0, 3).join(".")}.0/24`;
  if (read.ipv6) return `${read.ipv6.slice(0, 3).map(hex).join(":")}::/48`;
  return read.text;
}

function hex(group) {
  return group.toString(16);
}

/**
 * [address] read: its four IPv4 bytes, or its eight IPv6 groups, and always its [text], trimmed and in lower case.
 * An IPv4 address written as IPv6 (::ffff:a.b.c.d) is read as IPv4.
 */
function addressOf(address) {
  let text = (address ?? "").trim().toLowerCase();
  if (!text.includes(":")) return { text, ipv4: ipv4Bytes(text) };
  if (text.startsWith("[") && text.endsWith("]")) text = text.slice(1, -1);
  // A zone ("fe80::1%eth0") only says which of the receiver's own links it came in on.
  const groups = ipv6Groups(text.split("%")[0]);
  if (groups && groups.slice(0, 5).every((group) => group === 0) && groups[5] === 0xffff) {
    return { text, ipv4: [groups[6] >> 8, groups[6] & 0xff, groups[7] >> 8, groups[7] & 0xff] };
  }
  return { text: groups ? text : (address ?? "").trim().toLowerCase(), ipv6: groups };
}

/** The four bytes of the IPv4 address [text], or null when it isn't one. */
function ipv4Bytes(text) {
  const parts = text.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  const bytes = parts?.slice(1).map(Number);
  return bytes && bytes.every((byte) => byte <= 255) ? bytes : null;
}

/** The eight 16-bit groups of the IPv6 address [text], in lower case, or null when it isn't one. */
function ipv6Groups(text) {
  // The last 32 bits may be written as an IPv4 address: two groups.
  const dotted = text.match(/^(.*:)(\d+\.\d+\.\d+\.\d+)$/);
  if (dotted) {
    const bytes = ipv4Bytes(dotted[2]);
    if (!bytes) return null;
    text = `${dotted[1]}${hex((bytes[0] << 8) | bytes[1])}:${hex((bytes[2] << 8) | bytes[3])}`;
  }
  const halves = text.split("::");
  if (halves.length > 2) return null;
  const groupsOf = (part) => (part === "" ? [] : part.split(":"));
  const head = groupsOf(halves[0]);
  const tail = halves.length === 2 ? groupsOf(halves[1]) : [];
  const zeros = 8 - head.length - tail.length;
  // "::" stands for at least one group of zeros; without it, all eight are written.
  if (halves.length === 2 ? zeros < 1 : zeros !== 0) return null;
  const groups = [...head, ...Array(halves.length === 2 ? zeros : 0).fill("0"), ...tail];
  return groups.every((group) => /^[0-9a-f]{1,4}$/.test(group)) ? groups.map((group) => parseInt(group, 16)) : null;
}
