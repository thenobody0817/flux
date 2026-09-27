// Vendored from OmarchyRemote (https://github.com/omarchy/omarchy-remote)
// android/app/src/main/assets/android-bridge.js. Copyright 2026 Justin Sanders,
// used under the MIT License:
//
//   Permission is hereby granted, free of charge, to any person obtaining a
//   copy of this software and associated documentation files (the "Software"),
//   to deal in the Software without restriction, including without limitation
//   the rights to use, copy, modify, merge, publish, distribute, sublicense,
//   and/or sell copies of the Software, and to permit persons to whom the
//   Software is furnished to do so, subject to the following conditions:
//
//   The above copyright notice and this permission notice shall be included in
//   all copies or substantial portions of the Software.
//
//   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
//   FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
//   DEALINGS IN THE SOFTWARE.
//
// The web shell contract: this file is injected before every document, so keep it
// self-contained and free of imports.

(() => {
  if (window.top !== window) return;
  const pending = new Map();
  let serial = 0;
  AndroidShell.onmessage = event => {
    const reply = JSON.parse(event.data);
    const callback = pending.get(reply.id);
    if (!callback) return;
    pending.delete(reply.id);
    if (reply.error) callback.reject(new Error(reply.error));
    else if (reply.value?.error) callback.reject(new Error(reply.value.error));
    else callback.resolve(reply.value);
  };
  const send = (channel, body) =>
    new Promise((resolve, reject) => {
      const id = ++serial;
      pending.set(id, { resolve, reject });
      AndroidShell.postMessage(JSON.stringify({ id, channel, body }));
    });
  window.webkit = { messageHandlers: {} };
  for (const channel of [
    'shellHosts',
    'shellInstallBuild',
    'shellStorage',
    'shellKeyboard',
    'weatherDevice',
    'browserDevice',
  ]) {
    window.webkit.messageHandlers[channel] = { postMessage: body => send(channel, body) };
  }
  const canSave = data => data?.files?.length === 1 && data.files[0] instanceof File;
  Object.defineProperty(navigator, 'canShare', { value: canSave, configurable: true });
  Object.defineProperty(navigator, 'share', {
    configurable: true,
    value: async data => {
      if (!canSave(data)) throw new TypeError('Choose one file to save.');
      const file = data.files[0];
      const { token } = await send('shellFiles', {
        action: 'begin',
        name: file.name,
        type: file.type,
        size: file.size,
      });
      try {
        for (let offset = 0; offset < file.size; offset += 196608) {
          const chunk = file.slice(offset, offset + 196608);
          const encoded = await new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result.split(',')[1]);
            reader.onerror = () => reject(reader.error);
            reader.readAsDataURL(chunk);
          });
          await send('shellFiles', { action: 'append', token, offset, data: encoded });
        }
        const result = await send('shellFiles', { action: 'save', token });
        if (result.cancelled) throw new DOMException('Save cancelled', 'AbortError');
      } catch (error) {
        await send('shellFiles', { action: 'cancel', token }).catch(() => {});
        throw error;
      }
    },
  });
  window.addEventListener('hyprland-keyboard-dismiss', () =>
    send('shellKeyboard', { dismiss: true })
  );
  const reportEditing = () => {
    const el = document.activeElement;
    send(
      'shellKeyboard',
      !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName))
    );
  };
  document.addEventListener('focusin', reportEditing, true);
  document.addEventListener('focusout', () => queueMicrotask(reportEditing), true);
  document.addEventListener('DOMContentLoaded', reportEditing);
  window.__OMARCHY_PLATFORM__ = 'android';
  window.__HYPRLAND_NATIVE__ = true;
  window.__HYPRLAND_NATIVE_FOCUS__ = true;
  document.addEventListener('DOMContentLoaded', () => {
    document.documentElement.classList.add('native-shell', 'android-shell');
    const style = document.createElement('style');
    style.textContent =
      '.android-shell .prototype-layout{padding:0!important}.android-shell #touch-shell>div:first-child{padding-top:8px!important}';
    document.head.append(style);
  });
})();
