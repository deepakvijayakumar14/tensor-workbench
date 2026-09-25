import { useEffect, useState } from "react";
import { api } from "./api/client";
import type { Dataset, SystemInfo } from "./api/types";
import { Panel } from "./components/common";
import { DatasetPanel } from "./components/DatasetPanel";
import { RunPanel } from "./components/RunPanel";
import { StatusBar } from "./components/StatusBar";
import { SubmitPanel } from "./components/SubmitPanel";
import { SweepDetail } from "./components/SweepDetail";
import { WorkList } from "./components/WorkList";

export function App() {
  const [info, setInfo] = useState<SystemInfo | null>(null);
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [sweepId, setSweepId] = useState<string | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [listVersion, setListVersion] = useState(0);

  useEffect(() => {
    api.systemInfo().then(setInfo).catch(() => setInfo(null));
  }, []);

  // A freshly created dataset is QUEUED; follow it until it is READY so the submit form can use it.
  useEffect(() => {
    if (!dataset || dataset.status === "READY" || dataset.status === "FAILED") return;
    const timer = setTimeout(async () => {
      const page = await api.datasets(0, 20).catch(() => null);
      const fresh = page?.items.find((d) => d.id === dataset.id);
      if (fresh) setDataset({ ...fresh });
      else setDataset({ ...dataset });
    }, 800);
    return () => clearTimeout(timer);
  }, [dataset]);

  return (
    <div className="app">
      <StatusBar info={info} />
      <main className="layout">
        <div className="column left">
          <DatasetPanel info={info} selectedId={dataset?.id ?? null} onSelect={setDataset} />
          <SubmitPanel
            info={info}
            dataset={dataset}
            onSweep={(s) => {
              setSweepId(s.id);
              setRunId(null);
              setListVersion((v) => v + 1);
            }}
            onRun={(r) => {
              setSweepId(null);
              setRunId(r.id);
              setListVersion((v) => v + 1);
            }}
          />
        </div>
        <div className="column right">
          <Panel step="3" title="Monitor">
            <WorkList
              selectedSweepId={sweepId}
              selectedRunId={runId}
              refreshKey={listVersion}
              onSelectSweep={(id) => {
                setSweepId(id);
                setRunId(null);
              }}
              onSelectRun={(id) => {
                setSweepId(null);
                setRunId(id);
              }}
            />
            {sweepId && <SweepDetail key={sweepId} sweepId={sweepId} selectedRunId={runId} onSelectRun={setRunId} />}
          </Panel>
          <Panel step="4" title="Inspect a result">
            {runId ? (
              <RunPanel key={runId} runId={runId} />
            ) : (
              <p className="muted">Select a run from the table (or a single run) to see its slices, attempts and downloads.</p>
            )}
          </Panel>
        </div>
      </main>
      <footer className="footer">
        <span>{info?.functionDescription}</span>
        <a href="/api/docs" target="_blank" rel="noreferrer">
          API docs
        </a>
      </footer>
    </div>
  );
}
