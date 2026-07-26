import React from 'react';
import { AppShell, TopNav } from '../design-system';

/**
 * The entry point. Two doors: apply as a customer, or watch the bank's side. Each
 * maps to a top-level view in {@link App}.
 */
export default function LandingScreen({ onChoose }) {
  return (
    <AppShell nav={<TopNav brand="NEO" product="Customer onboarding" />}>
      <div className="landing">
        <header className="landing__head">
          <h1 className="landing__title">Customer onboarding</h1>
          <p className="landing__sub">
            Pick a simulation to explore the credit-card onboarding journey.
          </p>
        </header>

        <div className="landing__choices">
          <button type="button" className="choice" onClick={() => onChoose('journey')}>
            <span className="choice__kicker">For the applicant</span>
            <span className="choice__title">Customer journey simulation</span>
            <span className="choice__sub">
              Apply for a card as a customer would — choose a product, fill in the
              application, and watch it move through the bank in real time.
            </span>
            <span className="choice__cta">Start applying →</span>
          </button>

          <button type="button" className="choice" onClick={() => onChoose('backoffice')}>
            <span className="choice__kicker">For the bank</span>
            <span className="choice__title">Backoffice overview simulation</span>
            <span className="choice__sub">
              See every application flow through the onboarding services from the
              operator's side — the live board and the per-service view.
            </span>
            <span className="choice__cta">Open the console →</span>
          </button>
        </div>
      </div>
    </AppShell>
  );
}
