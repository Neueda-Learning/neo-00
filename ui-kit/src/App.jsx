import React from 'react';
import { AppShell, TopNav, PageHeader, Card } from './design-system';

/**
 * Hello world.
 *
 * This app exists to prove one thing: the design system stands on its own. There
 * is no API here, no data, no state — and the screen still looks like the product,
 * because everything visual lives in `src/design-system/`.
 *
 * It is also the diff baseline. When the design system changes, this is where you
 * see the change with nothing else in the way.
 *
 * Copy `src/design-system/` into your own app, add the two imports from
 * DESIGN.md § "Install", and you are where this file is.
 */
export default function App() {
  return (
    <AppShell
      nav={<TopNav brand="NEO" product="UI kit" />}
      footer="design-system · vendored into every module repo · read design-system/DESIGN.md"
    >
      <PageHeader
        title="Hello world"
        lede="the design system, and nothing else"
      />
      <Card title="You are here">
        <p>
          Everything on this screen — the bar, the title, this panel, the type and
          the spacing — comes from <code>src/design-system/</code>. This file adds
          four components and no CSS.
        </p>
      </Card>
    </AppShell>
  );
}
