import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';

// The one import that installs the design system. Nothing else is needed — no
// provider, no theme object, no context. See design-system/DESIGN.md § "Install".
import './design-system/styles.css';

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
