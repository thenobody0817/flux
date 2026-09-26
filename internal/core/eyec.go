package core

// fluxd carries eyec permission prompts between the eyec daemon on this
// computer and the phone. The eyec daemon asks, the phone answers with
// allow, deny, or yolo, and the daemon waits for the decision.
// docs/eyec.md describes the flow.

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"flux/internal/proto"
)

// The wait for the phone, in seconds.
const eyecTimeoutSeconds = 120

// eyecWaitSlice is the longest time 1 eyec.permit.wait call blocks. A longer
// wait returns the state "pending", and the client calls again.
var eyecWaitSlice = 50 * time.Second

// eyecResult is the answer of the phone, as eyec.permit.wait returns it.
type eyecResult struct {
	State string `json:"state"`
}

type eyecRequest struct {
	id       string
	device   string
	deadline time.Time
	done     chan struct{}
	result   *eyecResult
}

// eyecBook holds the permit requests that wait for a phone.
type eyecBook struct {
	mu   sync.Mutex
	byID map[string]*eyecRequest
}

// add stores a new request. A phone has at most 1 request at a time.
func (b *eyecBook) add(a *eyecRequest) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.byID == nil {
		b.byID = map[string]*eyecRequest{}
	}
	// A request whose caller stopped before eyec.permit.wait stays until here.
	for id, other := range b.byID {
		if time.Since(other.deadline) > time.Minute {
			delete(b.byID, id)
		}
	}
	for _, other := range b.byID {
		if other.device == a.device && other.result == nil && time.Now().Before(other.deadline) {
			return apiErr("busy", "Another request waits for this phone")
		}
	}
	a.done = make(chan struct{})
	b.byID[a.id] = a
	return nil
}

// deliver stores the answer of a phone. It accepts only an answer from the
// phone that got the request, with a known decision, and only once.
func (b *eyecBook) deliver(device, id, decision string) bool {
	if decision != "allow" && decision != "deny" && decision != "yolo" {
		return false
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	a := b.byID[id]
	if a == nil || a.device != device || a.result != nil {
		return false
	}
	a.result = &eyecResult{State: decision}
	close(a.done)
	return true
}

// wait blocks until the phone answers, the request expires, or slice ends.
// An expired request returns expired true, and the caller cancels it on the
// phone.
func (b *eyecBook) wait(ctx context.Context, id string, slice time.Duration) (res eyecResult, device string, expired bool, err error) {
	b.mu.Lock()
	a := b.byID[id]
	b.mu.Unlock()
	if a == nil {
		return res, "", false, apiErr("not_found", "No request with ID %s", id)
	}
	limit := time.Until(a.deadline)
	pending := limit > slice
	if pending {
		limit = slice
	}
	t := time.NewTimer(max(limit, 0))
	defer t.Stop()
	select {
	case <-a.done:
		b.remove(id)
		return *a.result, a.device, false, nil
	case <-t.C:
		if pending {
			return eyecResult{State: "pending"}, a.device, false, nil
		}
		b.remove(id)
		return res, a.device, true, apiErr("timeout", "The phone did not answer in time")
	case <-ctx.Done():
		return res, a.device, false, ctx.Err()
	}
}

func (b *eyecBook) remove(id string) (device string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if a := b.byID[id]; a != nil {
		device = a.device
		delete(b.byID, id)
	}
	return device
}

type eyecPermitParams struct {
	Device  string `json:"device"`
	Title   string `json:"title"`
	Pattern string `json:"pattern"`
	Service string `json:"service"`
}

// eyecDevice returns a paired and connected phone that answers eyec prompts.
func (d *Daemon) eyecDevice(dev *Device) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if !dev.Paired {
		return apiErr("not_paired", "%s is not paired", dev.Name)
	}
	if dev.link == nil {
		return offline(dev)
	}
	if !dev.accepts(proto.TypeFluxEyec) {
		return apiErr("unsupported", "Update Flux for Android on %s to answer eyec prompts", dev.Name)
	}
	return nil
}

