// Run with: node --test "report/*.test.mjs"
import { test } from "node:test";
import assert from "node:assert/strict";
import { senderKey } from "./sender.js";

test("an IPv4 address counts whole", () => {
  assert.equal(senderKey("203.0.113.7"), "203.0.113.7");
  assert.notEqual(senderKey("203.0.113.7"), senderKey("203.0.113.8"));
});

test("an IPv6 address counts by its /64, which one home or one phone is given", () => {
  const key = "2001:db8:0:0::/64";
  for (const address of ["2001:db8::1", "2001:db8::2", "2001:db8::3", "2001:0db8:0000:0000:ffff:ffff:ffff:ffff", "2001:DB8::ABCD", "2001:db8:0:0:1:2:3:4"]) {
    assert.equal(senderKey(address), key, address);
  }
  assert.equal(senderKey("2001:db8:0:1::1"), "2001:db8:0:1::/64");
  assert.equal(senderKey("2001:db8:a:b:c:d:e:f"), "2001:db8:a:b::/64");
  assert.equal(senderKey("::1"), "0:0:0:0::/64");
  assert.equal(senderKey("::"), "0:0:0:0::/64");
  assert.equal(senderKey("2001:db8::"), key);
});

test("a zone, brackets or blanks around an IPv6 address change nothing", () => {
  assert.equal(senderKey("fe80::1%eth0"), "fe80:0:0:0::/64");
  assert.equal(senderKey("[2001:db8::1]"), "2001:db8:0:0::/64");
  assert.equal(senderKey(" 2001:db8::1 "), "2001:db8:0:0::/64");
});

test("an IPv4 address written as IPv6 counts as that IPv4 address", () => {
  assert.equal(senderKey("::ffff:203.0.113.7"), "203.0.113.7");
  assert.equal(senderKey("::FFFF:cb00:7107"), "203.0.113.7");
  assert.equal(senderKey("0:0:0:0:0:ffff:203.0.113.7"), "203.0.113.7");
  // Another IPv6 address with an IPv4 address in its last 32 bits is still IPv6.
  assert.equal(senderKey("2001:db8::203.0.113.7"), "2001:db8:0:0::/64");
});

test("what isn't an address counts as itself, so it's still limited", () => {
  for (const odd of ["", "not an address", "2001:db8:::1", "1::2::3", "2001:db8::12345", "1:2:3:4:5:6:7:8:9", ":1:2:3:4:5:6:7", "::ffff:300.0.0.1", "g::1"]) {
    assert.equal(senderKey(odd), odd.trim().toLowerCase(), odd);
  }
  assert.equal(senderKey(null), "");
  assert.equal(senderKey(undefined), "");
});
