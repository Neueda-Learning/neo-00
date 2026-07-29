// The two products the customer journey offers: marketing content, plus the credit-limit range
// the application form bounds "requested limit" to.
//
// THE CODES ARE NEO-01'S. It owns the catalogue and refuses anything else, so a code invented
// here is an application that dies at step 1 — which is exactly what happened for as long as this
// file offered PREMIUM and PLATINUM to a service that only sells STANDARD, REWARDS and STUDENT.
// The board renders "STANDARD" / "REWARDS" (it strips CREDIT_CARD_).
//
// The limits below are a FALLBACK. At runtime the picker reads GET /api/v1/products, which proxies
// neo-01's live catalogue, and uses those bounds instead — neo-01's config is versioned and its
// team adds versions. These values are the current ones, so the form still works with neo-01 down.
//
// STUDENT is deliberately not offered: neo-01 restricts it to applicants whose employment status
// is STUDENT, and this form's applicants are employed.
export const PRODUCTS = [
  {
    code: 'CREDIT_CARD_STANDARD',
    name: 'Standard Card',
    tagline: 'Everyday spending, no annual fee',
    accent: 'premium',
    apr: 24.9,
    minIncome: 18000,
    minLimit: 250,
    maxLimit: 5000,
    defaultLimit: 3000,
    features: [
      'No annual fee',
      'Contactless & mobile wallet',
      'Freeze and unfreeze in the app',
    ],
  },
  {
    code: 'CREDIT_CARD_REWARDS',
    name: 'Rewards Card',
    tagline: 'Higher limits and cashback on every purchase',
    accent: 'platinum',
    apr: 22.9,
    minIncome: 24000,
    minLimit: 500,
    maxLimit: 10000,
    defaultLimit: 5000,
    features: [
      '1% cashback on everyday spend',
      'Travel insurance included',
      '24/7 support line',
    ],
  },
];

export const productByCode = (code) => PRODUCTS.find((p) => p.code === code);

/**
 * Fold neo-01's live catalogue into the marketing copy above.
 *
 * A product neo-01 no longer sells disappears from the picker; one we have no copy for is not
 * invented on the spot. An empty or failed read leaves the fallback list untouched — the shop
 * window stays open when the verification service is down.
 */
export function withLiveCatalogue(entries) {
  if (!entries || entries.length === 0) return PRODUCTS;
  return PRODUCTS.filter((p) => entries.some((e) => e.productCode === p.code)).map((p) => {
    const live = entries.find((e) => e.productCode === p.code);
    const minLimit = live.limitMin ?? p.minLimit;
    const maxLimit = live.limitMax ?? p.maxLimit;
    return {
      ...p,
      minLimit,
      maxLimit,
      // Keep the marketing default, but never outside the range the form will be judged against.
      defaultLimit: Math.min(Math.max(p.defaultLimit, minLimit), maxLimit),
    };
  });
}
