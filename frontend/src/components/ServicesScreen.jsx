import React from 'react';
import { BarChart, Caption, Card, Grid, StatusPill, Tag } from '../design-system';

/**
 * One panel per service: how many applications it is holding right now (dispatched,
 * no callback yet) and how it has answered so far.
 *
 * The in-progress number is only ever non-zero because journeys overlap — the
 * generator starts a new one every few seconds while each takes far longer to walk
 * ten services.
 */
export default function ServicesScreen({ services }) {
  const ceiling = Math.max(1, ...services.map((s) => s.total));

  return (
    <Grid cols="auto" min={320}>
      {services.map((s) => (
        <Card
          key={s.serviceId}
          title={`${s.step}. ${s.name}`}
          subtitle={s.serviceId}
          headEnd={
            <StatusPill tone={s.inProgress > 0 ? 'info' : 'neutral'}>
              {s.inProgress} in progress
            </StatusPill>
          }
          foot={
            <>
              {s.total} seen · <Tag>{s.baseUrl}</Tag>
            </>
          }
        >
          <BarChart
            labelWidth="90px"
            max={ceiling}
            data={[
              { label: 'Accepted', value: s.accepted, tone: 'positive' },
              { label: 'Rejected', value: s.rejected, tone: 'negative' },
              { label: 'Referred', value: s.referred, tone: 'warning' },
              ...(s.timedOut > 0
                ? [{ label: 'Timed out', value: s.timedOut, tone: 'negative' }]
                : []),
            ]}
          />
          {s.total === 0 && (
            <Caption>Nothing has reached this service yet.</Caption>
          )}
        </Card>
      ))}
    </Grid>
  );
}
