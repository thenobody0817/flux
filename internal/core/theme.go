package core

import (
	"context"
	"time"

	"flux/internal/desktop"
	"flux/internal/lan"
	"flux/internal/proto"
)

// themePoll is how often fluxd reads the active Omarchy theme. A theme
// switch replaces the colors.toml, and a read is cheap.
const themePoll = 2 * time.Second

// themeLoop watches the active Omarchy theme and sends each change to the
// paired phones.
func (d *Daemon) themeLoop(ctx context.Context) {
	t := time.NewTicker(themePoll)
	defer t.Stop()
	var lastSlug, lastText string
	first := true
	for {
		slug, text, ok := d.theme.Get()
		if ok && (first || slug != lastSlug || text != lastText) {
			name, _ := d.theme.Current()
			if name == "" {
				name = slug
			}
			if first {
				d.logf("Omarchy theme is %q", name)
			} else {
				d.logf("Omarchy theme changed to %q", name)
			}
			lastSlug, lastText, first = slug, text, false
			d.sendTheme("")
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// themePacket builds the flux.theme body from the active theme. ok is false
// when the theme or the file is missing.
func (d *Daemon) themePacket() (*proto.Packet, bool) {
	if d.theme == nil {
		return nil, false
	}
	slug, text, ok := d.theme.Get()
	if !ok {
		return nil, false
	}
	name, ok := d.theme.Current()
	if !ok || name == "" {
		name = slug
	}
	themes, _ := d.theme.List()
	return proto.New(proto.TypeFluxTheme, map[string]any{
		"name":   name,
		"slug":   slug,
		"mode":   desktop.Mode(text),
		"colors": text,
		"themes": themes,
	}), true
}

// sendTheme sends the active theme to each paired phone that accepts
// flux.theme, except the phone with the ID except.
func (d *Daemon) sendTheme(except string) {
	if _, ok := d.themePacket(); !ok {
		return
	}
	d.mu.Lock()
	var links []*lan.Link
	for _, dev := range d.devices {
		if dev.Paired && dev.link != nil && dev.ID != except && dev.accepts(proto.TypeFluxTheme) {
			links = append(links, dev.link)
		}
	}
	d.mu.Unlock()
	for _, l := range links {
		if p, ok := d.themePacket(); ok {
			_ = l.Send(p)
		}
	}
}

// sendThemeTo sends the active theme to one phone, for example the one that
// just connected or asked for the list.
func (d *Daemon) sendThemeTo(id string) {
	p, ok := d.themePacket()
	if !ok {
		return
	}
	d.mu.Lock()
	var l *lan.Link
	if dev, ok := d.devices[id]; ok && dev.Paired {
		l = dev.link
	}
	d.mu.Unlock()
	if l != nil {
		_ = l.Send(p)
	}
}

// handleThemeRequest lists the themes or applies one on behalf of a phone.
func (d *Daemon) handleThemeRequest(dev *Device, p *proto.Packet) {
	if d.theme == nil {
		return
	}
	var body struct {
		Action string `json:"action"`
		Set    string `json:"set"`
	}
	if p.Decode(&body) != nil {
		return
	}
	if body.Set != "" {
		if err := d.theme.Set(body.Set); err != nil {
			d.logf("%s: set theme %q: %v", dev.Name, body.Set, err)
			return
		}
		d.logf("%s set the theme to %q", dev.Name, body.Set)
		// The theme loop notices the new files and sends the new state.
		return
	}
	if body.Action == "list" {
		d.sendThemeTo(dev.ID)
	}
}
