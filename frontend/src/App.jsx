import React, { useState } from 'react';
import LandingScreen from './components/LandingScreen.jsx';
import LoginScreen from './components/LoginScreen.jsx';
import CustomerJourney from './components/CustomerJourney.jsx';
import BackofficeScreen from './components/BackofficeScreen.jsx';
import SimulatorScreen from './components/SimulatorScreen.jsx';

/**
 * The shell. A landing page offers three views; each is its own screen. We keep
 * the codebase's no-router convention — a single view-state switch — so only the
 * active screen mounts (and only it polls the API).
 *
 *   'landing'    → the three choices
 *   'login'      → the customer's four-character code
 *   'journey'    → Customer Journey Simulation (their account → product → form → live status)
 *   'backoffice' → Backoffice Overview Simulation (the two operator screens)
 *   'simulator'  → one-shot scenario dispatches to any configured module
 *
 * Each screen renders its own AppShell, because the bar differs: the customer sees a
 * progress tracker and their own code, the operator sees health and the generator controls.
 *
 * <p><b>Only the customer side asks who you are.</b> The backoffice is the bank's own view and a
 * customer code means nothing there. Signing in is held here rather than inside the journey so
 * that CustomerJourney only ever mounts with a real code and never has to handle its absence.</p>
 *
 * <p>Nothing is persisted: closing the tab signs you out. The code IS the identity, so signing in
 * again brings everything back — which is why losing it costs nothing.</p>
 */
export default function App() {
  const [view, setView] = useState('landing');
  const [customer, setCustomer] = useState(null);
  const goHome = () => setView('landing');

  return (
    <>
      {view === 'landing' && (
        <LandingScreen onChoose={(next) => setView(next === 'journey' ? 'login' : next)} />
      )}

      {view === 'login' && (
        <LoginScreen
          onSignedIn={(signedIn) => {
            setCustomer(signedIn);
            setView('journey');
          }}
          onHome={goHome}
        />
      )}

      {view === 'journey' && customer && (
        <CustomerJourney
          customerId={customer.customerId}
          initialItems={customer.items}
          onLogout={() => {
            setCustomer(null);
            setView('landing');
          }}
        />
      )}

      {view === 'backoffice' && <BackofficeScreen onHome={goHome} />}
      {view === 'simulator' && <SimulatorScreen onHome={goHome} />}
    </>
  );
}
