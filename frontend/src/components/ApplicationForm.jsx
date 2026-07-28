import React, { useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Field,
  Select,
  TextInput,
} from '../design-system';
import platinumCardImage from '../assets/cards/neo-platinum-card.png';
import premiumCardImage from '../assets/cards/neo-premium-card.png';
import { money } from '../status.js';
import { api } from '../api.js';

// The supported set from api-contract.md (tax residency / countries). Kept short on purpose.
const COUNTRIES = ['GB', 'IE', 'PL', 'DE', 'FR', 'ES', 'NL'];
const RESIDENTIAL = ['OWNER', 'MORTGAGE', 'RENTING', 'LIVING_WITH_FAMILY', 'OTHER'];
const EMPLOYMENT = ['PERMANENT', 'CONTRACT', 'SELF_EMPLOYED', 'STUDENT', 'RETIRED', 'UNEMPLOYED'];

const readable = (v) => ({ value: v, label: v.replace(/_/g, ' ').toLowerCase() });
const CARD_IMAGES = {
  premium: premiumCardImage,
  platinum: platinumCardImage,
};

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
      type: 'PASSPORT',
      documentId: `ZZ${Math.floor(1000000 + Math.random() * 8999999)}`,
      issuingCountry: f.nationality,
      expiryDate: '2031-01-31',
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
    <form className="application-details" onSubmit={submit}>
      <header className="application-details__hero">
        <img
          className="application-details__card-image"
          src={CARD_IMAGES[product.accent]}
          alt={`Selected ${product.name}`}
        />
        <h1>Let's get started</h1>
      </header>

      <section className="application-benefits" aria-labelledby="application-benefits-title">
        <h2 id="application-benefits-title">More ways to earn</h2>
        <ul>
          {product.features.map((feature) => (
            <li key={feature}>Complimentary {feature}</li>
          ))}
        </ul>
      </section>

      <div className="application-form">
        <section className="application-form__section" aria-labelledby="personal-info-title">
          <h2 id="personal-info-title">Personal info</h2>

          <div className="application-form__row">
            <Field label="Full name" required>
              {({ id }) => (
                <TextInput
                  id={id}
                  name="fullName"
                  autoComplete="name"
                  value={f.fullName}
                  onChange={set('fullName')}
                  required
                  placeholder="Ada Byron"
                />
              )}
            </Field>
            <Field label="Date of birth" required>
              {({ id }) => (
                <TextInput
                  id={id}
                  name="dateOfBirth"
                  type="date"
                  autoComplete="bday"
                  value={f.dateOfBirth}
                  onChange={set('dateOfBirth')}
                  required
                />
              )}
            </Field>
          </div>

          <div className="application-form__row">
            <Field label="Nationality">
              {({ id }) => (
                <Select
                  id={id}
                  name="nationality"
                  value={f.nationality}
                  onChange={set('nationality')}
                  options={COUNTRIES}
                />
              )}
            </Field>
            <Field label="Country of residence">
              {({ id }) => (
                <Select
                  id={id}
                  name="countryOfResidence"
                  value={f.countryOfResidence}
                  onChange={set('countryOfResidence')}
                  options={COUNTRIES}
                />
              )}
            </Field>
          </div>
        </section>

        <section className="application-form__section" aria-labelledby="contact-info-title">
          <h2 id="contact-info-title">Contact info</h2>

          <div className="application-form__row">
            <Field label="Email address" hint="Left blank, we make one up">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="email"
                  type="email"
                  autoComplete="email"
                  value={f.email}
                  onChange={set('email')}
                  placeholder="ada@example.com"
                />
              )}
            </Field>
            <Field label="Phone number (mobile preferred)">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="mobile"
                  type="tel"
                  autoComplete="tel"
                  value={f.mobile}
                  onChange={set('mobile')}
                />
              )}
            </Field>
          </div>

          <aside className="application-form__notice">
            We use your contact information to provide updates about this application.
          </aside>
        </section>

        <section className="application-form__section" aria-labelledby="address-info-title">
          <h2 id="address-info-title">Current address</h2>

          <div className="application-form__row">
            <Field label="Address line 1">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="addressLine1"
                  autoComplete="address-line1"
                  value={f.line1}
                  onChange={set('line1')}
                />
              )}
            </Field>
            <Field label="City">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="city"
                  autoComplete="address-level2"
                  value={f.city}
                  onChange={set('city')}
                />
              )}
            </Field>
          </div>

          <div className="application-form__row">
            <Field label="Postcode">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="postcode"
                  autoComplete="postal-code"
                  value={f.postcode}
                  onChange={set('postcode')}
                />
              )}
            </Field>
            <Field label="Months at address">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="monthsAtAddress"
                  type="number"
                  min="0"
                  value={f.monthsAtAddress}
                  onChange={set('monthsAtAddress')}
                />
              )}
            </Field>
          </div>
        </section>

        <section className="application-form__section" aria-labelledby="financial-info-title">
          <h2 id="financial-info-title">Financial info</h2>

          <div className="application-form__row">
            <Field label="Residential status">
              {({ id }) => (
                <Select
                  id={id}
                  name="residentialStatus"
                  value={f.residentialStatus}
                  onChange={set('residentialStatus')}
                  options={RESIDENTIAL.map(readable)}
                />
              )}
            </Field>
            <Field label="Employment status">
              {({ id }) => (
                <Select
                  id={id}
                  name="employmentStatus"
                  value={f.employmentStatus}
                  onChange={set('employmentStatus')}
                  options={EMPLOYMENT.map(readable)}
                />
              )}
            </Field>
          </div>

          <div className="application-form__row">
            <Field label="Employer">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="employerName"
                  autoComplete="organization"
                  value={f.employerName}
                  onChange={set('employerName')}
                />
              )}
            </Field>
            <Field label="Months in employment">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="monthsInEmployment"
                  type="number"
                  min="0"
                  value={f.monthsInEmployment}
                  onChange={set('monthsInEmployment')}
                />
              )}
            </Field>
          </div>

          <div className="application-form__row">
            <Field label="Annual income (£)" required>
              {({ id }) => (
                <TextInput
                  id={id}
                  name="annualIncome"
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
                  name="monthlyHousingCost"
                  type="number"
                  min="0"
                  step="50"
                  value={f.monthlyHousingCost}
                  onChange={set('monthlyHousingCost')}
                />
              )}
            </Field>
          </div>

          <div className="application-form__row">
            <Field label="Existing monthly credit (£)">
              {({ id }) => (
                <TextInput
                  id={id}
                  name="existingCreditCommitments"
                  type="number"
                  min="0"
                  step="10"
                  value={f.existingCreditCommitments}
                  onChange={set('existingCreditCommitments')}
                />
              )}
            </Field>
            <Field
              label="Requested credit limit (£)"
              hint={`between ${money(product.minLimit)} and ${money(product.maxLimit)}`}
              required
            >
              {({ id }) => (
                <TextInput
                  id={id}
                  name="requestedCreditLimit"
                  type="number"
                  min={product.minLimit}
                  max={product.maxLimit}
                  step="100"
                  value={f.requestedCreditLimit}
                  onChange={set('requestedCreditLimit')}
                  required
                />
              )}
            </Field>
          </div>

          <aside className="application-form__guidance">
            <span aria-hidden="true">i</span>
            <div>
              <h3>You should know</h3>
              <p>
                Enter your total gross annual income before tax. Your requested limit must stay
                within the range offered for the {product.name}.
              </p>
            </div>
          </aside>
        </section>

        <div className="application-form__consent">
          <Checkbox
            label="I accept the terms and consent to a credit check."
            checked={f.termsAccepted}
            onChange={set('termsAccepted')}
            required
          />
        </div>

        {error && (
          <Alert tone="negative" title="Could not submit">
            {error}
          </Alert>
        )}

        <div className="application-form__actions">
          <Button type="button" variant="ghost" onClick={onBack} disabled={submitting}>
            ← Back
          </Button>
          <Button type="submit" variant="primary" busy={submitting} busyLabel="Submitting…">
            Submit application
          </Button>
        </div>
      </div>
    </form>
  );
}