// EyecPermit sends a permission prompt to the phone.
func (d *Daemon) EyecPermit(raw json.RawMessage) (any, error) {
	var p eyecPermitParams
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, apiErr("bad_params", "params: %v", err)
	}
	dev, err := d.pick(p.Device)
	if err != nil {
		return nil, err
	}
	if err := d.eyecDevice(dev); err != nil {
		return nil, err
	}
	a := &eyecRequest{id: newApprovalID(), device: dev.ID, deadline: time.Now().Add(eyecTimeoutSeconds * time.Second)}
	if err := d.eyec.add(a); err != nil {
		return nil, err
	}
	body := map[string]any{
		"kind": "permit", "id": a.id, "title": p.Title, "pattern": p.Pattern,
		"service": p.Service, "timeout": eyecTimeoutSeconds,
	}
	if err := d.send(dev, proto.New(proto.TypeFluxEyec, body)); err != nil {
		d.eyec.remove(a.id)
		return nil, err
	}
	d.logf("eyec: permission %q asks %s", p.Title, dev.Name)
	return map[string]any{"id": a.id, "timeout": eyecTimeoutSeconds, "name": dev.Name}, nil
}

// EyecWait waits for the answer of the phone. An expired request is
// cancelled on the phone.
func (d *Daemon) EyecWait(ctx context.Context, id string) (any, error) {
	res, device, expired, err := d.eyec.wait(ctx, id, eyecWaitSlice)
	if expired {
		d.cancelEyec(device, id)
	}
	if err != nil {
		return nil, err
	}
	return res, nil
}

// EyecCancel ends a request and closes it on the phone.
func (d *Daemon) EyecCancel(id string) error {
	device := d.eyec.remove(id)
	if device == "" {
		return apiErr("not_found", "No request with ID %s", id)
	}
	d.cancelEyec(device, id)
	return nil
}

func (d *Daemon) cancelEyec(device, id string) {
	d.mu.Lock()
	dev := d.devices[device]
	d.mu.Unlock()
	if dev != nil {
		_ = d.send(dev, proto.New(proto.TypeFluxEyec, map[string]any{"kind": "cancel", "id": id}))
	}
}

// handleEyec routes a flux.eyec packet from a phone. The kinds "ask" and
// "trigger" are requests from the phone; "permit" is its answer.
func (d *Daemon) handleEyec(dev *Device, p *proto.Packet) {
	var b struct {
		Kind     string `json:"kind"`
		ID       string `json:"id"`
		Decision string `json:"decision"`
		Prompt   string `json:"prompt"`
		Agent    string `json:"agent"`
		Action   string `json:"action"`
	}
	if p.Decode(&b) != nil || b.ID == "" {
		return
	}
	switch b.Kind {
	case "permit":
		if !d.eyec.deliver(dev.ID, b.ID, b.Decision) {
			d.logf("eyec: %s answered a request that does not wait", dev.Name)
		}
	case "ask":
		// A model call can take minutes, so it must not block the link.
		go d.replyEyecAsk(dev, b.ID, b.Prompt, b.Agent)
	case "peek":
		go d.replyEyecPeek(dev, b.ID, b.Prompt)
	case "trigger":
		go d.replyEyecTrigger(dev, b.ID, b.Action)
	}
}

