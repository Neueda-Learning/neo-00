import React, { useState } from 'react';
import LandingScreen from './components/LandingScreen.jsx';
import CustomerJourney from './components/CustomerJourney.jsx';
import BackofficeScreen from './components/BackofficeScreen.jsx';

/**
 * The shell. A landing page offers two simulations; each is its own screen. We keep
 * the codebase's no-router convention — a single view-state switch — so only the
 * active screen mounts (and only it polls the API).
 *
 *   'landing'    → the two choices
 *   'journey'    → Customer Journey Simulation (product → form → live status)
 *   'backoffice' → Backoffice Overview Simulation (the two operator screens)
 *
 * Each screen renders its own AppShell, because the bar differs: the customer sees a
 * progress tracker, the operator sees health and the generator controls.
 */
export default function App() {
  const [view, setView] = useState('landing');
  const goHome = () => setView('landing');

  return (
    <>
      {view === 'landing' && <LandingScreen onChoose={setView} />}
      {view === 'journey' && <CustomerJourney onHome={goHome} />}
      {view === 'backoffice' && <BackofficeScreen onHome={goHome} />}
    </>
  );
}
