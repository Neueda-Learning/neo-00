import React, { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Field,
  FormActions,
  FormGrid,
  PageHeader,
  Select,
  Stack,
  TextInput,
} from '../design-system';
import { money } from '../status.js';
import { api } from '../api.js';

// The supported set from api-contract.md (tax residency / countries). Kept short on purpose.
const COUNTRIES = ['GB', 'IE', 'PL', 'DE', 'FR', 'ES', 'NL'];
const RESIDENTIAL = ['OWNER', 'MORTGAGE', 'RENTING', 'LIVING_WITH_FAMILY', 'OTHER'];
const EMPLOYMENT = ['PERMANENT', 'CONTRACT', 'SELF_EMPLOYED', 'STUDENT', 'RETIRED', 'UNEMPLOYED'];
// Exactly the three api-contract.md §4 allows for identityDocument.type — no more, or a
// module validating against the enum rejects an application this form produced.
const DOCUMENT_TYPES = ['PASSPORT', 'DRIVING_LICENCE', 'NATIONAL_ID'];

const readable = (v) => ({ value: v, label: v.replace(/_/g, ' ').toLowerCase() });

// Sensible defaults so an attendee can submit in one click, or tweak anything first.
const defaults = (product) => ({
  fullName: '',
  dateOfBirth: '1990-05-15',
  email: '',
  mobile: '+447700900123',
  nationality: 'GB',
  countryOfResidence: 'GB',
  residentialStatus: 'RENTING',
  line1: '10 Downing Street',
  city: 'London',
  postcode: 'SW1A 2AA',
  monthsAtAddress: 24,
  documentType: 'PASSPORT',
  documentId: 'ZS1234567',
  documentIssuingCountry: 'GB',
  documentExpiryDate: '2031-01-31',
  employmentStatus: 'PERMANENT',
  employerName: 'Acme Ltd',
  monthsInEmployment: 36,
  annualIncome: Math.max(35000, product.minIncome),
  monthlyHousingCost: 900,
  existingCreditCommitments: 150,
  requestedCreditLimit: product.defaultLimit,
  termsAccepted: false,
});

const slug = (name) => name.trim().toLowerCase().replace(/\s+/g, '.') || 'applicant';

/** Turn the flat form state into the api-contract §4 Application object. */
function toApplication(f, product) {
  return {
    channel: 'WEB',
    applicant: {
      fullName: f.fullName.trim(),
      dateOfBirth: f.dateOfBirth,
      email: f.email.trim() || `${slug(f.fullName)}@example.com`,
      mobile: f.mobile,
      nationality: f.nationality,
      countryOfResidence: f.countryOfResidence,
      taxResidencies: [f.countryOfResidence],
      residentialStatus: f.residentialStatus,
      currentAddress: {
        line1: f.line1,
        line2: null,
        city: f.city,
        postcode: f.postcode,
        country: f.countryOfResidence,
      },
      monthsAtAddress: Number(f.monthsAtAddress),
      dependants: 0,
    },
    identityDocument: {
      type: f.documentType,
      documentId: f.documentId.trim().toUpperCase(),
      issuingCountry: f.documentIssuingCountry,
      expiryDate: f.documentExpiryDate,
    },
    employment: {
      status: f.employmentStatus,
      employerName: f.employerName,
      monthsInEmployment: Number(f.monthsInEmployment),
    },
    finances: {
      annualIncome: Number(f.annualIncome),
      monthlyHousingCost: Number(f.monthlyHousingCost),
      existingCreditCommitments: Number(f.existingCreditCommitments),
    },
    product: {
      productCode: product.code,
      requestedCreditLimit: Number(f.requestedCreditLimit),
    },
    delivery: { useCurrentAddress: true, address: null },
    consents: { termsAccepted: true, paperlessStatements: true, marketingConsent: false },
  };
}

