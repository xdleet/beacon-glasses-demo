# Known issues / future work

Honest list, as of Nov 1, 2026.

## Known issues

- Phone must stay in Bluetooth range of the watch and glasses. Out of
  range, one-tap cannot reach the phone.
- Backend LAN address can change with DHCP (192.168.1.227 → .248 on
  Nov 1, 2026). If the phone says "Backend unreachable", update the
  Backend URL to the desktop's current LAN IP first.
- Phone screen-off / Doze survival, camera-app-foreground recording +
  data stream together, and away-from-LAN field use are not yet fully
  proven. The proven test was on home LAN, phone on Ready.
- Watch install must be a real .apk. Chat attachment downloads arrive
  as .bin and do not update the app — check the bottom version label
  after every install.
- Watch app must be installed over, never uninstalled, or the saved
  API key is wiped.

## Future work

- Fixed / discoverable backend address (static LAN IP or a permanent
  hostname), so the Backend URL never goes stale.
- One-tap from the watch with the phone asleep in a pocket, proven
  end-to-end in the field, not just on home LAN.
- Shorter answer rendering on the round watch screen (text is cut at
  the edges on a round display; the demo uses full-screen screenshots
  at the end so judges can read the full answer).
- Paid / gated tiers for the separate Ninja.TEK chat and video bots
  are a different project and are not part of this hackathon entry.
