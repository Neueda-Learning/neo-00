import React, { useEffect, useState } from 'react';
import {
  Alert,
  AppShell,
  Button,
  Card,
  Field,
  FormActions,
  PageHeader,
  TextInput,
  TopNav,
} from '../design-system';
import { api } from '../api.js';

/** Two letters, two digits. Short enough to say out loud and to type without looking. */
const CODE = /^[A-Z]{2}[0-9]{2}$/;

/**
 * Who are you? Four characters, and no password.
 *
 * <p>There being no password is the whole shape of this screen. "AB12 is taken" and "AB12 is
 * yours" are the same fact, so a code that already exists cannot be an error — it just means you
 * are that customer. What the screen can honestly do is say which of the two is about to happen
 * before you commit, and that is what the hint under the field is.
 *
 * This identifies; it does not authenticate. Anyone who types your code is you.
 */
export default function LoginScreen({ onSignedIn, onHome }) {
  const [code, setCode] = useState('');
  const [hint, setHint] = useState(null);   // 'taken' | 'free' | 'unknown' | null
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const valid = CODE.test(code);

  // The typing hint. Debounced so a four-character code is one request, not four.
  useEffect(() => {
    if (!valid) {
      setHint(null);
      return undefined;
    }
    let live = true;
    const timer = setTimeout(() => {
      api
        .customer(code)
        .then(() => live && setHint('taken'))
        .catch((e) => {
          if (!live) return;
          // ONLY a 404 means free. Any other failure is the orchestrator being unreachable, and
          // reading that as "available" would invite someone to create a code that exists.
          setHint(e.message === 'HTTP 404' ? 'free' : 'unknown');
        });
    }, 350);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [code, valid]);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // The greeting comes from THIS answer, not from the hint above: the two are separate
      // requests and a code can be created in between.
      const view = await api.signIn(code);
      onSignedIn(view);
    } catch (err) {
      setError(err.message);
      setBusy(false);
    }
  }

  return (
    <AppShell
      className="customer-shell login-shell"
      nav={
        <TopNav
          brand="NEO BANK"
          product="Customer access"
          actions={
            <Button variant="ghost" size="sm" onClick={onHome}>
              ← Home
            </Button>
          }
        />
      }
    >
      <PageHeader
        title="Your customer code"
        lede="use the one you already have, or make up a new one — two letters and two numbers"
      />

      <Card>
        <form onSubmit={submit} noValidate>
          {error && <Alert tone="negative">{error}</Alert>}

          <Field
            label="Customer code"
            hint={HINTS[hint] ?? 'for example AB12'}
            error={code.length === 4 && !valid ? 'two letters, then two numbers' : undefined}
          >
            {({ id }) => (
              <TextInput
                id={id}
                mono
                autoFocus
                maxLength={4}
                placeholder="AB12"
                value={code}
                // Uppercased as you type, so what you see is what is stored. The server
                // normalises too — this is only so the field does not lie to you.
                onChange={(e) => setCode(e.target.value.toUpperCase().slice(0, 4))}
              />
            )}
          </Field>

          <FormActions>
            <Button type="submit" variant="primary" disabled={!valid} busy={busy}>
              Continue
            </Button>
          </FormActions>
        </form>
      </Card>
    </AppShell>
  );
}

const HINTS = {
  taken: 'already in use — you will continue as that customer',
  free: 'free — we will create it',
  unknown: 'we cannot check that right now, but you can still continue',
};
