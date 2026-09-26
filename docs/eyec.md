# eyec

[Documentation index](README.md)

eyec is an assistant with eyes on the desktop. The phone uses Flux to answer
its permission prompts, chat with the assistant, and run curated desktop
actions.

## Permission prompts

When opencode asks eyec whether it may run a tool, eyec routes the prompt to
the phone through fluxd. The phone shows **Allow**, **Deny**, and
**YOLO**, and the answer decides the prompt on the computer.

The desktop dock still shows the same prompt. The first answer wins: the
dock and the phone race, and either can decide.

### Flow

```text
opencode ── ask ──▶ eyec daemon ── eyec.permit ──▶ fluxd ── flux.eyec ──▶ phone
                        │                                                   │
                        └──────────── decision ◀──────────────────────────┘
```

1. opencode asks eyec about a tool action.
2. The eyec daemon calls `eyec.permit` on the fluxd socket and waits.
3. fluxd sends a `flux.eyec` packet with `"kind": "permit"` to the phone.
4. The phone shows the Eyec screen and sends its decision back.
5. fluxd gives the decision to the waiting `eyec.permit.wait`.
6. eyec answers opencode.

If no phone answers within 120 seconds, eyec keeps waiting for the dock up
to its own 600 second limit. A phone that is offline or that does not run a
Flux version with this plugin is skipped.

**YOLO** turns on eyec's global YOLO flag, so every later prompt is allowed
without asking, exactly like the dock's YOLO button.

## Chat and actions

The phone can also open the **Ask eyec** screen on a computer. It sends a
prompt and shows the answer, the suggested choices, and the result of a
curated action.

- **Ask**: `{"kind":"ask","id","prompt"}` from the phone. fluxd runs the
  prompt on the eyec daemon and replies with
  `{"kind":"answer","id","text","choices","error"}`.
- **Peek**: `{"kind":"peek","id","prompt"}` from the phone. fluxd captures
  the whole screen on the eyec daemon and replies with the same `answer`
  body plus `image` (base64 JPEG), `mime`, and `ocr`. The screen shows in the
  phone chat. The capture shutter still blocks it.
- **Trigger**: `{"kind":"trigger","id","action"}` from the phone. fluxd runs
  one action from a fixed allowlist and replies with
  `{"kind":"trigger","id","ok","detail"}`. The actions are `status`, `last`,
  `dock.toggle`, `dock.show`, `dock.hide`, `shutter.on`, `shutter.off`,
  `redact.on`, `redact.off`, `yolo.on`, and `yolo.off`. A paired phone cannot
  run arbitrary commands.

## Protocol

`flux.eyec` is a Flux extension. It carries one request or answer per packet.

| Direction | Body |
| --- | --- |
| Computer to phone | `{"kind":"permit","id","title","pattern","service","timeout"}` |
| Phone to computer | `{"kind":"permit","id","decision"}` (`allow`, `deny`, or `yolo`) |
| Computer to phone | `{"kind":"cancel","id"}` |
| Phone to computer | `{"kind":"ask","id","prompt"}` |
| Computer to phone | `{"kind":"answer","id","text","choices","error"}` |
| Phone to computer | `{"kind":"peek","id","prompt"}` |
| Computer to phone | `{"kind":"answer","id","text","image","mime","ocr","error"}` |
| Phone to computer | `{"kind":"trigger","id","action"}` |
| Computer to phone | `{"kind":"trigger","id","ok","detail"}` |

The phone and the computer list `flux.eyec` in their capabilities, so the
feature turns on only when both sides understand it.

## IPC methods

The eyec daemon uses these methods on the fluxd socket:

| Method | Params | Result |
| --- | --- | --- |
| `eyec.permit` | `device`, `title`, `pattern`, `service` | `id`, `timeout`, `name` |
| `eyec.permit.wait` | `id` | `{"state": "pending"\|"allow"\|"deny"\|"yolo"}` |
| `eyec.permit.cancel` | `id` | `{}` |
| `eyec.actions` | | `{"actions": [{"id","label"}]}` |
| `eyec.trigger` | `device`, `action` | `{"ok", "detail"}` |

`eyec.permit.wait` blocks for at most 50 seconds and returns `pending`, so
the caller calls it again. One request waits for a phone at a time.

The eyec side is in `eyec/fluxd.py`; it calls these methods and returns the
decision to the eyec daemon's permission handler. fluxd runs the ask and the
trigger with the `eyec` CLI and the eyec socket.

## Security

Pairing is the trust boundary. A paired phone can answer any eyec prompt on
this computer. No signature is required, unlike [fingerprint
approval](approvals.md).
