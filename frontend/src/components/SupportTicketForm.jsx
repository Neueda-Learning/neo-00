import React, { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Field,
  FormActions,
  Select,
  Textarea,
} from '../design-system';
import { api } from '../api.js';

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

/**
 * "Get in touch" once the application is done.
 *
 * One case per application, and by design: the support module derives its case id from the
 * journey's correlation id, so a second send quietly returns the first case rather than opening
 * another. That is the behaviour we want for somebody pressing a button twice, but it does mean
 * there is no new reference to quote back — hence a confirmation rather than a case number.
 */
export default function SupportTicketForm({ applicationId }) {
  const [category, setCategory] = useState(REASONS[0].value);
  const [description, setDescription] = useState('');
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setSending(true);
    setError(null);
    try {
      await api.openSupportCase(applicationId, { category, description: description.trim() });
      setSent(true);
    } catch (err) {
      setError(err.message);
    } finally {
      setSending(false);
    }
  }

  if (sent) {
    return (
      <Card title="We have got it">
        <Alert tone="positive">
          Thank you — someone from our team will be in touch about this application.
        </Alert>
      </Card>
    );
  }

  return (
    <Card title="Need a hand?" subtitle="tell us what is on your mind and we will get back to you">
      <form onSubmit={submit} noValidate>
        {error && <Alert tone="negative">{error}</Alert>}

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
