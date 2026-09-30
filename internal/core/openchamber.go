package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"time"

	"flux/internal/lan"
	"flux/internal/openchamber"
	"flux/internal/proto"
)

// openchamberPoll is how often fluxd reads the sessions when no event
// arrives. The event stream brings most changes sooner; the poll is the
// safety net for an event that OpenChamber does not send.
const openchamberPoll = 5 * time.Second

// openchamberRetry is how long fluxd waits before it connects again.
const openchamberRetry = 5 * time.Second

// openchamberSettle collects a burst of events into one read.
const openchamberSettle = 200 * time.Millisecond

// openchamberReadTimeout limits a read for a phone.
const openchamberReadTimeout = 10 * time.Second

// openchamberCallTimeout limits a reply and the other short calls.
const openchamberCallTimeout = 5 * time.Second

// openchamberKindsTTL is how long fluxd keeps the list of agent kinds.
const openchamberKindsTTL = time.Minute

// openchamberStateWait is how long fluxd waits for a new session in its state.
const openchamberStateWait = 3 * time.Second

// Limits of a read for a phone.
const (
	openchamberDefaultMessages = 40
	openchamberMaxMessages     = 200
	openchamberMaxText         = 1 << 20
	openchamberSessionLimit    = 100
)

// errOpenChamberOff ends a session when the user turns the feature off.
var errOpenChamberOff = errors.New("openchamber sync is off")

// OpenChamberAgent is one OpenChamber session as the phone sees it. Status
// is "idle", "working", or "blocked". Waiting is "form" or "permission"
// while the session waits for an answer, and it is empty otherwise.
type OpenChamberAgent struct {
	ID      string `json:"id"`
	Title   string `json:"title"`
	Agent   string `json:"agent"`
	Status  string `json:"status"`
	Project string `json:"project"`
	Model   string `json:"model"`
	Waiting string `json:"waiting"`
	Updated int64  `json:"updated"`
	// Dir is the folder of the session. It goes only to fluxd, which uses
	// it to file the session away.
	Dir string `json:"-"`
}

// OpenChamberKind is one agent that OpenChamber can start a session with.
type OpenChamberKind struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// openChamberLive is what the OpenChamber loop read.
type openChamberLive struct {
	Agents []OpenChamberAgent
	Kinds  []OpenChamberKind
	// Dirs are the folders that a new session can start in, as ~ paths.
	Dirs []string
}

// openChamberView is the OpenChamber state in a flux.openchamber state
// packet and in the IPC state. Control is true when a phone can reply,
// interrupt, start, and close sessions.
type openChamberView struct {
	Enabled bool               `json:"enabled"`
	Running bool               `json:"running"`
	Control bool               `json:"control"`
	Agents  []OpenChamberAgent `json:"agents"`
	Kinds   []OpenChamberKind  `json:"kinds"`
	Dirs    []string           `json:"dirs"`
}

// openchamberControlLocked reports whether a phone can reply to sessions,
// interrupt them, start them, and close them.
func (d *Daemon) openchamberControlLocked() bool {
	return d.cfg.OpenChamber && d.cfg.OpenChamberControl
}

// openchamberControlOn reports whether openchamber_control is on.
func (d *Daemon) openchamberControlOn() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.openchamberControlLocked()
}

// openchamberViewLocked returns the state for the phones.
func (d *Daemon) openchamberViewLocked() openChamberView {
	v := openChamberView{
		Enabled: d.cfg.OpenChamber, Running: d.cfg.OpenChamber && d.ocRunning,
		Control: d.openchamberControlLocked(),
		Agents:  d.ocAgents, Kinds: d.ocKinds, Dirs: d.ocDirs,
	}
	if !v.Enabled || v.Agents == nil {
		v.Agents = []OpenChamberAgent{}
	}
	if !v.Control || v.Kinds == nil {
		v.Kinds = []OpenChamberKind{}
	}
	if !v.Control || v.Dirs == nil {
		v.Dirs = []string{}
	}
	return v
}

func openchamberStatePacket(v openChamberView) *proto.Packet {
	return proto.New(proto.TypeFluxOpenChamber, map[string]any{
		"kind": "state", "enabled": v.Enabled, "running": v.Running,
		"control": v.Control, "agents": v.Agents, "kinds": v.Kinds, "dirs": v.Dirs,
	})
}

