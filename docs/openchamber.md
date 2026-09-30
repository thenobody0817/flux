# OpenChamber sessions

[Documentation index](README.md)

Flux for Android shows the [OpenChamber](https://openchamber.dev) sessions that run on your Omarchy computer.
You can see the status of each session, read its messages, and get a notification when a session needs input or finishes.
When you turn on control, you can also send prompts, answer questions and permission prompts, stop a run, start a session, and close one.

The phone uses the Flux link that it already has.
It needs no new port, no firewall rule, and no new pairing.
Your computer needs OpenChamber running.

```text
OpenChamber ── loopback HTTP ── fluxd ── Flux TLS link ── Flux for Android
```

`fluxd` is the only OpenChamber client.
The phone never connects to the OpenChamber port.
`fluxd` reads the port that OpenChamber writes in its settings, and it can follow a different one with `OPENCHAMBER_PORT`.

## Requirements

- OpenChamber 2.0 or newer, running on the same computer as `fluxd`, as the same user.
- OpenChamber API version 1 or newer. The health check reports it.
- A `fluxd` and a Flux for Android that both include this feature.

To check the computer, run:

```sh
flux-cli doctor
```

When OpenChamber runs, `flux-cli doctor` prints this line:

```text
✓ OpenChamber 2.0.3 runs, so the phone can show its sessions
```

### Login

OpenChamber asks for a password from its web interface.
For programs on the same computer it writes a local client token in `~/.config/openchamber/settings.json`, and `fluxd` reads that token for each call.
You do not have to configure anything, and the token follows a password change.
`OPENCHAMBER_TOKEN` overrides the token.

When the phone says that OpenChamber refused the login, restart OpenChamber on the computer and run `flux-cli doctor`.

## See your sessions

1. Start OpenChamber on the computer.
2. Open Flux for Android and select the computer.
3. Select **Sessions**.
4. Select a session to read its recent messages.

The list puts sessions that need input first, then the working ones, then the idle ones.
A session that needs input waits for a question or a permission prompt.
The **Sessions** tile shows the number of sessions that need input.

A session that the computer runs shows as **Working**; one that waits shows as **Needs input**; one that is ready shows as **Idle**.

## Read the messages

The session screen shows the recent messages, with the newest at the bottom.
It reads them again every 5 seconds while the session works, and after each reply.
Select refresh to read them now.

The messages come in one of two forms. The **Rich session output** switch on the device page selects it:

- **Rich** (the default) draws a card per message: your messages, the answers of the agent, its reasoning, and each tool call with its input and its output.
- **Plain** shows the lines that the computer rendered, as one block of text in the colors of the terminal.

The switch applies to every computer, and the phone reads the messages again when you change it.

## Notifications

The phone posts a notification when a session needs input.
It also posts a notification when a session goes from working to ready.
A tap opens the messages of that session.

The phone does not post notifications for the first session list after it connects.
It waits 2 seconds before a finished notification, because the status can change between tool calls.
When the session works again, the phone removes its notification.

The **Session needs input** and **Session finished** switches on the phone's device page turn the notifications off.
They are the same switches as the herdr agents, so they apply to both.
Android also lists the two types as the **Agents that need input** and **Agents that finish** channels.

## Answer a session

Replies are off by default, because a session can run commands on the computer.
To let the phone send prompts and answers, set this key in `~/.config/flux/config.toml`:

```toml
openchamber_control = true
```

Reload the daemon:

```sh
systemctl --user reload fluxd
```

The session screen then shows the reply controls:

- A question shows a card with its fields. A field with choices shows buttons, a yes or no field shows two buttons, and a free-text field shows a text box. Select **Answer** to send the whole form.
- A permission prompt shows **Allow** and **Deny**.
- The text field sends a new prompt to the session. A pause does not end it; select **Send**.
- **Stop the run** stops what the session is doing, without closing it.

Before the first reply, the phone asks for its fingerprint or screen lock.
The unlock stays valid for 5 minutes.
A prompt can have up to 16 KB of text.

## Start a session

With `openchamber_control = true`, the phone can start a session on the computer.

1. Open **Sessions**.
2. Select the add button in the top bar.
3. Under **Run**, select the agent, for example `build` or `plan`.
4. Under **Folder**, type a folder or select one of the OpenChamber projects. `~` is the home folder, and `~/Code/app` is a folder in it.
5. Select **Start**.

Before the first start, the phone asks for its fingerprint or screen lock.
The phone then opens the screen of the new session.
An empty title lets OpenChamber name the session.

## Close a session

With `openchamber_control = true`, select **Close** on the screen of a session, then confirm.
`fluxd` stops the run and files the session away in OpenChamber.
A closed session leaves the list; OpenChamber keeps its messages.

## Turn the feature off

To stop sending sessions to the phone, set this key in `~/.config/flux/config.toml`:

```toml
openchamber = false
```

Reload the daemon:

```sh
systemctl --user reload fluxd
```

The phone then shows that the feature is off on the computer.

## Use another port

`fluxd` finds OpenChamber through the port in `~/.config/openchamber/settings.json`.
To follow another port, set the variable for the service:

```sh
systemctl --user edit fluxd
```

Add these lines, then restart the service:

```ini
[Service]
Environment=OPENCHAMBER_PORT=PORT
```

```sh
systemctl --user restart fluxd
```

`OPENCHAMBER_TOKEN` follows the same way when you use a token other than the one that OpenChamber writes.

## Access and privacy

The phone can read the session list and the recent messages of a session.
With `openchamber_control = true`, it can also send prompts, answer questions and permission prompts, stop a run, start a session, and close one.
A session can run commands, so a reply has the same power as a prompt that you type in OpenChamber.
`fluxd` reads and answers only sessions in the last session list.

`fluxd` logs each reply with the device name, the session, and the kind of the reply.
For a prompt it logs the number of characters, not the text.
It also logs each new session and closed session.

Messages can contain secrets.
They go only to paired phones over the Flux TLS link, and `fluxd` does not write them to its log.

## How it works

`fluxd` subscribes to the event stream of OpenChamber.
After each event it reads the session list and the status, with a short settle window that collects a burst of events into one read.
It also reads them every 5 seconds, which finds a change that no event carried.
It connects again every 5 seconds while OpenChamber is down, and at once when the phone opens the session list.

### Wire format

The packet type is `flux.openchamber`.
Both sides send it.
The `kind` field selects the message.

| Kind | Sender | Body |
| --- | --- | --- |
| `state` | Computer | `enabled`, `running`, `control`, `agents`, `kinds`, and `dirs` |
| `output` | Computer | `session`, `format`, then `text` and `truncated`, or `error`, and `pending` |
| `sent` | Computer | `session` and `action`, and `error` when the reply failed |
| `created` | Computer | `session` or `error` |
| `closed` | Computer | `session`, and `error` when the close failed |
| `request` | Phone | No other fields. The computer answers with `state`. |
| `read` | Phone | `session`, `messages` from 1 to 200, and `format` (`rich` or `plain`). Zero messages means 40. |
| `prompt` | Phone | `session` and `text`. The computer answers with `sent`. |
| `interrupt` | Phone | `session`. The computer answers with `sent`. |
| `form` | Phone | `session`, `form`, and `answer`. The computer answers with `sent`. |
| `permission` | Phone | `session`, `permission`, and `decision` (`allow` or `deny`). The computer answers with `sent`. |
| `create` | Phone | `agent`, `cwd`, and `title`. The computer answers with `created`. |
| `close` | Phone | `session`. The computer answers with `closed`. |

The computer sends `state` when the phone connects, after each change, and as the answer to `request`.
Each session has `id`, `title`, `agent`, `status`, `project`, `model`, `waiting`, and `updated`:

```json
{"kind":"state","enabled":true,"running":true,"control":false,"kinds":[{"id":"build","name":"Build"}],"dirs":["~","~/Code/flux"],"agents":[{"id":"ses_abc","title":"Fix the build","agent":"build","status":"working","project":"flux","model":"deepseek-flash","waiting":"","updated":1790726898269}]}
```

`status` is `idle`, `working`, or `blocked`.
A session is blocked while it waits for a question or a permission prompt; `waiting` is then `form` or `permission`.
`project` is the base name of the OpenChamber project of the session.
`kinds` lists the agents that the computer can start, and `dirs` lists the folders for a new session, as `~` paths.
Both lists are empty when `control` is `false`.

A `read` with `"format":"rich"` gets an `output` with `"format":"rich"`.
Its `text` is a JSON list of entries with short keys: `r` is the role (`u` for a user message, `a` for an agent message or paragraph, `r` for reasoning, and `t` for a tool), `t` is the text, `n` the tool name, `s` the tool status, `i` the tool input, `o` the tool output, and `e` a tool error.
Without `rich`, `text` is plain lines with a header for each message.
`text` has at most 1 MB, and `truncated` is `true` when older messages were cut.

`pending` lists what the session waits for:

```json
{"kind":"output","session":"ses_abc","pending":[{"kind":"form","id":"frm_1","title":"Run it on production too?","fields":[{"key":"choice","type":"string","label":"Pick one","required":true,"options":[{"value":"1","label":"Yes"}]}]},{"kind":"permission","id":"perm_1","action":"shell","resources":["bin/rails db:migrate"]}]}
```

A form answer maps each field key to its value, for example `{"choice":"1"}`.
An external field takes `true`, which acknowledges it.

A reply and its answer look like this:

```json
{"kind":"permission","session":"ses_abc","permission":"perm_1","decision":"allow"}
{"kind":"sent","session":"ses_abc","action":"permission"}
```

`flux-cli status --json` includes the same session state in its `openchamber` field.

## Troubleshoot

| Problem | Next step |
| --- | --- |
| The **Sessions** tile is missing | Update `fluxd` and Flux for Android. The tile shows only when the computer sends `flux.openchamber`. |
| The phone says that OpenChamber is not running | Run `flux-cli doctor` on the computer, and start OpenChamber. |
| The list is empty | Start a session in OpenChamber, or select + on the **Sessions** screen. |
| The phone says that the feature is off | Set `openchamber = true` and reload `fluxd`. |
| The phone says that replies are off | Set `openchamber_control = true` and reload `fluxd`. |
| The add button is missing | Set `openchamber_control = true` and reload `fluxd`. OpenChamber must run. |
| A new session says that it did not start | Run `flux-cli doctor` and read the error. The folder must exist on the computer. |
| The messages show as plain lines | Turn on **Rich session output** on the device page. |
| An agent is missing from the start list | It must be a primary agent of OpenChamber, not a subagent or a hidden one. |
| The phone says that OpenChamber refused the login | Restart OpenChamber on the computer so that it writes its local client token, then run `flux-cli doctor`. |
| OpenChamber listens on another port | Set `OPENCHAMBER_PORT` for the service, see [Use another port](#use-another-port). |

To read the OpenChamber messages of the daemon, run:

```sh
journalctl --user -u fluxd --no-pager | grep openchamber
```
