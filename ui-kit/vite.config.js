import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Nothing but React and Vite. The design system adds no build step, no CSS
// framework and no runtime dependency — if it needed one, it could not be
// vendored into ten repos that must build offline.
export default defineConfig({
  plugins: [react()],
  server: { port: 5180 },
});