// openchamberLoop follows the OpenChamber sessions and sends each change
// to the phones. It connects again after OpenChamber stops or restarts.
func (d *Daemon) openchamberLoop(ctx context.Context) {
	logged := ""
	for ctx.Err() == nil {
		if !d.openchamberEnabled() {
			d.setOpenChamber(false, openChamberLive{})
			d.openchamberPause(ctx, 0)
			continue
		}
		err := d.openchamberSession(ctx)
		d.setOpenChamber(false, openChamberLive{})
		if ctx.Err() != nil {
			return
		}
		if errors.Is(err, errOpenChamberOff) {
			continue
		}
		// OpenChamber is often not running. Log each problem once, not at
		// each retry.
		if msg := err.Error(); msg != logged {
			d.logf("openchamber: %v", err)
			logged = msg
		}
		d.openchamberPause(ctx, openchamberRetry)
	}
}

// openchamberSession follows one OpenChamber server until the connection
// fails or the user turns the feature off. Any event makes fluxd read the
// sessions again, so the content of an event does not matter.
func (d *Daemon) openchamberSession(ctx context.Context) error {
	health, err := d.oc.Health(ctx)
	if err != nil {
		return err
	}
	if health.Compatibility.APIVersion < openchamber.MinAPIVersion {
		return fmt.Errorf("OpenChamber %s uses API version %d, and Flux needs %d or newer",
			health.Version, health.Compatibility.APIVersion, openchamber.MinAPIVersion)
	}
	d.logf("OpenChamber %s: following its sessions", health.Version)

	stream, err := d.oc.Subscribe(ctx)
	if err != nil {
		return err
	}
	defer stream.Close()
	events := make(chan struct{}, 1)
	done := make(chan error, 1)
	go func() {
		for {
			if _, err := stream.Next(); err != nil {
				done <- err
				return
			}
			select {
			case events <- struct{}{}:
			default:
			}
		}
	}()

	var kinds []OpenChamberKind
	var kindsAt time.Time
	tick := time.NewTicker(openchamberPoll)
	defer tick.Stop()
	for {
		live, err := d.openchamberRead(ctx, &kinds, &kindsAt)
		if err != nil {
			return err
		}
		d.setOpenChamber(true, live)

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-d.ocWake:
			if !d.openchamberEnabled() {
				return errOpenChamberOff
			}
		case err := <-done:
			return err
		case <-tick.C:
		case <-events:
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(openchamberSettle):
			}
			select {
			case <-events:
			default:
			}
		}
	}
}

// openchamberRead reads the sessions and their status in one pass.
func (d *Daemon) openchamberRead(ctx context.Context, kinds *[]OpenChamberKind, kindsAt *time.Time) (openChamberLive, error) {
	q := map[string][]string{"limit": {fmt.Sprint(openchamberSessionLimit)}}
	sessions, err := d.oc.Sessions(ctx, q)
	if err != nil {
		return openChamberLive{}, err
	}
	status, err := d.oc.Status(ctx)
	if err != nil {
		return openChamberLive{}, err
	}
	projects := map[string]string{}
	var dirs []string
	if list, err := d.oc.Projects(ctx); err == nil {
		for _, p := range list {
			projects[p.ID] = p.Canonical
			if p.Canonical != "" {
				dirs = append(dirs, p.Canonical)
			}
		}
	}
	// An agent that the user adds shows after a minute. Only control uses
	// the list, so fluxd skips the lookup while it is off.
	if !d.openchamberControlOn() {
		*kinds, *kindsAt, dirs = nil, time.Time{}, nil
	} else if time.Since(*kindsAt) > openchamberKindsTTL {
		ks, err := d.oc.Kinds(ctx, "")
		if err != nil {
			return openChamberLive{}, err
		}
		*kinds, *kindsAt = openchamberKinds(ks), time.Now()
	}
	return openChamberLive{
		Agents: openchamberAgents(sessions, status, projects),
		Kinds:  *kinds,
		Dirs:   openchamberDirs(dirs),
	}, nil
}

