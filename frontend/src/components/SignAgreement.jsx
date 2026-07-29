import React, { useEffect, useState } from 'react';
import { Alert, Button, Card, EmptyState, FormActions, KeyValue, Spinner } from '../design-system';
import { api } from '../api.js';
import { money } from '../status.js';

/**
 * The one moment in the journey where the customer does something.
 *
 * The bank has approved a limit and drawn up a credit agreement; nothing else can happen until
 * this is signed. Neither button decides anything here — they report what the customer did to the
 * module that owns the agreement, and that module answers the orchestrator in its own time. The
 * status screen's poll picks the journey up again on its own a second later.
 */
export default function SignAgreement({ applicationId }) {
  const [terms, setTerms] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [sending, setSending] = useState(null);   // 'sign' | 'decline' | null
  const [actionError, setActionError] = useState(null);

  useEffect(() => {
    let live = true;
    api
      .agreement(applicationId)
      .then((t) => {
        if (live) {
          setTerms(t);
          setLoadError(null);
        }
      })
      .catch((e) => {
        if (live) setLoadError(e.message);
      });
    return () => {
      live = false;
    };
  }, [applicationId]);

  // The buttons stay disabled until the terms are in: signing something the page could not
  // display is exactly the thing a credit agreement exists to prevent.
  async function act(kind, call) {
    setSending(kind);
    setActionError(null);
    try {
      await call(applicationId);
      // Deliberately no local "signed!" state. What happens next is the journey's answer, and
      // the poll upstairs is already asking for it — inventing an outcome here would mean
      // showing one that the bank has not actually reached.
    } catch (e) {
      setActionError(e.message);
      setSending(null);
    }
  }

  return (
    <Card
      title="Your credit agreement"
      subtitle="read it, then sign to continue"
      foot={
        <FormActions>
          <Button
            variant="primary"
            disabled={!terms || sending != null}
            onClick={() => act('sign', api.signAgreement)}
          >
            {sending === 'sign' ? 'Signing…' : 'Sign the agreement'}
          </Button>
          <Button
            variant="ghost"
            disabled={!terms || sending != null}
            onClick={() => act('decline', api.declineAgreement)}
          >
            {sending === 'decline' ? 'Declining…' : 'Decline'}
          </Button>
        </FormActions>
      }
    >
      {loadError && <Alert tone="negative">{loadError}</Alert>}
      {actionError && <Alert tone="negative">{actionError}</Alert>}

      {!terms && !loadError && <EmptyState flush title="Fetching your agreement…" />}

      {terms && (
        <>
          <KeyValue
            items={[
              ['Credit limit', money(terms.approvedLimit)],
              ['Representative APR', terms.apr == null ? '—' : `${terms.apr}%`],
              ['Minimum monthly payment', money(terms.minPaymentGbp)],
              ['Terms version', terms.termsVersion ?? '—'],
            ]}
          />
          {terms.documentAvailable ? (
            <iframe
              className="agreement-doc"
              title="Your credit agreement"
              src={api.agreementDocumentUrl(applicationId)}
            />
          ) : (
            <Alert tone="warning">
              We are still preparing your agreement document. It will appear here shortly.
            </Alert>
          )}
          {sending != null && <Spinner label="Sending your answer" />}
        </>
      )}
    </Card>
  );
}
