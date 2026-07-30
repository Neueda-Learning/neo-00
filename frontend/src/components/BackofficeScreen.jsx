import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  AppShell,
  Button,
  StatusPill,
  TextInput,
  TopNav,
} from '../design-system';
import ApplicationsScreen from './ApplicationsScreen.jsx';
import ServicesScreen from './ServicesScreen.jsx';
import { api } from '../api.js';

// Applications move every ~3s (2s to decide + 1s between steps), so a 1s poll is
// enough to make the board feel live without hammering the orchestrator.
const POLL_MS = 1000;

const TABS = [
  { id: 'applications', label: 'Applications' },
  { id: 'services', label: 'Services' },
];

/**
 * The "Backoffice Overview Simulation": the two operator screens plus the generator
 * controls. It lives behind the landing choice, so its 1s board poll and identity
 * poll only run while the operator is actually looking at it — the customer journey
 * never does.
 */
export default function BackofficeScreen({ onHome }) {
  const [screen, setScreen] = useState('applications');
  const [health, setHealth] = useState(null);
  const [info, setInfo] = useState(null);
  const [rows, setRows] = useState([]);
  const [summary, setSummary] = useState(null);
  const [services, setServices] = useState([]);
  const [generator, setGenerator] = useState(null);
  const [demo, setDemo] = useState(null);
  const [error, setError] = useState(null);
  // Which write is in flight, so the button that triggered it can show progress. The
  // API is in ap-southeast-1 and a click is a POST plus a reload round-trip, so
  // without this the button looks dead for a second or two.
  const [busy, setBusy] = useState(null);

  const reload = useCallback(async () => {
    try {
      const [board, totals, svc, gen, dem] = await Promise.all([
        api.board(),
        api.summary(),
        api.services(),
        api.generator(),
        api.demoMode(),
      ]);
      setRows(board);
      setSummary(totals);
      setServices(svc);
      setGenerator(gen);
      setDemo(dem);
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }, []);

  // Identity changes far less often than the board — poll it lazily.
  useEffect(() => {
    async function identity() {
      try {
        const [h, i] = await Promise.all([api.health(), api.info()]);
        setHealth(h);
        setInfo(i);
      } catch {
        setHealth(null);
      }
    }
    identity();
    const id = setInterval(identity, 10000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    reload();
    const id = setInterval(reload, POLL_MS);
    return () => clearInterval(id);
  }, [reload]);

  async function toggle(enabled) {
    setBusy('toggle');
    try {
      setGenerator(await api.setGenerator({ enabled }));
      await reload();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  async function changeInterval(intervalMs) {
    setGenerator(await api.setGenerator({ intervalMs }));
  }

  async function createOne() {
    setBusy('create');
    try {
      await api.createApplication();
      await reload();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  async function toggleDemo(enabled) {
    setBusy('demo');
    try {
      setDemo(await api.setDemoMode(enabled));
      await reload();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  // Send the step one parked application is waiting on. No reload: the board already
  // polls every second, and a second fetch would only race it.
  async function proceed(id) {
    setBusy(`proceed:${id}`);
    try {
      await api.proceed(id);
      await reload();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  const up = !error && health?.status === 'UP';
  const running = generator?.enabled ?? false;
  const stepping = demo?.enabled ?? false;

  return (
    <AppShell
      className="backoffice-shell"
      wide
      nav={
        <TopNav
          brand="NEO BANK"
          product="Command centre"
          tabs={TABS}
          active={screen}
          onSelect={setScreen}
          actions={
            <>
              <StatusPill tone={up ? 'positive' : 'negative'}>
                {up ? 'Up' : 'Down'}
              </StatusPill>
              <GeneratorControls
                generator={generator}
                running={running}
                busy={busy}
                onToggle={toggle}
                onInterval={changeInterval}
                onCreateOne={createOne}
              />
              <Button
                variant={stepping ? 'secondary' : 'ghost'}
                size="sm"
                onClick={() => toggleDemo(!stepping)}
                busy={busy === 'demo'}
                aria-pressed={stepping}
                title={
                  stepping
                    ? 'Every step is waiting for you. Switch off to let the parked journeys run.'
                    : 'Demo mode: hold each application before every step so you can send it yourself. Turn the generator off first.'
                }
              >
                {stepping ? `Stepping · ${demo?.parked ?? 0} held` : 'Stepping off'}
              </Button>
              <Button variant="ghost" size="sm" onClick={onHome}>
                ← Home
              </Button>
            </>
          }
        />
      }
      footer="Each application visits the configured services in order, waiting for a callback at every step. Only ACCEPTED advances."
    >
      {error && (
        <Alert
          tone="negative"
          title="Orchestrator unreachable"
          action={
            <Button variant="secondary" size="sm" onClick={reload}>
              Retry
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      {screen === 'applications' ? (
        <ApplicationsScreen
          rows={rows}
          summary={summary}
          services={services}
          busy={busy}
          onProceed={proceed}
        />
      ) : (
        <ServicesScreen services={services} />
      )}
    </AppShell>
  );
}

/**
 * The control that starts and stops the orchestrator sending applications, and the
 * one that adds a single fixture. Both live in the bar because they belong to the
 * whole console, not to either screen.
 */
function GeneratorControls({ generator, running, busy, onToggle, onInterval, onCreateOne }) {
  return (
    <>
      <TextInput
        size="sm"
        type="number"
        min="500"
        step="500"
        value={generator?.intervalMs ?? 5000}
        onChange={(e) => onInterval(Number(e.target.value))}
        aria-label="Milliseconds between generated applications"
        title="Milliseconds between generated applications"
        style={{ width: '9ch' }}
      />
      <Button
        variant="secondary"
        size="sm"
        onClick={onCreateOne}
        busy={busy === 'create'}
        busyLabel="Adding…"
        title="Create a single application"
      >
        + one
      </Button>
      {/* `secondary`, not `primary`: the design system allows one primary per view, and
          during a demo that belongs to the Proceed button on the parked row. */}
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onToggle(!running)}
        busy={busy === 'toggle'}
        aria-pressed={running}
      >
        {running ? 'Sending' : 'Stopped'}
      </Button>
    </>
  );
}