// openchamberDirs returns the folders for a new session, in home-relative
// form, without duplicates, and without the global project or the trash.
func openchamberDirs(dirs []string) []string {
	home, _ := os.UserHomeDir()
	out := make([]string, 0, len(dirs))
	seen := map[string]bool{}
	for _, dir := range dirs {
		if dir == "/" || dir == "" || strings.Contains(dir, "/.local/share/Trash/") || strings.HasSuffix(dir, "/.local/share/Trash") {
			continue
		}
		rel := homeRelative(dir, home)
		if rel == "" || seen[rel] {
			continue
		}
		seen[rel] = true
		out = append(out, rel)
	}
	sort.Strings(out)
	if len(out) == 0 {
		return nil
	}
	return out
}

// openchamberAgents converts the sessions for the phone, blocked first and
// then most recently updated.
func openchamberAgents(sessions []openchamber.Session, status openchamber.Status, projects map[string]string) []OpenChamberAgent {
	out := make([]OpenChamberAgent, 0, len(sessions))
	for _, s := range sessions {
		if s.ID == "" {
			continue
		}
		st, waiting := openchamberStatus(s.ID, status)
		project := ""
		if dir, ok := projects[s.ProjectID]; ok {
			project = filepath.Base(dir)
			if dir == "/" {
				project = ""
			}
		}
		out = append(out, OpenChamberAgent{
			ID: s.ID, Title: s.Title, Agent: s.Agent, Status: st, Project: project,
			Model: s.Model.ID, Waiting: waiting, Updated: s.Time.Updated,
			Dir: s.Location.Directory,
		})
	}
	sort.SliceStable(out, func(i, j int) bool {
		if a, b := openchamberRank(out[i].Status), openchamberRank(out[j].Status); a != b {
			return a < b
		}
		return out[i].Updated > out[j].Updated
	})
	return out
}

// openchamberRank orders the agents on the phone.
func openchamberRank(status string) int {
	switch status {
	case "blocked":
		return 0
	case "working":
		return 1
	case "idle":
		return 2
	}
	return 3
}

// openchamberStatus returns the status of a session and what it waits for.
// A pending question or permission blocks the session.
func openchamberStatus(id string, status openchamber.Status) (string, string) {
	if p, ok := status.Pending[id]; ok {
		switch {
		case len(p.Forms) > 0:
			return "blocked", "form"
		case len(p.Permissions) > 0:
			return "blocked", "permission"
		}
	}
	switch status.Sessions[id].Status {
	case "busy", "retry":
		return "working", ""
	}
	return "idle", ""
}

// openchamberKinds converts the agent kinds for the phone.
func openchamberKinds(kinds []openchamber.Kind) []OpenChamberKind {
	out := make([]OpenChamberKind, 0, len(kinds))
	for _, k := range kinds {
		if k.ID == "" {
			continue
		}
		name := k.Name
		if name == "" {
			name = k.ID
		}
		out = append(out, OpenChamberKind{ID: k.ID, Name: name})
	}
	return out
}

func (d *Daemon) openchamberEnabled() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.cfg.OpenChamber
}

// openchamberPause waits for the delay, a wake signal, or the end of ctx.
// A zero delay waits with no limit.
func (d *Daemon) openchamberPause(ctx context.Context, delay time.Duration) {
	var after <-chan time.Time
	if delay > 0 {
		t := time.NewTimer(delay)
		defer t.Stop()
		after = t.C
	}
	select {
	case <-ctx.Done():
	case <-d.ocWake:
	case <-after:
	}
}

// wakeOpenChamber makes the OpenChamber loop read the sessions now. A loop
// that waits to connect again tries at once.
func (d *Daemon) wakeOpenChamber() {
	select {
	case d.ocWake <- struct{}{}:
	default:
	}
}

// setOpenChamber records the OpenChamber state and sends it to the phones
// when it changed.
func (d *Daemon) setOpenChamber(running bool, live openChamberLive) {
	d.mu.Lock()
	if d.ocRunning == running && slices.Equal(d.ocAgents, live.Agents) &&
		slices.Equal(d.ocKinds, live.Kinds) && slices.Equal(d.ocDirs, live.Dirs) {
		d.mu.Unlock()
		return
	}
	d.ocRunning, d.ocAgents, d.ocKinds, d.ocDirs = running, live.Agents, live.Kinds, live.Dirs
	d.mu.Unlock()
	d.sendOpenChamber()
}

