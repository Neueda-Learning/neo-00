import React, { useState } from 'react';
import LandingScreen from './components/LandingScreen.jsx';
import LoginScreen from './components/LoginScreen.jsx';
import CustomerJourney from './components/CustomerJourney.jsx';
import BackofficeScreen from './components/BackofficeScreen.jsx';
import SimulatorScreen from './components/SimulatorScreen.jsx';

/**
 * The shell. A landing page offers four doors; each is its own screen. We keep
 * the codebase's no-router convention — a single view-state switch — so only the
 * active screen mounts (and only it polls the API).
 *
 *   'landing'    → the four choices
 *   'login'      → the customer's four-character code (shared by 'apply' and 'signin')
 *   'journey'    → Customer Journey Simulation (their account → product → form → live status)
 *   'backoffice' → Backoffice Overview Simulation (the two operator screens)
 *   'simulator'  → one-shot scenario dispatches to any configured module
 *
 * Each screen renders its own AppShell, because the bar differs: the customer sees a
 * progress tracker and their own code, the operator sees health and the generator controls.
 * Every bar also carries the same five-destination nav (see {@link ../nav.js}) so a visitor
 * is never stuck on one screen — landing's own choice cards are that nav for the landing
 * page itself, so it does not repeat it.
 *
 * <p><b>Only the customer side asks who you are.</b> The backoffice is the bank's own view and a
 * customer code means nothing there. Signing in is held here rather than inside the journey so
 * that CustomerJourney only ever mounts with a real code and never has to handle its absence.</p>
 *
 * <p><b>Apply vs Sign in.</b> Both are the same login screen — the difference is what happens
 * after it. {@code loginIntent} carries that choice through the login step and becomes
 * CustomerJourney's {@code startStep}: 'apply' skips straight to the product picker, 'signin'
 * lands on the existing-products home. It also keys CustomerJourney so switching intent while
 * already signed in (e.g. via the nav bar) remounts onto the right step rather than leaving the
 * old journey's step state behind.</p>
 *
 * <p>Nothing is persisted: closing the tab signs you out. The code IS the identity, so signing in
 * again brings everything back — which is why losing it costs nothing.</p>
 */
export default function App() {
  const [view, setView] = useState('landing');
  const [loginIntent, setLoginIntent] = useState(null); // 'apply' | 'signin'
  const [customer, setCustomer] = useState(null);

  function navigateTo(id) {
    if (id === 'apply' || id === 'signin') {
      setLoginIntent(id);
      // Already signed in: don't make someone re-type a code they just gave us.
      setView(customer ? 'journey' : 'login');
      return;
    }
    setView(id); // 'landing' | 'backoffice' | 'simulator'
  }

  return (
    <>
      {view === 'landing' && <LandingScreen onChoose={navigateTo} />}

      {view === 'login' && (
        <LoginScreen
          current={loginIntent}
          onNavigate={navigateTo}
          onSignedIn={(signedIn) => {
            setCustomer(signedIn);
            setView('journey');
          }}
        />
      )}

      {view === 'journey' && customer && (
        <CustomerJourney
          key={loginIntent}
          customerId={customer.customerId}
          initialItems={customer.items}
          startStep={loginIntent === 'apply' ? 'product' : 'home'}
          onNavigate={navigateTo}
          onLogout={() => {
            setCustomer(null);
            setLoginIntent(null);
            setView('landing');
          }}
        />
      )}

      {view === 'backoffice' && <BackofficeScreen onNavigate={navigateTo} />}
      {view === 'simulator' && (
        <SimulatorScreen current="simulator" onNavigate={navigateTo} />
      )}
    </>
  );
}