// eyecAsk runs "ask" on the eyec daemon and returns the answer and choices.
func eyecAsk(ctx context.Context, prompt, agent string) (string, []string, error) {
	if strings.TrimSpace(prompt) == "" {
		return "", nil, apiErr("bad_params", "the prompt is empty")
	}
	conn, err := net.DialTimeout("unix", eyecSocketPath(), 2*time.Second)
	if err != nil {
		return "", nil, apiErr("eyec_unavailable", "eyec is not running")
	}
	defer conn.Close()
	req := map[string]any{"cmd": "ask", "prompt": prompt}
	if agent != "" {
		req["agent"] = agent
	}
	b, _ := json.Marshal(req)
	if _, err := conn.Write(append(b, '\n')); err != nil {
		return "", nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	reader := bufio.NewReader(conn)
	var text strings.Builder
	for {
		line, err := reader.ReadBytes('\n')
		if len(line) > 0 {
			var ev struct {
				Event   string   `json:"event"`
				Data    string   `json:"data"`
				Answer  string   `json:"answer"`
				Choices []string `json:"choices"`
				Message string   `json:"message"`
			}
			if json.Unmarshal(line, &ev) == nil {
				switch ev.Event {
				case "text":
					text.WriteString(ev.Data)
				case "done":
					answer := ev.Answer
					if answer == "" {
						answer = text.String()
					}
					return strings.TrimSpace(answer), ev.Choices, nil
				case "error":
					return "", nil, apiErr("eyec_error", "%s", ev.Message)
				}
			}
		}
		if err != nil {
			if ctx.Err() != nil {
				return "", nil, apiErr("timeout", "eyec did not answer in time")
			}
			return "", nil, err
		}
	}
}

func (d *Daemon) replyEyecAsk(dev *Device, id, prompt, agent string) {
	ctx, cancel := context.WithTimeout(d.ctx, 300*time.Second)
	defer cancel()
	body := map[string]any{"kind": "answer", "id": id}
	answer, choices, err := eyecAsk(ctx, prompt, agent)
	if err != nil {
		body["error"] = err.Error()
		d.logf("eyec: ask for %s failed: %v", dev.Name, err)
	} else {
		body["text"] = answer
		body["choices"] = choices
	}
	if err := d.send(dev, proto.New(proto.TypeFluxEyec, body)); err != nil {
		d.logf("eyec: answer to %s: %v", dev.Name, err)
	}
}

// eyecPeek captures the whole screen on the eyec daemon and returns the
// answer, the OCR text, and the JPEG path.
func eyecPeek(ctx context.Context, prompt string) (answer, ocr, image string, err error) {
	conn, err := net.DialTimeout("unix", eyecSocketPath(), 2*time.Second)
	if err != nil {
		return "", "", "", apiErr("eyec_unavailable", "eyec is not running")
	}
	defer conn.Close()
	req := map[string]any{"cmd": "peek", "full": true}
	if strings.TrimSpace(prompt) != "" {
		req["prompt"] = prompt
	}
	b, _ := json.Marshal(req)
	if _, err := conn.Write(append(b, '\n')); err != nil {
		return "", "", "", err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	reader := bufio.NewReader(conn)
	for {
		line, rerr := reader.ReadBytes('\n')
		if len(line) > 0 {
			var ev struct {
				Event   string `json:"event"`
				Answer  string `json:"answer"`
				Ocr     string `json:"ocr"`
				Image   string `json:"image"`
				Message string `json:"message"`
			}
			if json.Unmarshal(line, &ev) == nil {
				switch ev.Event {
				case "done":
					return ev.Answer, ev.Ocr, ev.Image, nil
				case "error":
					return "", "", "", apiErr("eyec_error", "%s", ev.Message)
				}
			}
		}
		if rerr != nil {
			if ctx.Err() != nil {
				return "", "", "", apiErr("timeout", "eyec did not finish the peek in time")
			}
			return "", "", "", rerr
		}
	}
}

// maxEyecImage is the largest screenshot that a packet carries inline.
const maxEyecImage = 4 << 20

func (d *Daemon) replyEyecPeek(dev *Device, id, prompt string) {
	ctx, cancel := context.WithTimeout(d.ctx, 300*time.Second)
	defer cancel()
	body := map[string]any{"kind": "answer", "id": id}
	answer, ocr, image, err := eyecPeek(ctx, prompt)
	if err != nil {
		body["error"] = err.Error()
		d.logf("eyec: peek for %s failed: %v", dev.Name, err)
	} else {
		body["text"] = answer
		body["ocr"] = ocr
		if data, rerr := os.ReadFile(image); rerr == nil && len(data) <= maxEyecImage {
			body["image"] = base64.StdEncoding.EncodeToString(data)
			body["mime"] = "image/jpeg"
		}
	}
	if err := d.send(dev, proto.New(proto.TypeFluxEyec, body)); err != nil {
		d.logf("eyec: peek answer to %s: %v", dev.Name, err)
	}
}

// EyecAction is one action that a phone can ask eyec to run. The list is
// fixed, so a paired phone cannot run arbitrary commands.
type EyecAction struct {
	ID    string   `json:"id"`
	Label string   `json:"label"`
	Args  []string `json:"-"`
}

var eyecActions = []EyecAction{
	{"status", "Status", []string{"status"}},
	{"last", "Last answer", []string{"last"}},
	{"dock.toggle", "Toggle dock", []string{"ui", "toggle"}},
	{"dock.show", "Show dock", []string{"ui", "show"}},
	{"dock.hide", "Hide dock", []string{"ui", "hide"}},
	{"shutter.on", "Shutter on", []string{"privacy", "--shutter"}},
	{"shutter.off", "Shutter off", []string{"privacy", "--no-shutter"}},
	{"redact.on", "Redact on", []string{"privacy", "--redact"}},
	{"redact.off", "Redact off", []string{"privacy", "--no-redact"}},
	{"yolo.on", "YOLO on", []string{"yolo", "on"}},
	{"yolo.off", "YOLO off", []string{"yolo", "off"}},
}

func eyecActionByID(id string) (EyecAction, bool) {
	for _, a := range eyecActions {
		if a.ID == id {
			return a, true
		}
	}
	return EyecAction{}, false
}

// EyecActions lists the actions that a phone may run.
func (d *Daemon) EyecActions() any {
	out := make([]map[string]string, 0, len(eyecActions))
	for _, a := range eyecActions {
		out = append(out, map[string]string{"id": a.ID, "label": a.Label})
	}
	return map[string]any{"actions": out}
}

// runEyecAction runs one allowlisted eyec command and returns its output.
func runEyecAction(ctx context.Context, id string) (bool, string) {
	a, ok := eyecActionByID(id)
	if !ok {
		return false, "unknown action"
	}
	// Reuse the eyec CLI, which owns each toggle, instead of writing the
	// config that the daemon and the dock already read.
	cctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, "eyec", a.Args...).CombinedOutput()
	detail := strings.TrimSpace(string(out))
	if len(detail) > 500 {
		detail = detail[:500]
	}
	if err != nil {
		if detail == "" {
			detail = err.Error()
		}
		return false, detail
	}
	return true, detail
}

// EyecTrigger runs a trigger action for the CLI or the IPC server.
func (d *Daemon) EyecTrigger(raw json.RawMessage) (any, error) {
	var p struct {
		Device string `json:"device"`
		Action string `json:"action"`
	}
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, apiErr("bad_params", "params: %v", err)
	}
	dev, err := d.pick(p.Device)
	if err != nil {
		return nil, err
	}
	if err := d.eyecDevice(dev); err != nil {
		return nil, err
	}
	ok, detail := runEyecAction(d.ctx, p.Action)
	return map[string]any{"ok": ok, "detail": detail}, nil
}

func (d *Daemon) replyEyecTrigger(dev *Device, id, action string) {
	ok, detail := runEyecAction(d.ctx, action)
	body := map[string]any{"kind": "trigger", "id": id, "ok": ok, "detail": detail}
	if err := d.send(dev, proto.New(proto.TypeFluxEyec, body)); err != nil {
		d.logf("eyec: trigger result to %s: %v", dev.Name, err)
	}
}

func eyecSocketPath() string {
	if p := os.Getenv("EYEC_SOCKET"); p != "" {
		return p
	}
	runtime := os.Getenv("XDG_RUNTIME_DIR")
	if runtime == "" {
		runtime = fmt.Sprintf("/run/user/%d", os.Getuid())
	}
	return filepath.Join(runtime, "eyec", "eyec.sock")
}
