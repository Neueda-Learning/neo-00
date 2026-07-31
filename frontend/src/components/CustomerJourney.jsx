import React, { useEffect, useState } from 'react';
import {
  AppShell,
  Button,
  PageHeader,
  TopNav,
} from '../design-system';
import { api } from '../api.js';
import { DESTINATIONS } from '../nav.js';
import { PRODUCTS, productByCode, withLiveCatalogue } from '../products.js';
import { money } from '../status.js';
import ApplicationForm from './ApplicationForm.jsx';
import CustomerHome from './CustomerHome.jsx';
import JourneyStatus from './JourneyStatus.jsx';

const STEPS = [
  { key: 'product', label: 'Choose a card' },
  { key: 'form', label: 'Your details' },
  { key: 'status', label: 'Decision' },
];

const STUDENT_PREVIEW = {
  code: 'CREDIT_CARD_STUDENT',
  name: 'Student Card',
  tagline: 'Your first Neo card is on the way',
  accent: 'student',
  comingSoon: true,
  features: [
    'Designed around student life',
    'Simple mobile spending controls',
    'Full product details coming soon',
  ],
};

/**
 * Everything a signed-in customer sees: what they already have, and the flow to get more —
 * pick a product, fill the application, then watch the live decision come back.
 *
 * This is the one customer-facing surface in the project. It uses the same design
 * system as the operator screens; what changes is the voice, not the components.
 *
 * <p>The shell lives here rather than higher up because the customer's code and the Logout
 * button belong on every one of these screens, and this is the component that owns the bar.</p>
 *
 * <p>{@code startStep} is how the landing page's two customer doors diverge after the same
 * login screen: "Apply for a card" passes {@code 'product'} (skip straight to the picker),
 * "Sign in" passes {@code 'home'} (see what you already have). Omit it and the old
 * has-something-vs-new-customer default applies.</p>
 */
