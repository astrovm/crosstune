// Who a report is counted against, for the limit on how many one sender may send. Both Workers count by it.

/**
 * The sender's [address] as the limit counts it. An IPv4 address counts whole. An IPv6 address counts by its
 * first 64 bits: a provider gives each home, and each phone on a mobile network, a /64 of its own, so the
 * addresses inside one are one sender. An IPv4 address written as IPv6 (::ffff:a.b.c.d) counts as that IPv4
 * address. What isn't an address counts as itself, so it's still limited.
 */
export function senderKey(address) {
  let text = (address ?? "").trim().toLowerCase();
  if (text.startsWith("[") && text.endsWith("]")) text = text.slice(1, -1);
  // A zone ("fe80::1%eth0") only says which of the receiver's own links it came in on.
  if (text.includes(":")) text = text.split("%")[0];
  const groups = text.includes(":") ? ipv6Groups(text) : null;
  if (!groups) return text;
  if (groups.slice(0, 5).every((group) => group === 0) && groups[5] === 0xffff) {
    return [groups[6] >> 8, groups[6] & 0xff, groups[7] >> 8, groups[7] & 0xff].join(".");
  }
  return `${groups.slice(0, 4).map((group) => group.toString(16)).join(":")}::/64`;
}

/** The eight 16-bit groups of the IPv6 address [text], in lower case, or null when it isn't one. */
function ipv6Groups(text) {
  // The last 32 bits may be written as an IPv4 address: two groups.
  const dotted = text.match(/^(.*:)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (dotted) {
    const bytes = dotted.slice(2).map(Number);
    if (bytes.some((byte) => byte > 255)) return null;
    text = `${dotted[1]}${((bytes[0] << 8) | bytes[1]).toString(16)}:${((bytes[2] << 8) | bytes[3]).toString(16)}`;
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