// openchamberChanged sends the state after the user changes the setting.
func (d *Daemon) openchamberChanged() {
	d.wakeOpenChamber()
	d.sendOpenChamber()
}

// sendOpenChamber sends the state to each paired phone that is connected
// and accepts flux.openchamber.
func (d *Daemon) sendOpenChamber() {
	d.mu.Lock()
	p := openchamberStatePacket(d.openchamberViewLocked())
	var links []*lan.Link
	for _, dev := range d.devices {
		if dev.Paired && dev.link != nil && dev.accepts(proto.TypeFluxOpenChamber) {
			links = append(links, dev.link)
		}
	}
	d.mu.Unlock()
	for _, l := range links {
		_ = l.Send(p)
	}
	d.markDirty()
}

// handleOpenChamber answers a flux.openchamber packet from a phone.
func (d *Daemon) handleOpenChamber(dev *Device, l *lan.Link, p *proto.Packet) {
	var body struct {
		Kind       string          `json:"kind"`
		Session    string          `json:"session"`
		Messages   int             `json:"messages"`
		Format     string          `json:"format"`
		Text       string          `json:"text"`
		Form       string          `json:"form"`
		Permission string          `json:"permission"`
		Decision   string          `json:"decision"`
		Answer     json.RawMessage `json:"answer"`
		Agent      string          `json:"agent"`
		Cwd        string          `json:"cwd"`
		Title      string          `json:"title"`
	}
	if p.Decode(&body) != nil {
		return
	}
	switch body.Kind {
	case "request":
		// The phone opened its session list. When OpenChamber was not
		// running, fluxd tries to connect again now.
		d.wakeOpenChamber()
		d.mu.Lock()
		state := openchamberStatePacket(d.openchamberViewLocked())
		d.mu.Unlock()
		_ = l.Send(state)
	case "read":
		go func() { _ = l.Send(d.readOpenChamber(body.Session, body.Messages, body.Format == "rich")) }()
	case "prompt":
		go func() { _ = l.Send(d.openchamberPrompt(dev, body.Session, body.Text)) }()
	case "interrupt":
		go func() { _ = l.Send(d.openchamberInterrupt(dev, body.Session)) }()
	case "form":
		go func() { _ = l.Send(d.openchamberForm(dev, body.Session, body.Form, body.Answer)) }()
	case "permission":
		go func() { _ = l.Send(d.openchamberPermission(dev, body.Session, body.Permission, body.Decision)) }()
	case "create":
		go func() {
			reply := d.openchamberCreate(dev, body.Agent, body.Cwd, body.Title)
			// The phone opens the new session at once, so it must know
			// the session before the answer.
			d.mu.Lock()
			state := openchamberStatePacket(d.openchamberViewLocked())
			d.mu.Unlock()
			_ = l.Send(state)
			_ = l.Send(reply)
		}()
	case "close":
		go func() { _ = l.Send(d.openchamberClose(dev, body.Session)) }()
	default:
		d.logf("%s: unknown flux.openchamber kind %q", dev.Name, body.Kind)
	}
}

// readOpenChamber returns an output packet with the recent messages of a
// session. It reads only a session in the last state, so a phone cannot
// read other sessions. With rich, text carries a JSON list of entries for
// the app to draw as cards; without it, text is plain lines.
func (d *Daemon) readOpenChamber(session string, messages int, rich bool) *proto.Packet {
	reply := map[string]any{"kind": "output", "session": session}
	if rich {
		reply["format"] = "rich"
	}
	d.mu.Lock()
	enabled := d.cfg.OpenChamber
	known := d.openchamberAgentLocked(session)
	d.mu.Unlock()
	switch {
	case !enabled:
		reply["error"] = "OpenChamber sync is off on this computer"
	case !known:
		reply["error"] = fmt.Sprintf("No session %s runs on this computer", session)
	default:
		ctx, cancel := context.WithTimeout(d.ctx, openchamberReadTimeout)
		msgs, err := d.oc.Messages(ctx, session, openchamberMessages(messages))
		status, serr := d.oc.Status(ctx)
		cancel()
		if err != nil {
			reply["error"] = openchamberError(err)
			break
		}
		if serr == nil {
			reply["pending"] = openchamberPending(session, status)
		} else {
			reply["pending"] = []any{}
		}
		if rich {
			text, truncated := openchamberRich(msgs, openchamberMaxText)
			reply["text"], reply["truncated"] = text, truncated
		} else {
			text, truncated := openchamberPlain(msgs, openchamberMaxText)
			reply["text"], reply["truncated"] = text, truncated
		}
	}
	return proto.New(proto.TypeFluxOpenChamber, reply)
}

