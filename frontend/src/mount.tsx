import { StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import Practice from './Practice';
import type { Host } from './api';

// The only thing this module exports. app.js imports it on demand and hands over
// a container plus a request function; the pilot never sees the access token.
//
// The root is kept against the container it was created for. Leaving the screen
// and coming back used to render into a root whose container had been emptied in
// the meantime, which left Practice blank with no error anywhere.
let root: Root | null = null;
let mountedIn: HTMLElement | null = null;

export function mount(container: HTMLElement, host: Host): void {
  if (root && (mountedIn !== container || !container.contains(mountedIn?.firstChild ?? null))) {
    root.unmount();
    root = null;
  }
  if (!root) {
    root = createRoot(container);
    mountedIn = container;
  }
  root.render(
    <StrictMode>
      <Practice {...host} />
    </StrictMode>,
  );
}

export function unmount(): void {
  root?.unmount();
  root = null;
  mountedIn = null;
}
