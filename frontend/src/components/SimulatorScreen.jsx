import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  AppShell,
  Badge,
  Button,
  Caption,
  CodeBlock,
  DataTable,
  EmptyState,
  Field,
  FormActions,
  PageHeader,
  Select,
  Split,
  StatusPill,
  Tag,
  Textarea,
  Toolbar,
  TopNav,
} from '../design-system';
import { api } from '../api.js';
import { clock, httpTone } from '../status.js';

const DOMAIN = {
  neo01: 'verification',
  neo02: 'policy',
  neo03: 'kyc',
  neo04: 'screening',
  neo05: 'credit',
  neo06: 'agreement',
  neo07: 'account',
  neo08: 'card',
  neo09: 'support',
  neo10: 'analytics',
};

function scenarioLabel(scenario) {
  return `${scenario.id} · ${scenario.title}`;
}

function scenarioIdFrom(row) {
  if (row.scenarioId) return row.scenarioId;
  const match = row.applicationId?.match(/^(SIM-\d+)/);
  return match?.[1] || 'CUSTOM';
}

export default function SimulatorScreen({ onHome }) {
  const [health, setHealth] = useState(null);
  const [scenarios, setScenarios] = useState([]);
  const [targets, setTargets] = useState([]);
  const [scenarioId, setScenarioId] = useState('');
  const [targetId, setTargetId] = useState('');
  const [envelope, setEnvelope] = useState('');
  const [rows, setRows] = useState([]);
  const [last, setLast] = useState(null);
  const [expanded, setExpanded] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const [scenarioCatalogue, targetRows, dispatchRows, currentHealth] = await Promise.all([
          api.scenarios(),
          api.targets(),
          api.dispatches(),
          api.health(),
        ]);
        if (!active) return;
        const scenarioRows = scenarioCatalogue.scenarios || [];
        setScenarios(scenarioRows);
        setTargets(targetRows);
        setRows(dispatchRows);
        setHealth(currentHealth);
        const firstScenario = scenarioRows[0];
        const firstTarget = targetRows[0];
        if (firstScenario) {
          setScenarioId(firstScenario.id);
          setEnvelope(JSON.stringify(firstScenario.request, null, 2));
        }
        if (firstTarget) setTargetId(firstTarget.serviceId);
      } catch (e) {
        if (active) setError(e.message);
      }
    }
    load();
    return () => {
      active = false;
    };
  }, []);

  const selectedScenario = scenarios.find((scenario) => scenario.id === scenarioId);
  const selectedTarget = targets.find((target) => target.serviceId === targetId);
  const parsed = useMemo(() => {
    try {
      return { value: JSON.parse(envelope), error: null };
    } catch (e) {
      return { value: null, error: e.message };
    }
  }, [envelope]);

  const sortedScenarios = useMemo(() => {
    const domain = DOMAIN[targetId];
    return [...scenarios].sort((a, b) => {
      const aRelevant = a.modules?.includes(domain) ? 0 : 1;
      const bRelevant = b.modules?.includes(domain) ? 0 : 1;
      return aRelevant - bRelevant || a.id.localeCompare(b.id);
    });
  }, [scenarios, targetId]);

  function chooseScenario(nextId) {
    setScenarioId(nextId);
    const next = scenarios.find((scenario) => scenario.id === nextId);
    if (next) setEnvelope(JSON.stringify(next.request, null, 2));
    setLast(null);
    setError(null);
  }

  async function reload() {
    setRows(await api.dispatches());
  }

  async function send() {
    if (parsed.error) return;
    setBusy('send');
    setError(null);
    try {
      // Send the editor value, not merely scenarioId: this is how an operator supplies outputs
      // that a one-shot call cannot accumulate from earlier journey steps.
      const result = await api.dispatch({ envelope: parsed.value, targetServiceId: targetId });
      setLast(result);
      await reload();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  async function startJourney() {
    if (parsed.error) return;
    setBusy('journey');
    setError(null);
    try {
      setLast(await api.submitApplication(parsed.value.application));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  async function clear() {
    if (!targetId) return;
    setBusy('clear');
    setError(null);
    try {
      await api.clearDispatches(targetId);
      setLast(null);
      await reload();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  const columns = [
    {
      key: 'scenario',
      header: 'Scenario',
      render: (row) => <Tag>{scenarioIdFrom(row)}</Tag>,
    },
    {
      key: 'targetServiceId',
      header: 'Target',
      render: (row) => <Tag>{row.targetServiceId}</Tag>,
    },
    { key: 'sentAt', header: 'Sent', mono: true, render: (row) => clock(row.sentAt) },
    {
      key: 'ackHttpStatus',
      header: 'HTTP',
      tight: true,
      render: (row) => (
        <Badge tone={httpTone(row.ackHttpStatus)}>
          {row.ackHttpStatus === 0 ? 'unreachable' : row.ackHttpStatus ?? 'sending'}
        </Badge>
      ),
    },
    {
      key: 'reportedStatus',
      header: 'Report',
      render: (row) =>
        row.reportedStatus ? (
          <>
            <Tag>{row.reportedStatus}</Tag>
            {row.canonicalStatus && row.canonicalStatus !== row.reportedStatus && (
              <> → <Badge tone="positive">{row.canonicalStatus}</Badge></>
            )}
          </>
        ) : 'Awaiting callback',
    },
    { key: 'reportedAt', header: 'Answered', mono: true, render: (row) => clock(row.reportedAt) },
  ];

  const nav = (
    <TopNav
      brand="NEO"
      product="Simulator"
      actions={
        <>
          <StatusPill tone={health?.status === 'UP' ? 'positive' : 'negative'}>
            {health?.status || 'UNKNOWN'}
          </StatusPill>
          <Button variant="ghost" size="sm" onClick={onHome}>← Home</Button>
        </>
      }
    />
  );

  return (
    <AppShell className="simulator-shell" nav={nav}>
      <PageHeader
        title="Module Simulator"
        lede="one scenario · one configured target · no journey state"
        meta={selectedTarget?.analytical ? 'Analytical target — acknowledgement only' : undefined}
      />

      {error && <Alert tone="negative" title="Simulator could not complete the request">{error}</Alert>}
      {last?.ackHttpStatus === 0 && (
        <Alert tone="negative" title="Module unreachable">{String(last.ackBody)}</Alert>
      )}

      <Toolbar>
        <Field label="Target module" htmlFor="simulator-target">
          <Select
            id="simulator-target"
            value={targetId}
            onChange={(event) => setTargetId(event.target.value)}
            options={targets.map((target) => ({
              value: target.serviceId,
              label: `${target.serviceId} · ${target.name}${target.analytical ? ' · analytical' : ''}`,
            }))}
          />
        </Field>
        <Field label="Scenario" htmlFor="simulator-scenario">
          <Select
            id="simulator-scenario"
            value={scenarioId}
            onChange={(event) => chooseScenario(event.target.value)}
            options={sortedScenarios.map((scenario) => ({
              value: scenario.id,
              label: scenarioLabel(scenario),
            }))}
          />
        </Field>
      </Toolbar>

      <Split
        ratio="wide"
        className="simulator-editor"
        sidebar={
          <Field label="Latest response">
            <CodeBlock value={last || { status: 'No response yet' }} scroll />
          </Field>
        }
      >
        <Field
          label="Dispatch envelope"
          htmlFor="simulator-envelope"
          hint={selectedScenario?.trait}
          error={parsed.error ? `Invalid JSON: ${parsed.error}` : null}
        >
          <Textarea
            id="simulator-envelope"
            className="simulator-envelope"
            mono
            invalid={Boolean(parsed.error)}
            value={envelope}
            onChange={(event) => setEnvelope(event.target.value)}
          />
        </Field>
        <Caption>
          One-shot dispatches cannot accumulate outputs from steps 1…N−1. Paste an
          <Tag>outputs</Tag> object into this envelope when the target reads earlier-step output.
          Simulator applications are available through <Tag>{'GET /applications/{id}'}</Tag>,
          but never on the board or <Tag>?name=</Tag>. SIM-25’s deliberate duplicate id is
          freshened, so that one scenario cannot test duplication here.
        </Caption>
        <FormActions>
          <Button variant="primary" onClick={send} busy={busy === 'send'}
                  busyLabel="Sending" disabled={!targetId || Boolean(parsed.error)}>
            Send
          </Button>
          <Button variant="secondary" onClick={startJourney} busy={busy === 'journey'}
                  busyLabel="Starting" disabled={Boolean(parsed.error)}>
            Start full journey
          </Button>
          <Button variant="ghost" onClick={clear} busy={busy === 'clear'}
                  busyLabel="Clearing" disabled={!targetId}>
            Clear {targetId || 'target'} log
          </Button>
        </FormActions>
      </Split>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(row) => row.id}
        maxRows={50}
        total={rows.length}
        footnote="newest first · orchestrator-owned operator log; module boards must keep the 10-row cap"
        rowTone={(row) => httpTone(row.ackHttpStatus)}
        onRowClick={(row) => setExpanded(expanded === row.id ? null : row.id)}
        expandedKey={expanded}
        renderExpanded={(row) => (
          <div className="simulator-expanded">
            {row.statusWarning && (
              <Alert tone="warning" title="Unknown callback word">{row.statusWarning}</Alert>
            )}
            <CodeBlock value={row} scroll />
          </div>
        )}
        empty={
          <EmptyState title="Pick a scenario and press Send">
            The dispatch timeline will appear here.
          </EmptyState>
        }
      />
    </AppShell>
  );
}
