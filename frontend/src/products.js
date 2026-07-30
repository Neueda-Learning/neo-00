// The customer-facing catalogue uses the three product codes fixed by api-contract.md.
// Limit ranges and the minimum age mirror the latest versioned ProductConfig rows; APR,
// taglines and benefits are display copy because the shared contract does not define them.
export const PRODUCTS = [
  {
    code: 'CREDIT_CARD_STANDARD',
    name: 'Standard Card',
    tagline: 'Simple, flexible credit for everyday spending',
    accent: 'standard',
    apr: 19.9,
    minAge: 18,
    minLimit: 250,
    maxLimit: 5000,
    defaultLimit: 1500,
    defaultEmploymentStatus: 'PERMANENT',
    features: [
      'No annual fee',
      'Contactless & mobile wallet',
      'Real-time spending alerts',
    ],
  },
  {
    code: 'CREDIT_CARD_REWARDS',
    name: 'Rewards Card',
    tagline: 'Earn rewards on everyday spending',
    accent: 'rewards',
    apr: 24.9,
    minAge: 18,
    minLimit: 500,
    maxLimit: 10000,
    defaultLimit: 3000,
    defaultEmploymentStatus: 'PERMANENT',
    features: [
      '1% cashback on everyday spend',
      'No annual fee',
      'Contactless & mobile wallet',
    ],
  },
  {
    code: 'CREDIT_CARD_STUDENT',
    name: 'Student Card',
    tagline: 'Flexible credit designed for students',
    accent: 'student',
    apr: 14.9,
    minAge: 18,
    minLimit: 500,
    maxLimit: 3000,
    defaultLimit: 1000,
    defaultEmploymentStatus: 'STUDENT',
    features: [
      'No annual fee',
      'Cashback on study essentials',
      'Budgeting and spending alerts',
    ],
  },
];

export const productByCode = (code) => PRODUCTS.find((p) => p.code === code);