// openchamberAgentLocked reports whether the session is in the last state.
func (d *Daemon) openchamberAgentLocked(session string) bool {
	return slices.ContainsFunc(d.ocAgents, func(a OpenChamberAgent) bool { return a.ID == session })
}

// openchamberAgentDirLocked returns the folder of a session in the last
// state. An empty result means that fluxd does not know the session.
func (d *Daemon) openchamberAgentDirLocked(session string) string {
	for _, a := range d.ocAgents {
		if a.ID == session {
			return a.Dir
		}
	}
	return ""
}

// openchamberMessages returns the message count for a read. Zero or less
// means the default.
func openchamberMessages(n int) int {
	switch {
	case n <= 0:
		return openchamberDefaultMessages
	case n > openchamberMaxMessages:
		return openchamberMaxMessages
	}
	return n
}

// openchamberPending lists what a session waits for, for the phone.
func openchamberPending(session string, status openchamber.Status) []any {
	out := []any{}
	p, ok := status.Pending[session]
	if !ok {
		return out
	}
	for _, f := range p.Forms {
		out = append(out, map[string]any{"kind": "form", "id": f.ID, "title": f.Title, "fields": openchamberFields(f)})
	}
	for _, perm := range p.Permissions {
		out = append(out, map[string]any{"kind": "permission", "id": perm.ID, "action": perm.Action, "resources": perm.Resources})
	}
	return out
}

// openchamberFields converts the fields of a form for the phone.
func openchamberFields(f openchamber.Form) []any {
	out := make([]any, 0, len(f.Fields))
	for _, field := range f.Fields {
		if field.Key == "" {
			continue
		}
		options := make([]any, 0, len(field.Options))
		for _, o := range field.Options {
			label := o.Label
			if label == "" {
				label = o.Value
			}
			options = append(options, map[string]any{"value": o.Value, "label": label})
		}
		out = append(out, map[string]any{
			"key": field.Key, "type": field.Type, "label": field.Label,
			"options": options, "required": field.Required,
		})
	}
	return out
}

// openchamberError returns the text for a phone about a failed call.
func openchamberError(err error) string {
	var oe *openchamber.Error
	if errors.As(err, &oe) {
		switch oe.Code {
		case "unreachable":
			return "OpenChamber does not answer on this computer"
		case "not_found":
			return "That session is gone"
		case "unauthorized":
			return "OpenChamber refused the login. Restart OpenChamber on this computer, then run flux-cli doctor"
		}
		return "OpenChamber: " + oe.Message
	}
	return "OpenChamber does not answer on this computer"
}

// openchamberPlain renders the messages as plain lines for the phone. The
// newest message comes last.
func openchamberPlain(msgs []openchamber.Message, max int) (string, bool) {
	var b strings.Builder
	for _, m := range slices.Backward(msgs) {
		switch m.Type {
		case "user":
			text := strings.TrimSpace(m.Text)
			if text == "" {
				continue
			}
			b.WriteString("▌ You\n")
			b.WriteString(text)
			b.WriteString("\n\n")
		case "assistant":
			var head bool
			for _, part := range m.Content {
				switch part.Type {
				case "text":
					if strings.TrimSpace(part.Text) == "" {
						continue
					}
					if !head {
						head = true
						b.WriteString("▌ " + openchamberWho(m))
						b.WriteString("\n")
					}
					b.WriteString(strings.TrimSpace(part.Text))
					b.WriteString("\n\n")
				case "tool":
					if !head {
						head = true
						b.WriteString("▌ " + openchamberWho(m))
						b.WriteString("\n")
					}
					b.WriteString("⚙ " + part.Name)
					if s := openchamberToolSummary(part); s != "" {
						b.WriteString("  " + s)
					}
					b.WriteString("\n")
					if out := strings.TrimSpace(openchamberToolOutput(part)); out != "" {
						b.WriteString(out)
						b.WriteString("\n")
					}
					b.WriteString("\n")
				}
			}
		}
	}
	return tailText(b.String(), max)
}

