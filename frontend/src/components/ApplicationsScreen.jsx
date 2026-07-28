import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Caption,
  DataTable,
  EmptyState,
  Field,
  Grid,
  MetricTile,
  PageHeader,
  SearchInput,
  Select,
  StepTrail,
  Tag,
  Timeline,
  Toolbar,
} from '../design-system';
import { api } from '../api.js';
import { clock, eventTone, journeyTone, money, stepTone, time } from '../status.js';

/**
 * The operator's board. Every application, with a dot per service showing how far it
 * got and how each one answered. Click a row for its full append-only log; or filter
 * to a single service to see everything that service has been asked.
 *
 * <p><b>This screen is the documented exception to the 10-row cap</b>
 * (design-system/DESIGN.md § "Board"), which is why `maxRows` is passed explicitly
 * rather than left at its default. The cap exists because a module board stores only
 * an applicationId and must hydrate every visible name from the orchestrator. This IS
 * the orchestrator: it owns the applications, hydrates nothing, and its whole job is
 * being watched. A module board that copies this line fails its Definition of Done.</p>
 */
export default function ApplicationsScreen({ rows, summary, services }) {
  const [openId, setOpenId] = useState(null);
  const [serviceFilter, setServiceFilter] = useState('');
  const [query, setQuery] = useState('');

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(
      (r) =>
        r.id.toLowerCase().includes(needle) ||
        (r.applicantName ?? '').toLowerCase().includes(needle)
    );
  }, [rows, query]);

  const columns = [
    { key: 'id', header: 'Application', mono: true },
    { key: 'applicantName', header: 'Applicant' },
    {
      key: 'product',
      header: 'Product',
      render: (r) => (
        <>
          {r.productCode?.replace('CREDIT_CARD_', '')}
          {r.requestedLimit ? ` · ${money(r.requestedLimit)}` : ''}
        </>
      ),
    },
    {
      key: 'steps',
      header: 'Journey',
      tight: true,
      render: (r) => (
        <StepTrail
          steps={r.steps.map((s) => ({
            id: s.step,
            label: `${s.step}. ${s.serviceId}`,
            status: s.status,
            tone: stepTone(s.status),
            current: s.status === 'in-flight',
          }))}
        />
      ),
    },
    {
      key: 'overallStatus',
      header: 'Outcome',
      tight: true,
      render: (r) => <Badge tone={journeyTone(r.overallStatus)}>{r.overallStatus}</Badge>,
    },
    { key: 'createdAt', header: 'Started', render: (r) => time(r.createdAt) },
  ];

  return (
    <>
      <PageHeader
        title="Applications"
        lede="live · one dot per service, in saga order · click a row for its full event log"
      />

      <Grid cols={6} min={140} style={{ marginBottom: 'var(--ds-space-6)' }}>
        <MetricTile label="Applications" value={summary?.total ?? 0} />
        <MetricTile label="In progress" value={summary?.inProgress ?? 0} tone="info" />
        <MetricTile label="Completed" value={summary?.completed ?? 0} tone="positive" />
        <MetricTile label="Rejected" value={summary?.rejected ?? 0} tone="negative" />
        <MetricTile label="Referred" value={summary?.referred ?? 0} tone="warning" />
        <MetricTile label="Failed" value={summary?.failed ?? 0} tone="negative" />
      </Grid>

      <Toolbar>
        <SearchInput
          grow
          placeholder="Application id or applicant name"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search applications"
        />
        <Field label="Events per service">
          {({ id }) => (
            <Select
              id={id}
              value={serviceFilter}
              onChange={(e) => setServiceFilter(e.target.value)}
              options={[
                { value: '', label: '— off, showing applications —' },
                ...services.map((s) => ({
                  value: s.serviceId,
                  label: `${s.step}. ${s.serviceId}`,
                })),
              ]}
            />
          )}
        </Field>
      </Toolbar>

      {serviceFilter ? (
        <ServiceEventLog serviceId={serviceFilter} />
      ) : (
        <DataTable
          columns={columns}
          rows={matches}
          total={matches.length}
          maxRows={50}
          rowKey={(r) => r.id}
          onRowClick={(r) => setOpenId(openId === r.id ? null : r.id)}
          expandedKey={openId}
          selectedKey={openId}
          renderExpanded={(r) => <ApplicationLog applicationId={r.id} />}
          footnote="newest first"
          empty={
            <EmptyState
              title={rows.length === 0 ? 'No applications yet' : 'Nothing matches that'}
            >
              {rows.length === 0 ? (
                <>
                  Flip the generator to <strong>Sending</strong>, or add a single
                  application with <strong>+ one</strong>.
                </>
              ) : (
                <>Clear the search to see the whole board.</>
              )}
            </EmptyState>
          }
        />
      )}
    </>
  );
}

/** The full append-only log for one application, refreshed while the row is open. */
function ApplicationLog({ applicationId }) {
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    async function load() {
      try {
        const d = await api.journey(applicationId);
        if (live) {
          setDetail(d);
          setError(null);
        }
      } catch (e) {
        if (live) setError(e.message);
      }
    }
    load();
    const id = setInterval(load, 1000);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, [applicationId]);

  if (error) return <Alert tone="negative">{error}</Alert>;
  if (!detail) return <EmptyState flush title="Loading…" />;

  return (
    <>
      <Timeline
        items={detail.events.map((e) => ({
          id: e.id,
          tone: eventTone(e),
          title: (
            <>
              <Tag>{e.stepIndex > 0 ? `step ${e.stepIndex}` : '—'}</Tag>
              <span>{e.eventType}</span>
              {e.serviceId && <Tag>{e.serviceId}</Tag>}
              {e.status && <Badge tone={stepTone(e.status)}>{e.status}</Badge>}
            </>
          ),
          detail: e.comment,
          when: clock(e.createdAt),
        }))}
      />
      <Caption>
        Append-only: every line was written once and never updated. This log is what
        the journey actually did.
      </Caption>
    </>
  );
}

/** Everything one service has been asked to do, newest first. */
function ServiceEventLog({ serviceId }) {
  const [events, setEvents] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    async function load() {
      try {
        const rows = await api.events(serviceId, 200);
        if (live) {
          setEvents(rows);
          setError(null);
        }
      } catch (e) {
        if (live) setError(e.message);
      }
    }
    load();
    const id = setInterval(load, 1000);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, [serviceId]);

  if (error) return <Alert tone="negative">{error}</Alert>;

  return (
    <DataTable
      columns={[
        { key: 'applicationId', header: 'Application', mono: true },
        { key: 'stepIndex', header: 'Step', numeric: true, tight: true },
        { key: 'eventType', header: 'Event' },
        {
          key: 'status',
          header: 'Status',
          tight: true,
          render: (e) => (e.status ? <Badge tone={stepTone(e.status)}>{e.status}</Badge> : '—'),
        },
        { key: 'comment', header: 'Comment' },
        { key: 'createdAt', header: 'When', render: (e) => clock(e.createdAt) },
      ]}
      rows={events}
      total={events.length}
      maxRows={50}
      rowKey={(e) => e.id}
      footnote="newest first"
      empty={
        <EmptyState title={`Nothing has reached ${serviceId} yet`}>
          Applications visit the services in order, so a later step waits until the
          earlier ones have accepted.
        </EmptyState>
      }
    />
  );
}
