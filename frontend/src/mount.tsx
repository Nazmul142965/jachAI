import { StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import Practice from './Practice';
import type { Host } from './api';

// The only thing this module exports. app.js imports it on demand and hands over
// a container plus a request function; the pilot never sees the access token.
let root: Root | null = null;

export function mount(container: HTMLElement, host: Host): void {
  if (!root) root = createRoot(container);
  root.render(
    <StrictMode>
      <Practice {...host} />
    </StrictMode>,
  );
}

export function unmount(): void {
  root?.unmount();
  root = null;
}