// openchamberRich renders the messages as a JSON list of entries. Keys are
// short to keep the packet small: r is the role (u, a, t, r), t the text,
// n the tool name, s the tool status, i the tool input, and o its output.
func openchamberRich(msgs []openchamber.Message, max int) (string, bool) {
	entries := make([]map[string]any, 0, len(msgs))
	for _, m := range slices.Backward(msgs) {
		switch m.Type {
		case "user":
			if text := strings.TrimSpace(m.Text); text != "" {
				entries = append(entries, map[string]any{"r": "u", "t": text})
			}
		case "assistant":
			entries = append(entries, map[string]any{"r": "a", "n": openchamberWho(m)})
			for _, part := range m.Content {
				switch part.Type {
				case "text":
					if text := strings.TrimSpace(part.Text); text != "" {
						entries = append(entries, map[string]any{"r": "a", "t": text})
					}
				case "reasoning":
					if text := strings.TrimSpace(part.Text); text != "" {
						entries = append(entries, map[string]any{"r": "r", "t": clamp(text, 2000)})
					}
				case "tool":
					entry := map[string]any{"r": "t", "n": part.Name, "s": part.State.Status}
					if s := openchamberToolSummary(part); s != "" {
						entry["i"] = s
					}
					if out := strings.TrimSpace(openchamberToolOutput(part)); out != "" {
						entry["o"] = clamp(out, 4000)
					}
					if part.State.Error != "" {
						entry["e"] = part.State.Error
					}
					entries = append(entries, entry)
				}
			}
		}
	}
	data, _ := json.Marshal(entries)
	return tailText(string(data), max)
}

// openchamberWho names the writer of an assistant message.
func openchamberWho(m openchamber.Message) string {
	name := m.Agent
	if name == "" {
		name = "Agent"
	}
	if m.Model.ID != "" {
		return name + " (" + m.Model.ID + ")"
	}
	return name
}

// openchamberToolSummary returns a short line about the input of a tool.
func openchamberToolSummary(part openchamber.Content) string {
	if len(part.State.Input) == 0 {
		return ""
	}
	var in map[string]any
	if json.Unmarshal(part.State.Input, &in) != nil {
		return ""
	}
	for _, key := range []string{"command", "filePath", "path", "pattern", "query", "description", "url"} {
		if v, ok := in[key].(string); ok && v != "" {
			return clamp(strings.Join(strings.Fields(v), " "), 200)
		}
	}
	return ""
}

// openchamberToolOutput returns the text that a tool produced.
func openchamberToolOutput(part openchamber.Content) string {
	var b strings.Builder
	for _, c := range part.State.Content {
		if c.Type == "text" {
			b.WriteString(c.Text)
		}
	}
	return b.String()
}

// clamp cuts text to at most n bytes at a whole UTF-8 character.
func clamp(s string, n int) string {
	if len(s) <= n {
		return s
	}
	t := s[:n]
	for len(t) > 0 && !utf8Start(t[len(t)-1]) {
		t = t[:len(t)-1]
	}
	return t
}

// utf8Start reports whether b is the first byte of a UTF-8 character.
func utf8Start(b byte) bool { return b&0xc0 != 0x80 }