export default function CustomerJourney({
  customerId,
  initialItems = [],
  startStep,
  onNavigate,
  onLogout,
}) {
  // Somebody who already has something lands on it; somebody new goes straight to the cards,
  // because an empty list is not worth a screen of its own. An explicit startStep (Apply vs
  // Sign in) always wins over that guess.
  const [step, setStep] = useState(startStep ?? (initialItems.length ? 'home' : 'product'));
  const [items, setItems] = useState(initialItems);
  const [product, setProduct] = useState(null);
  const [applicationId, setApplicationId] = useState(null);
  // Whose call it is that something is a card and not an application: the orchestrator's.
  const [kind, setKind] = useState(null);

  /** Re-read what this customer has. Cheap: one database read, no module calls. */
  async function refresh() {
    try {
      const view = await api.customer(customerId);
      setItems(view.items);
      return view.items;
    } catch {
      return items;   // a failed refresh must not empty a screen that was already right
    }
  }

  // initialItems is a snapshot from the moment of sign-in. Apply straight through to a
  // submission, then use the nav bar to jump to Sign in without leaving this component's own
  // remount cycle behind, and that snapshot would be all Sign in ever showed. One background
  // refresh on mount keeps it honest without turning every screen into a poller.
  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function backToHome() {
    const fresh = await refresh();
    setProduct(null);
    setApplicationId(null);
    setKind(null);
    setStep(fresh.length ? 'home' : 'product');
  }

  // Apply and Sign in both point back into THIS component once you're already signed in, so a
  // click on either has to move `step` directly rather than round-trip through the parent's
  // loginIntent: that value often doesn't change (you can reach the account screen with intent
  // still 'apply', e.g. after finishing an application), and a no-op state update means no
  // remount and no effect at all. `active` mirrors it: it reflects what's on screen, not which
  // door you came in — the two only look the same right after signing in.
  const active = step === 'home' ? 'signin' : 'apply';
  function handleNavigate(id) {
    if (id === 'apply') { setStep('product'); return; }
    if (id === 'signin') { backToHome(); return; }
    onNavigate(id);
  }

  return (
    <AppShell
      className="customer-shell"
      nav={
        <TopNav
          brand="NEO BANK"
          product="Your account"
          // The cross-page tabs and the apply rail both answer "where am I", and the bar is
          // already carrying the customer code and Log out. On 'form' and 'status' there's
          // real in-progress work (and both already have their own way back — Back, or Apply
          // for another / Your account) so the tabs step aside rather than clip mid-word.
          // 'product' keeps them: a brand-new customer with nothing yet has NO other way off
          // that screen (its Back button only exists once you own something), so trading a
          // slightly busier bar there for a real dead end is the wrong side of that trade.
          tabs={step === 'form' || step === 'status' ? [] : DESTINATIONS}
          active={active}
          onSelect={handleNavigate}
          actions={
            <>
              {/* The apply rail means nothing over a list of things you already own. */}
              {step !== 'home' && <Progress step={step} />}
              <Tagged code={customerId} />
              <Button variant="ghost" size="sm" onClick={onLogout}>
                Log out
              </Button>
            </>
          }
        />
      }
    >
      {step === 'home' && (
        <CustomerHome
          customerId={customerId}
          items={items}
          onApply={() => setStep('product')}
          onOpen={(item) => {
            setApplicationId(item.applicationId);
            setKind(item.kind);
            // An item opened from home has a product CODE, not the marketing object the picker
            // hands over. Look it up, so the detail screen reads the same either way.
            setProduct(productByCode(item.productCode) ?? null);
            setStep('status');
          }}
        />
      )}

      {step === 'product' && (
        <ProductStep
          onChoose={(p) => {
            setProduct(p);
            setStep('form');
          }}
          onBack={items.length ? () => setStep('home') : undefined}
        />
      )}

      {step === 'form' && (
        <ApplicationForm
          product={product}
          customerId={customerId}
          onBack={() => setStep('product')}
          onSubmitted={(id) => {
            setApplicationId(id);
            setKind(null);
            setStep('status');
          }}
        />
      )}

      {step === 'status' && (
        <JourneyStatus
          applicationId={applicationId}
          product={product}
          kind={kind}
          onRestart={() => {
            setProduct(null);
            setApplicationId(null);
            setKind(null);
            setStep('product');
          }}
          onHome={backToHome}
        />
      )}
    </AppShell>
  );
}

/** The signed-in code, in the bar. */
function Tagged({ code }) {
  return <span className="customer-code" title="your customer code">{code}</span>;
}

/** "Choose a card › Your details › Decision" — where the customer is, in the bar. */
function Progress({ step }) {
  const current = STEPS.findIndex((s) => s.key === step);
  return (
    <ol className="journey-progress">
      {STEPS.map((s, i) => (
        <li
          key={s.key}
          className={[
            'journey-progress__step',
            i === current && 'journey-progress__step--current',
            i < current && 'journey-progress__step--done',
          ]
            .filter(Boolean)
            .join(' ')}
          aria-current={i === current ? 'step' : undefined}
        >
          <span className="journey-progress__num">{i + 1}</span>
          <span>{s.label}</span>
        </li>
      ))}
    </ol>
  );
}