/** Step 2: the application form for the chosen product. */
export default function ApplicationForm({ product, onBack, onSubmitted }) {
  const [f, setF] = useState(() => defaults(product));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const set = (key) => (e) => {
    const value = e.target.type === 'checkbox' ? e.target.checked : e.target.value;
    setF((prev) => ({ ...prev, [key]: value }));
  };

  async function submit(e) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const created = await api.submitApplication(toApplication(f, product));
      onSubmitted(created.id);
    } catch (err) {
      setError(err.message);
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit}>
      <PageHeader
        title="Your details"
        lede={`applying for the ${product.name}`}
      />

      <Stack gap={5}>
        <Card title="About you">
          <FormGrid cols={3}>
            <Field label="Full name" required>
              {({ id }) => (
                <TextInput
                  id={id}
                  value={f.fullName}
                  onChange={set('fullName')}
                  required
                  placeholder="Ada Byron"
                />
              )}
            </Field>
            <Field label="Date of birth" required>
              {({ id }) => (
                <TextInput id={id} type="date" value={f.dateOfBirth} onChange={set('dateOfBirth')} required />
              )}
            </Field>
            <Field label="Email" hint="left blank, we make one up">
              {({ id }) => (
                <TextInput id={id} type="email" value={f.email} onChange={set('email')} placeholder="optional" />
              )}
            </Field>
            <Field label="Mobile">
              {({ id }) => <TextInput id={id} value={f.mobile} onChange={set('mobile')} />}
            </Field>
            <Field label="Nationality">
              {({ id }) => (
                <Select id={id} value={f.nationality} onChange={set('nationality')} options={COUNTRIES} />
              )}
            </Field>
            <Field label="Country of residence">
              {({ id }) => (
                <Select
                  id={id}
                  value={f.countryOfResidence}
                  onChange={set('countryOfResidence')}
                  options={COUNTRIES}
                />
              )}
            </Field>
            <Field label="Residential status">
              {({ id }) => (
                <Select
                  id={id}
                  value={f.residentialStatus}
                  onChange={set('residentialStatus')}
                  options={RESIDENTIAL.map(readable)}
                />
              )}
            </Field>
            <Field label="Months at address">
              {({ id }) => (
                <TextInput
                  id={id}
                  type="number"
                  min="0"
                  value={f.monthsAtAddress}
                  onChange={set('monthsAtAddress')}
                />
              )}
            </Field>
            <Field label="Address line 1">
              {({ id }) => <TextInput id={id} value={f.line1} onChange={set('line1')} />}
            </Field>
            <Field label="City">
              {({ id }) => <TextInput id={id} value={f.city} onChange={set('city')} />}
            </Field>
            <Field label="Postcode">
              {({ id }) => <TextInput id={id} value={f.postcode} onChange={set('postcode')} />}
            </Field>
          </FormGrid>
        </Card>

        <Card title="Identity document">
          <FormGrid cols={3}>
            <Field label="Document type" required>
              {({ id }) => (
                <Select
                  id={id}
                  value={f.documentType}
                  onChange={set('documentType')}
                  options={DOCUMENT_TYPES.map(readable)}
                />
              )}
            </Field>
            <Field label="Document number" required>
              {({ id }) => (
                <TextInput
                  id={id}
                  value={f.documentId}
                  onChange={set('documentId')}
                  required
                  placeholder="ZS1234567"
                />
              )}
            </Field>
            <Field label="Issuing country" required>
              {({ id }) => (
                <Select
                  id={id}
                  value={f.documentIssuingCountry}
                  onChange={set('documentIssuingCountry')}
                  options={COUNTRIES}
                />
              )}
            </Field>
            <Field label="Expiry date" required>
              {({ id }) => (
                <TextInput
                  id={id}
                  type="date"
                  value={f.documentExpiryDate}
                  onChange={set('documentExpiryDate')}
                  required
                />
              )}
            </Field>
          </FormGrid>
        </Card>

        <Card title="Work and money">
          <FormGrid cols={3}>
            <Field label="Employment status">
              {({ id }) => (
                <Select
                  id={id}
                  value={f.employmentStatus}
                  onChange={set('employmentStatus')}
                  options={EMPLOYMENT.map(readable)}
                />
              )}
            </Field>
            <Field label="Employer">
              {({ id }) => <TextInput id={id} value={f.employerName} onChange={set('employerName')} />}
            </Field>
            <Field label="Months in employment">
              {({ id }) => (
                <TextInput
                  id={id}
                  type="number"
                  min="0"
                  value={f.monthsInEmployment}
                  onChange={set('monthsInEmployment')}
                />
              )}
            </Field>
            <Field label="Annual income (£)" required>
              {({ id }) => (
                <TextInput
                  id={id}
                  type="number"
                  min="0"
                  step="1000"
                  value={f.annualIncome}
                  onChange={set('annualIncome')}
                  required
                />
              )}
            </Field>
            <Field label="Monthly housing cost (£)">
              {({ id }) => (
                <TextInput
                  id={id}
                  type="number"
                  min="0"
                  step="50"
                  value={f.monthlyHousingCost}
                  onChange={set('monthlyHousingCost')}
                />
              )}
            </Field>
            <Field label="Existing monthly credit (£)">
              {({ id }) => (
                <TextInput
                  id={id}
                  type="number"
                  min="0"
                  step="10"
                  value={f.existingCreditCommitments}
                  onChange={set('existingCreditCommitments')}
                />
              )}
            </Field>
          </FormGrid>
        </Card>

        <Card title={`Your ${product.name}`}>
          <FormGrid cols={2}>
            <Field
              label="Requested credit limit (£)"
              hint={`between ${money(product.minLimit)} and ${money(product.maxLimit)}`}
              required
            >
              {({ id }) => (
                <TextInput
                  id={id}
                  type="number"
                  min={product.minLimit}
                  max={product.maxLimit}
                  // step="any", NOT a round number. HTML5 validates `step` as an offset from
                  // `min`, and `min` is neo-01's — it moved from 500 to 250 the day the
                  // catalogue was corrected, at which point step="100" made the form's OWN
                  // default of 3000 invalid and the browser blocked submit with no visible
                  // error. A granularity rule derived from somebody else's floor is a trap;
                  // the range is a real constraint, the roundness was never one.
                  step="any"
                  value={f.requestedCreditLimit}
                  onChange={set('requestedCreditLimit')}
                  required
                />
              )}
            </Field>
            <FormGrid.Full>
              <Checkbox
                label="I accept the terms and consent to a credit check."
                checked={f.termsAccepted}
                onChange={set('termsAccepted')}
                required
              />
            </FormGrid.Full>
          </FormGrid>
        </Card>

        {error && (
          <Alert tone="negative" title="Could not submit">
            {error}
          </Alert>
        )}

        <FormActions>
          <Button type="submit" variant="primary" busy={submitting} busyLabel="Submitting…">
            Submit application
          </Button>
          <Button type="button" variant="ghost" onClick={onBack} disabled={submitting}>
            ← Back
          </Button>
        </FormActions>
      </Stack>
    </form>
  );
}