// openchamberPrompt sends text from a phone to a session and returns the
// sent packet. The log gets the length, not the text.
func (d *Daemon) openchamberPrompt(dev *Device, session, text string) *proto.Packet {
	return d.openchamberReply(dev, session, "prompt", func(ctx context.Context) error {
		text = cleanPrompt(text)
		switch {
		case text == "":
			return openchamberRefusal("The text is empty")
		case len(text) > herdrMaxPrompt:
			return openchamberRefusal(fmt.Sprintf("The text is longer than %d KB", herdrMaxPrompt>>10))
		}
		if err := d.oc.Prompt(ctx, session, text); err != nil {
			return err
		}
		d.logf("%s sent %d characters to the OpenChamber session %s", dev.Name, len([]rune(text)), session)
		return nil
	})
}

// openchamberInterrupt stops the run of a session for a phone.
func (d *Daemon) openchamberInterrupt(dev *Device, session string) *proto.Packet {
	return d.openchamberReply(dev, session, "interrupt", func(ctx context.Context) error {
		if err := d.oc.Interrupt(ctx, session); err != nil {
			return err
		}
		d.logf("%s stopped the OpenChamber session %s", dev.Name, session)
		return nil
	})
}

// openchamberForm answers a question that waits for a session. The answer
// is the object of field values that the app built.
func (d *Daemon) openchamberForm(dev *Device, session, form string, answer json.RawMessage) *proto.Packet {
	return d.openchamberReply(dev, session, "form", func(ctx context.Context) error {
		if form == "" {
			return openchamberRefusal("The question is gone")
		}
		if len(answer) == 0 || !json.Valid(answer) {
			return openchamberRefusal("The answer is empty")
		}
		if err := d.oc.AnswerForm(ctx, session, form, answer); err != nil {
			return err
		}
		d.logf("%s answered a question in the OpenChamber session %s", dev.Name, session)
		return nil
	})
}

// openchamberPermission answers a permission prompt for a session.
func (d *Daemon) openchamberPermission(dev *Device, session, request, decision string) *proto.Packet {
	return d.openchamberReply(dev, session, "permission", func(ctx context.Context) error {
		if request == "" {
			return openchamberRefusal("The permission is gone")
		}
		switch decision {
		case "allow", "deny":
		default:
			return openchamberRefusal(fmt.Sprintf("The decision %q is not allowed", decision))
		}
		if err := d.oc.AnswerPermission(ctx, session, request, decision); err != nil {
			return err
		}
		d.logf("%s answered a permission in the OpenChamber session %s: %s", dev.Name, session, decision)
		return nil
	})
}

// openchamberRefusal is a reply that fluxd refuses before it calls
// OpenChamber. Its text goes to the phone.
type openchamberRefusal string

func (r openchamberRefusal) Error() string { return string(r) }

// openchamberReply runs a reply from a phone after the checks that all
// replies share: the feature is on, control is on, and the session is
// known.
func (d *Daemon) openchamberReply(dev *Device, session, action string, send func(ctx context.Context) error) *proto.Packet {
	reply := map[string]any{"kind": "sent", "session": session, "action": action}
	d.mu.Lock()
	enabled, control := d.cfg.OpenChamber, d.openchamberControlLocked()
	known := d.openchamberAgentLocked(session)
	d.mu.Unlock()
	switch {
	case !enabled:
		reply["error"] = "OpenChamber sync is off on this computer"
	case !control:
		reply["error"] = errOpenChamberControlOff
	case !known:
		reply["error"] = fmt.Sprintf("No session %s runs on this computer", session)
	default:
		ctx, cancel := context.WithTimeout(d.ctx, openchamberCallTimeout)
		err := send(ctx)
		cancel()
		if err != nil {
			reply["error"] = openchamberReplyError(err)
		}
	}
	return proto.New(proto.TypeFluxOpenChamber, reply)
}

// errOpenChamberControlOff is the reply when openchamber_control is off.
const errOpenChamberControlOff = "Replies from the phone are off on this computer. Set openchamber_control = true in ~/.config/flux/config.toml."

// openchamberReplyError returns the text for a phone about a failed reply.
func openchamberReplyError(err error) string {
	var refusal openchamberRefusal
	if errors.As(err, &refusal) {
		return string(refusal)
	}
	return openchamberError(err)
}

