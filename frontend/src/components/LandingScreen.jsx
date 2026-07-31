import React from 'react';
import { AppShell, TopNav } from '../design-system';

/**
 * The entry point. Four doors: apply, sign in, operate the journey, or probe one module.
 * Each maps to a top-level view in {@link App}. Apply and Sign in both land on
 * {@link LoginScreen} — they diverge only in where that screen sends you next (straight
 * to the product picker, versus your existing account).
 */
export default function LandingScreen({ onChoose }) {
  return (
    <AppShell className="landing-shell" nav={<TopNav brand="NEO BANK" product="Platform 00" />}>
      <div className="landing">
        <header className="landing__head">
          <span className="landing__eyebrow">One journey. Four perspectives.</span>
          <h1 className="landing__title">Enter the bank.</h1>
          <p className="landing__sub">
            Follow a credit-card application from first choice to final decision, come
            back to an account you already opened, watch the platform coordinate every
            check behind it, or send one module a scenario of your own.
          </p>
        </header>

        <div className="landing__choices">
          <button type="button" className="choice" onClick={() => onChoose('apply')}>
            <span className="choice__number">01</span>
            <span className="choice__kicker">Customer</span>
            <span className="choice__title">Apply for a card</span>
            <span className="choice__sub">
              Choose a product, apply, and watch your decision move through the bank live.
            </span>
            <span className="choice__cta">Begin journey <span aria-hidden="true">↗</span></span>
          </button>

          <button type="button" className="choice" onClick={() => onChoose('signin')}>
            <span className="choice__number">02</span>
            <span className="choice__kicker">Customer</span>
            <span className="choice__title">Sign in</span>
            <span className="choice__sub">
              Already have a code? See your existing cards and applications.
            </span>
            <span className="choice__cta">Sign in <span aria-hidden="true">↗</span></span>
          </button>

          <button type="button" className="choice" onClick={() => onChoose('backoffice')}>
            <span className="choice__number">03</span>
            <span className="choice__kicker">Operations</span>
            <span className="choice__title">Watch the platform</span>
            <span className="choice__sub">
              See every application, service response, and live decision from the inside.
            </span>
            <span className="choice__cta">Open command centre <span aria-hidden="true">↗</span></span>
          </button>

          <button type="button" className="choice" onClick={() => onChoose('simulator')}>
            <span className="choice__number">04</span>
            <span className="choice__kicker">Instructor tooling</span>
            <span className="choice__title">Probe a module</span>
            <span className="choice__sub">
              Send a known contract scenario to any team and inspect its acknowledgement and report.
            </span>
            <span className="choice__cta">Open simulator <span aria-hidden="true">↗</span></span>
          </button>
        </div>
        <p className="landing__foot">NEO BANK / CUSTOMER ONBOARDING ORCHESTRATOR / LIVE SIMULATION</p>
      </div>
    </AppShell>
  );
}
