import React, { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  EmptyState,
  Field,
  FormActions,
  Select,
  Tag,
  Textarea,
  Timeline,
} from '../design-system';
import { api } from '../api.js';
import { clock } from '../status.js';

// The support desk's own taxonomy, in the customer's words. The CODES are the module's — it
// validates them against its configured list and refuses one it does not know — so these must
// stay in step with its seeded taxonomy. The labels are ours: nobody outside a bank calls it a
// DATA_CORRECTION.
const REASONS = [
  { value: 'APPLICATION_STATUS', label: 'A question about my application' },
  { value: 'CARD_NOT_ARRIVED', label: 'My card has not arrived' },
  { value: 'AGREEMENT_QUESTION', label: 'A question about my agreement' },
  { value: 'DATA_CORRECTION', label: 'Something about me is wrong' },
  { value: 'COMPLAINT', label: 'I want to make a complaint' },
  { value: 'OTHER', label: 'Something else' },
];

// Where the case has got to, said the way a customer would say it. The keys are the support
// module's statuses; an unknown one falls back to itself rather than being hidden, because a
// status nobody has taught this page is still news.
const SAYS = {
  NEW: { text: 'Received', tone: 'info' },
  OPEN: { text: 'Someone is looking at it', tone: 'info' },
  PENDING_CUSTOMER: { text: 'Waiting for you', tone: 'warning' },
  RESOLVED: { text: 'Resolved', tone: 'positive' },
  CLOSED: { text: 'Closed', tone: 'neutral' },
};

/**
 * "Get in touch", and then stay open.
 *
 * One case per application, by design: the support module derives its case id from the journey's
 * correlation id, so a second send returns the first case rather than opening another. So this
 * has two faces — the form, when there is no case, and the case itself once there is.
 *
 * The case face is the point. A confirmation that says "someone will be in touch" and then never
 * changes is a dead end; the customer has to go somewhere else to find out what happened, and in
 * this system there is nowhere else. So it polls, and whatever the support desk records against
 * the case turns up here.
 */
export default function SupportTicketForm({ applicationId }) {
  const [supportCase, setSupportCase] = useState(null);
  const [loaded, setLoaded] = useState(false);
  const [category, setCategory] = useState(REASONS[0].value);
  const [description, setDescription] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState(null);

  // Poll for the case: before it exists (so a case raised on another device shows up), and after,
  // so an answer from the support desk arrives without anybody pressing anything. Five seconds,
  // not one — a person typing at a desk is not the journey's second-by-second machinery.
  useEffect(() => {
    let live = true;
    async function load() {
      try {
        const c = await api.supportCase(applicationId);
        if (live) {
          setSupportCase(c);
          setLoaded(true);
        }
      } catch {
        if (live) setLoaded(true);   // no case is not an error; offer the form
      }
    }
    load();
    const id = setInterval(load, 5000);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, [applicationId]);

  async function submit(e) {
    e.preventDefault();
    setSending(true);
    setError(null);
    try {
      await api.openSupportCase(applicationId, { category, description: description.trim() });
      // Don't invent a case object here — ask for the real one, so what the customer sees is what
      // the support desk actually stored, reference and all.
      setSupportCase(await api.supportCase(applicationId));
    } catch (err) {
      setError(err.message);
    } finally {
      setSending(false);
    }
  }

  if (supportCase) {
    return <OpenCase supportCase={supportCase} />;
  }

  return (
    <Card title="Need a hand?" subtitle="tell us what is on your mind and we will get back to you">
      <form onSubmit={submit} noValidate>
        {error && <Alert tone="negative">{error}</Alert>}
        {!loaded && <EmptyState flush title="Checking…" />}

        <Field label="What is it about?" htmlFor="support-category">
          <Select
            id="support-category"
            options={REASONS}
            value={category}
            onChange={(e) => setCategory(e.target.value)}
          />
        </Field>

        <Field
          label="Tell us more"
          htmlFor="support-description"
          hint="a sentence or two is plenty"
        >
          <Textarea
            id="support-description"
            rows={4}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What happened?"
          />
        </Field>

        <FormActions>
          <Button type="submit" variant="primary" disabled={sending || !description.trim()}>
            {sending ? 'Sending…' : 'Send'}
          </Button>
        </FormActions>
      </form>
    </Card>
  );
}

/** The case, once it exists: where it is up to, and everything said about it so far. */
function OpenCase({ supportCase }) {
  const said = SAYS[supportCase.status] ?? { text: supportCase.status, tone: 'neutral' };
  const answered = supportCase.resolutionNote;
  // The resolution is shown as the answer above, so don't say it again three lines down. The
  // support desk records it in both places; that is right for an audit trail and wrong for a
  // person reading it.
  const conversation = (supportCase.updates ?? []).filter((u) => u.note !== answered);

  return (
    <Card
      title="Your enquiry"
      subtitle="we will update this as we go — you do not need to refresh"
      headEnd={<Badge tone={said.tone}>{said.text}</Badge>}
    >
      <p>
        Reference <Tag>{supportCase.reference}</Tag>
        {supportCase.openedAt && ` · raised ${clock(supportCase.openedAt)}`}
      </p>

      {answered && (
        <Alert tone="positive" title="Our answer">
          {answered}
        </Alert>
      )}

      {conversation.length > 0 ? (
        <Timeline
          items={conversation.map((u, i) => ({
            id: i,
            // The first entry is the customer's own words coming back at them, which is worth
            // showing: it is the record of what was asked.
            tone: i === 0 ? 'neutral' : 'info',
            title: <span>{u.actor}</span>,
            detail: u.note,
            when: clock(u.at),
          }))}
        />
      ) : (
        <EmptyState flush title="Nothing further yet" />
      )}
    </Card>
  );
}