/** Step 1: the products, side by side. */
function ProductStep({ onChoose, onBack }) {
  // The codes and limit ranges belong to the verification module, not to us, and it rejects an
  // application that disagrees with them. Read them; keep the built-in copy if it cannot be asked.
  //
  // The copy is the INITIAL state, not an error state, and that is what makes this safe to do on
  // mount: the cards are on screen before the request is made, so a slow module delays an update
  // rather than an empty page. Measured — module stopped, 0.4s; module hung, 5s (the orchestrator's
  // read timeout), both ending in the same two cards nobody saw flicker.
  const [products, setProducts] = useState(PRODUCTS);

  useEffect(() => {
    let live = true;
    api
      .products()
      .then((entries) => {
        if (live) setProducts(withLiveCatalogue(entries));
      })
      .catch(() => {
        /* the fallback list is already in state */
      });
    return () => {
      live = false;
    };
  }, []);

  return (
    <section className="card-shop">
      <div className="card-shop__intro">
        <div>
          <p className="card-shop__eyebrow">Neo credit cards</p>
          <PageHeader
            title="Choose your card"
            lede="Straightforward credit, built around how you spend."
          />
        </div>
        {onBack && (
          <Button variant="ghost" onClick={onBack}>
            ← Back to your account
          </Button>
        )}
      </div>

      <div className="card-shop__offers">
        {[...products, STUDENT_PREVIEW].map((p) => (
          <article
            key={p.code}
            className={`product-offer product-offer--${p.accent}`}
          >
            {(p.accent === 'platinum' || p.comingSoon) && (
              <span className="product-offer__recommended">
                {p.comingSoon ? 'Coming soon' : 'Most rewarding'}
              </span>
            )}

            <div className="product-offer__content">
              <div className="product-offer__heading">
                <p className="product-offer__kicker">{p.name}</p>
                <h2>{p.tagline}</h2>
              </div>

              <ul className="product-features">
                {p.features.map((f) => (
                  <li key={f}>{f}</li>
                ))}
              </ul>

              {p.comingSoon ? (
                <>
                  <dl className="product-offer__facts">
                    <div>
                      <dt>Availability</dt>
                      <dd>Coming soon</dd>
                    </div>
                    <div>
                      <dt>Credit limit</dt>
                      <dd>To be announced</dd>
                    </div>
                    <div>
                      <dt>Representative APR</dt>
                      <dd>To be announced</dd>
                    </div>
                  </dl>
                  <p className="product-offer__eligibility">
                    Pricing and eligibility details will be published before applications open.
                  </p>
                  <Button variant="secondary" block disabled>
                    Coming soon
                  </Button>
                </>
              ) : (
                <>
                  <dl className="product-offer__facts">
                    <div>
                      <dt>Annual fee</dt>
                      <dd>£0</dd>
                    </div>
                    <div>
                      <dt>Credit limit</dt>
                      <dd>{money(p.minLimit)}–{money(p.maxLimit)}</dd>
                    </div>
                    <div>
                      <dt>Representative APR</dt>
                      <dd>{p.apr}%</dd>
                    </div>
                  </dl>
                  <p className="product-offer__eligibility">
                    Minimum income {money(p.minIncome)}. Eligibility and the credit limit offered
                    are subject to status.
                  </p>
                  <Button
                    variant="primary"
                    block
                    onClick={() => onChoose(p)}
                  >
                    Apply for the {p.name}
                  </Button>
                </>
              )}
            </div>

            <div className="product-offer__visual">
              <p className="product-offer__tier">
                {p.comingSoon ? 'Student' : p.accent === 'platinum' ? 'Rewards' : 'Everyday'}
              </p>
              <div
                className="product-card-stack"
                tabIndex="0"
                aria-label={`${p.name}. Hover or focus to reveal the back of the card.`}
              >
                <div
                  className={`product-face product-face--back product-face--${p.accent}`}
                  aria-hidden="true"
                >
                  <span className="product-face__stripe" />
                  <span className="product-face__signature">AUTHORISED SIGNATURE</span>
                  <span className="product-face__number">
                    ••••&nbsp; ••••&nbsp; ••••&nbsp; 2048
                  </span>
                  <span className="product-face__back-brand">NEO / CREDIT</span>
                </div>

                <div className={`product-face product-face--front product-face--${p.accent}`}>
                  <div className="product-face__topline">
                    <span className="product-face__brand">NEO</span>
                    <span className="product-face__contactless" aria-hidden="true">)))</span>
                  </div>
                  <span className="product-face__chip" aria-hidden="true" />
                  <div className="product-face__bottomline">
                    <span className="product-face__name">{p.name}</span>
                    <span className="product-face__network">N</span>
                  </div>
                </div>
              </div>
              <p className="product-offer__reveal">Hover to reveal card back</p>
            </div>
          </article>
        ))}
      </div>

      <p className="card-shop__legal">
        Representative example: assuming your account is used only for purchases and the
        balance is repaid in equal monthly payments over 12 months. Rates are variable.
      </p>
    </section>
  );
}