// openchamberCreate starts a session for a phone and returns the created
// packet. kind is the agent of the session, and cwd is the folder.
func (d *Daemon) openchamberCreate(dev *Device, kind, cwd, title string) *proto.Packet {
	reply := map[string]any{"kind": "created"}
	d.mu.Lock()
	enabled, control := d.cfg.OpenChamber, d.openchamberControlLocked()
	kindKnown := slices.ContainsFunc(d.ocKinds, func(k OpenChamberKind) bool { return k.ID == kind })
	d.mu.Unlock()
	var err error
	switch {
	case !enabled:
		err = openchamberRefusal("OpenChamber sync is off on this computer")
	case !control:
		err = openchamberRefusal(errOpenChamberControlOff)
	case kind != "" && !kindKnown:
		err = openchamberRefusal(fmt.Sprintf("OpenChamber cannot start the agent %q on this computer", kind))
	}
	var dir string
	if err == nil {
		dir, err = openchamberDir(cwd)
	}
	var session openchamber.Session
	if err == nil {
		ctx, cancel := context.WithTimeout(d.ctx, openchamberCallTimeout)
		session, err = d.oc.Create(ctx, kind, dir, title)
		cancel()
	}
	if err != nil {
		reply["error"] = openchamberReplyError(err)
		return proto.New(proto.TypeFluxOpenChamber, reply)
	}
	if session.ID == "" {
		reply["error"] = "OpenChamber did not report the new session"
		return proto.New(proto.TypeFluxOpenChamber, reply)
	}
	d.logf("%s started the OpenChamber session %s (%s)", dev.Name, session.ID, dir)
	d.waitOpenChamberSession(session.ID)
	reply["session"] = session.ID
	return proto.New(proto.TypeFluxOpenChamber, reply)
}

// waitOpenChamberSession makes the OpenChamber loop read the sessions, and
// waits until the state has the session or openchamberStateWait passes.
func (d *Daemon) waitOpenChamberSession(session string) {
	d.wakeOpenChamber()
	deadline := time.Now().Add(openchamberStateWait)
	for time.Now().Before(deadline) {
		d.mu.Lock()
		known := d.openchamberAgentLocked(session)
		d.mu.Unlock()
		if known {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// openchamberClose stops and files away a session for a phone.
func (d *Daemon) openchamberClose(dev *Device, session string) *proto.Packet {
	reply := map[string]any{"kind": "closed", "session": session}
	d.mu.Lock()
	enabled, control := d.cfg.OpenChamber, d.openchamberControlLocked()
	known := d.openchamberAgentLocked(session)
	dir := d.openchamberAgentDirLocked(session)
	d.mu.Unlock()
	switch {
	case !enabled:
		reply["error"] = "OpenChamber sync is off on this computer"
	case !control:
		reply["error"] = errOpenChamberControlOff
	case !known:
		reply["error"] = fmt.Sprintf("No session %s runs on this computer", session)
	default:
		ctx, cancel := context.WithTimeout(d.ctx, openchamberCallTimeout)
		// Stop the run before it is filed away, so a working agent does
		// not keep writing to an archived session.
		err := d.oc.Interrupt(ctx, session)
		if err == nil {
			err = d.oc.Archive(ctx, dir, []string{session})
		}
		cancel()
		if err != nil {
			reply["error"] = openchamberReplyError(err)
			break
		}
		d.logf("%s closed the OpenChamber session %s", dev.Name, session)
		d.wakeOpenChamber()
	}
	return proto.New(proto.TypeFluxOpenChamber, reply)
}

// openchamberDir returns the absolute folder for a new session. An empty
// folder and ~ are the home folder, and ~/ starts a path in it. The folder
// must exist.
func openchamberDir(dir string) (string, error) {
	dir = strings.TrimSpace(dir)
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	switch {
	case dir == "" || dir == "~":
		dir = home
	case strings.HasPrefix(dir, "~/"):
		dir = filepath.Join(home, dir[2:])
	case !filepath.IsAbs(dir):
		return "", openchamberRefusal(fmt.Sprintf("Give the folder as a full path or with ~/: %s", dir))
	}
	dir = filepath.Clean(dir)
	if fi, err := os.Stat(dir); err != nil || !fi.IsDir() {
		return "", openchamberRefusal(fmt.Sprintf("The folder %s does not exist", dir))
	}
	return dir, nil
}
