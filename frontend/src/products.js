// The two products the customer journey offers. This is marketing content plus the credit-limit
// range the application form bounds "requested limit" to. Codes mirror the backend generator
// (ApplicationFactory), so a submitted application and a generated backoffice fixture speak the
// same product language — the board renders "PREMIUM" / "PLATINUM" (it strips CREDIT_CARD_).
export const PRODUCTS = [
  {
    code: 'CREDIT_CARD_PREMIUM',
    name: 'Premium Card',
    tagline: 'Everyday rewards, no annual fee',
    accent: 'premium',
    apr: 24.9,
    minIncome: 18000,
    minLimit: 500,
    maxLimit: 10000,
    defaultLimit: 3000,
    features: [
      '1% cashback on everyday spend',
      'No annual fee',
      'Contactless & mobile wallet',
    ],
  },
  {
    code: 'CREDIT_CARD_PLATINUM',
    name: 'Platinum Card',
    tagline: 'Higher limits and premium perks',
    accent: 'platinum',
    apr: 22.9,
    minIncome: 40000,
    minLimit: 5000,
    maxLimit: 25000,
    defaultLimit: 10000,
    features: [
      'Airport lounge access',
      '24/7 travel concierge',
      'Comprehensive travel insurance',
    ],
  },
];

export const productByCode = (code) => PRODUCTS.find((p) => p.code === code);
